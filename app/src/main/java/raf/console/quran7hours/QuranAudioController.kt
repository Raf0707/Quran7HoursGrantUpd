package raf.console.quran7hours

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import raf.console.quran7hours.AudioOfflineStore

class QuranAudioController(
    context: Context,
    private val repository: QuranRepository,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val offlineStore = AudioOfflineStore(appContext)

    val player: ExoPlayer = ExoPlayer.Builder(appContext).build()
    val hadrPlayer: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setMediaItem(MediaItem.fromUri("android.resource://${appContext.packageName}/${R.raw.quran_7_hours}"))
        prepare()
    }

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration
    private val _repeat = MutableStateFlow(false)
    val repeat: StateFlow<Boolean> = _repeat
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _downloadRequired = MutableStateFlow<String?>(null)
    val downloadRequired: StateFlow<String?> = _downloadRequired

    private val _hadrPlaying = MutableStateFlow(false)
    val hadrPlaying: StateFlow<Boolean> = _hadrPlaying
    private val _hadrPosition = MutableStateFlow(0L)
    val hadrPosition: StateFlow<Long> = _hadrPosition
    private val _hadrDuration = MutableStateFlow(0L)
    val hadrDuration: StateFlow<Long> = _hadrDuration
    private val _hadrVisible = MutableStateFlow(false)
    val hadrVisible: StateFlow<Boolean> = _hadrVisible

    private var prepareJob: Job? = null
    private var sourceCandidates: List<Uri> = emptyList()
    private var sourceIndex = 0
    private var sourceAutoPlay = true

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            _position.value = player.currentPosition.coerceAtLeast(0L)
            _duration.value = player.duration.takeIf { it > 0 } ?: 0L
            _hadrPosition.value = hadrPlayer.currentPosition.coerceAtLeast(0L)
            _hadrDuration.value = hadrPlayer.duration.takeIf { it > 0 } ?: 0L
            handler.postDelayed(this, 250L)
        }
    }

    /** Exactly one audio engine may be audible at a time. */
    private fun silenceHadrForAyah() {
        hadrPlayer.playWhenReady = false
        hadrPlayer.pause()
        _hadrPlaying.value = false
    }

    /** Prevent a pending/buffering ayah request from starting underneath Hadr. */
    private fun silenceAyahForHadr() {
        sourceAutoPlay = false
        player.playWhenReady = false
        player.pause()
        _playing.value = false
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) silenceHadrForAyah()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _duration.value = player.duration.takeIf { it > 0 } ?: 0L
                if (playbackState == Player.STATE_READY) _error.value = null
                if (playbackState == Player.STATE_ENDED) {
                    scope.launch {
                        if (_repeat.value) {
                            player.seekTo(0)
                            player.play()
                        } else if (preferences.settings.value.autoAdvance) {
                            advance(1)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!tryNextSource()) {
                    _error.value = "Не удалось воспроизвести аят ни из локального файла, ни через GitHub/EveryAyah."
                    _playing.value = false
                }
            }
        })
        hadrPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _hadrPlaying.value = isPlaying
                if (isPlaying) silenceAyahForHadr()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _hadrDuration.value = hadrPlayer.duration.takeIf { it > 0 } ?: 0L
            }
        })
        handler.post(ticker)
    }

    fun playAyah(surah: Int, ayah: Int, name: String) {
        val spec = ayahPlayback(preferences.settings.value.reciter, surah, ayah)
        val current = _track.value
        if (current?.kind == TrackKind.AYAH && current.surah == surah && ayah in current.ayah..current.endAyah) {
            toggle()
            return
        }
        playTrack(Track(surah, spec.groupStartAyah, spec.groupEndAyah, name, TrackKind.AYAH))
    }

    fun playBismillah(surah: Int, name: String) {
        val current = _track.value
        if (current?.kind == TrackKind.BISMILLAH && current.surah == surah) {
            toggle()
            return
        }
        playTrack(Track(surah, 0, 0, name, TrackKind.BISMILLAH))
    }

    fun playTrack(track: Track, autoPlay: Boolean = true) {
        if (autoPlay) silenceHadrForAyah()
        prepareJob?.cancel()
        _track.value = track
        _error.value = null
        _downloadRequired.value = null
        sourceAutoPlay = autoPlay
        player.stop()
        player.clearMediaItems()

        prepareJob = scope.launch {
            val storedReciter = preferences.settings.value.reciter
            val reciterId = if (storedReciter == "nabil_rifai" || storedReciter !in RECITERS) {
                preferences.updateSettings { it.copy(reciter = "alafasy") }
                "alafasy"
            } else {
                storedReciter
            }

            try {
                sourceCandidates = if (track.kind == TrackKind.BISMILLAH) {
                    val providerAyah = if (track.surah == 1) 1 else 0
                    buildList {
                        val local = offlineStore.localUri(reciterId, track.surah, providerAyah)
                        local?.let(::add)

                        val github = githubBismillahAudioUrl(reciterId, track.surah)
                        if (local == null) {
                            cacheOnlineCopy(
                                reciterId,
                                track.surah,
                                providerAyah,
                                listOf(github)
                            )
                        }

                        // Playback itself never waits for caching: ExoPlayer starts streaming immediately.
                        add(Uri.parse(github))
                        if (track.surah == 1) {
                            add(Uri.parse(githubAyahAudioUrl(reciterId, 1, 1)))
                            add(Uri.parse(everyAyahAudioUrl(reciterId, 1, 1)))
                        }
                    }.distinct()
                } else {
                    val spec = ayahPlayback(reciterId, track.surah, track.ayah)
                    buildList {
                        val local = offlineStore.localUri(reciterId, track.surah, spec.providerAyah)
                        local?.let(::add)

                        val github = githubAyahAudioUrl(reciterId, track.surah, spec.providerAyah)
                        if (local == null) {
                            cacheOnlineCopy(
                                reciterId,
                                track.surah,
                                spec.providerAyah,
                                listOf(
                                    github,
                                    everyAyahAudioUrl(reciterId, track.surah, spec.providerAyah)
                                )
                            )
                        }

                        add(Uri.parse(github))
                        add(Uri.parse(everyAyahAudioUrl(reciterId, track.surah, spec.providerAyah)))
                    }.distinct()
                }

                sourceIndex = 0
                if (sourceCandidates.isEmpty()) {
                    _playing.value = false
                    _error.value = "Нет доступного источника аудио."
                } else {
                    playCurrentSource()
                }
            } catch (t: Throwable) {
                sourceCandidates = emptyList()
                _playing.value = false
                _error.value = "Не удалось подготовить аудио: ${t.message ?: "неизвестная ошибка"}"
            }
        }
    }


    /**
     * Online playback remains instant, but the same GitHub MP3 is also copied in
     * the background to the final offline structure. A later full-reciter install
     * therefore skips ayahs the user has already listened to.
     */
    private fun cacheOnlineCopy(
        reciterId: String,
        surah: Int,
        providerAyah: Int,
        candidates: List<String>
    ) {
        scope.launch(Dispatchers.IO) {
            // Best-effort and completely silent. This never creates WorkManager work,
            // never posts a notification and never publishes full-download progress.
            // On success the final MP3 still lands in the normal offline structure,
            // so an explicit full-reciter install skips it later.
            runCatching {
                offlineStore.cacheOnlineAyah(
                    reciterId = reciterId,
                    surah = surah,
                    providerAyah = providerAyah,
                    candidates = candidates
                )
            }
        }
    }

    fun dismissDownloadRequest() {
        _downloadRequired.value = null
    }

    private fun playCurrentSource() {
        val uri = sourceCandidates.getOrNull(sourceIndex) ?: return
        if (sourceAutoPlay) silenceHadrForAyah()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = sourceAutoPlay
    }

    private fun tryNextSource(): Boolean {
        if (sourceIndex + 1 >= sourceCandidates.size) return false
        sourceIndex++
        playCurrentSource()
        return true
    }

    fun reloadForReciter() {
        _track.value?.let { playTrack(it, _playing.value) }
    }

    fun toggle() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        silenceHadrForAyah()
        sourceAutoPlay = true
        if (player.mediaItemCount > 0) {
            // ExoPlayer keeps the current media item at STATE_ENDED. Calling
            // play() alone does not reliably restart it on all Media3/player
            // combinations, so explicitly rewind the finished ayah first.
            val ended = player.playbackState == Player.STATE_ENDED ||
                    (_duration.value > 0L && player.currentPosition >= (_duration.value - 120L).coerceAtLeast(0L))
            if (ended) player.seekTo(0L)
            player.play()
            return
        }
        _track.value?.let { playTrack(it, true) }
    }

    fun close() {
        prepareJob?.cancel()
        player.stop()
        player.clearMediaItems()
        sourceCandidates = emptyList()
        _track.value = null
        _playing.value = false
        _position.value = 0
        _duration.value = 0
        _error.value = null
    }

    fun seekTo(positionMs: Long) {
        val max = _duration.value.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0L, max))
    }
    fun setRepeat(value: Boolean) { _repeat.value = value }

    fun toggleHadr() {
        _hadrVisible.value = true
        if (hadrPlayer.isPlaying) {
            hadrPlayer.pause()
        } else {
            silenceAyahForHadr()
            hadrPlayer.playWhenReady = true
            hadrPlayer.play()
        }
    }
    fun playHadr() {
        _hadrVisible.value = true
        silenceAyahForHadr()
        hadrPlayer.playWhenReady = true
        hadrPlayer.play()
    }
    fun pauseHadr() { hadrPlayer.pause() }
    fun closeHadr() {
        hadrPlayer.pause()
        hadrPlayer.seekTo(0L)
        _hadrVisible.value = false
        _hadrPlaying.value = false
        _hadrPosition.value = 0L
    }
    fun seekHadr(positionMs: Long) {
        val max = _hadrDuration.value.takeIf { it > 0 } ?: Long.MAX_VALUE
        hadrPlayer.seekTo(positionMs.coerceIn(0L, max))
    }

    suspend fun advance(direction: Int) {
        val track = _track.value ?: return
        if (track.kind == TrackKind.BISMILLAH) {
            if (direction > 0) {
                val s = repository.surah(track.surah)
                val spec = ayahPlayback(preferences.settings.value.reciter, s.id, 1)
                playTrack(Track(s.id, spec.groupStartAyah, spec.groupEndAyah, s.nameRu))
            } else if (track.surah > 1) {
                val p = repository.surah(track.surah - 1)
                val last = p.ayahs.lastOrNull()?.a ?: 1
                val spec = ayahPlayback(preferences.settings.value.reciter, p.id, last)
                playTrack(Track(p.id, spec.groupStartAyah, spec.groupEndAyah, p.nameRu))
            }
            return
        }
        val currentSurah = repository.surah(track.surah)
        val spec = ayahPlayback(preferences.settings.value.reciter, track.surah, track.ayah)
        val candidate = if (direction > 0) spec.groupEndAyah + 1 else spec.groupStartAyah - 1
        if (candidate in 1..currentSurah.ayahs.size) {
            val target = ayahPlayback(preferences.settings.value.reciter, track.surah, candidate)
            playTrack(Track(track.surah, target.groupStartAyah, target.groupEndAyah, currentSurah.nameRu))
            return
        }
        if (direction < 0 && track.surah == 1 && spec.groupStartAyah == 1) {
            playTrack(Track(1, 0, 0, currentSurah.nameRu, TrackKind.BISMILLAH))
            return
        }
        if (direction > 0 && track.surah < 114) {
            val next = repository.surah(track.surah + 1)
            // Canonical Mushaf transition: read the basmala before the next
            // surah, except At-Tawbah (9), which has no opening basmala.
            if (next.id == 9) {
                val target = ayahPlayback(preferences.settings.value.reciter, next.id, 1)
                playTrack(Track(next.id, target.groupStartAyah, target.groupEndAyah, next.nameRu))
            } else {
                // When this basmala ends, STATE_ENDED -> advance(1) enters ayah 1
                // of `next.id` because the BISMILLAH track keeps the target surah.
                playTrack(Track(next.id, 0, 0, next.nameRu, TrackKind.BISMILLAH))
            }
        } else if (direction < 0 && track.surah > 1) {
            val prev = repository.surah(track.surah - 1)
            val last = prev.ayahs.lastOrNull()?.a ?: 1
            val target = ayahPlayback(preferences.settings.value.reciter, prev.id, last)
            playTrack(Track(prev.id, target.groupStartAyah, target.groupEndAyah, prev.nameRu))
        }
    }

    fun release() {
        prepareJob?.cancel()
        handler.removeCallbacks(ticker)
        player.release()
        hadrPlayer.release()
    }
}

