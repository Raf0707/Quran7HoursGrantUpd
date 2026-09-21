package raf.console.quran7hours

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import raf.console.quran7hours.MainActivity
import raf.console.quran7hours.QuranRepository
import raf.console.quran7hours.RECITERS
import raf.console.quran7hours.everyAyahAudioUrl
import raf.console.quran7hours.githubAyahAudioUrl
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Final offline audio lives in the public Downloads collection:
 * Downloads/Quran7Hours/audio/<reciter>/<SSSAAA>.mp3
 *
 * A single unfinished file is staged in app-private storage as *.part. The staged
 * file survives process restarts and is resumed with HTTP Range, so a network drop
 * never forces a large ayah file to start again from byte zero.
 */
data class ReciterOfflineStats(
    val files: Int = 0,
    val bytes: Long = 0L,
    val partialBytes: Long = 0L
) {
    val totalBytes: Long get() = bytes + partialBytes
    val complete: Boolean get() = files >= AudioOfflineStore.EXPECTED_AYAH_FILES
}

/** Live progress shared between WorkManager, reader sheet and download manager. */
data class ReciterDownloadProgress(
    val reciterId: String,
    val downloaded: Int = 0,
    val total: Int = AudioOfflineStore.EXPECTED_AYAH_FILES,
    val surah: Int = 0,
    val ayah: Int = 0,
    val currentBytes: Long = 0L,
    val currentTotalBytes: Long? = null,
    val status: String = "IDLE",
    val message: String = ""
)

class AudioOfflineStore(private val context: Context) {
    companion object {
        const val EXPECTED_AYAH_FILES = 6236
        private const val MIN_AUDIO_BYTES = 512L
        private const val ROOT = "Download/Quran7Hours/audio"
        private const val PARTS_ROOT = "q7_audio_parts"
        // Short-lived temp files used only by automatic online-listening cache.
        // They are intentionally separate from resumable full-reciter *.part files,
        // so listening to one ayah can never masquerade as a manual bulk download.
        private const val STREAM_CACHE_ROOT = "q7_audio_stream_cache"

        private val fileLocks = ConcurrentHashMap<String, Mutex>()

        fun fileName(surah: Int, providerAyah: Int): String =
            surah.toString().padStart(3, '0') + providerAyah.toString().padStart(3, '0') + ".mp3"
    }

    private fun relativePath(reciterId: String) = "$ROOT/$reciterId/"
    private fun partialDir(reciterId: String) = File(context.filesDir, "$PARTS_ROOT/$reciterId")
    private fun partialFile(reciterId: String, name: String) = File(partialDir(reciterId), "$name.part")
    private fun streamCacheDir(reciterId: String) = File(context.cacheDir, "$STREAM_CACHE_ROOT/$reciterId")
    private fun lockKey(reciterId: String, name: String) = "$reciterId/$name"

    suspend fun localUri(reciterId: String, surah: Int, providerAyah: Int): Uri? = withContext(Dispatchers.IO) {
        val name = fileName(surah, providerAyah)
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.IS_PENDING}=0"
            val args = arrayOf(relativePath(reciterId), name)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    if (c.getLong(sizeCol) >= MIN_AUDIO_BYTES) {
                        return@withContext ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol)
                        )
                    }
                }
            }
            null
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(root, "Quran7Hours/audio/$reciterId/$name")
                .takeIf { it.isFile && it.length() >= MIN_AUDIO_BYTES }
                ?.let(Uri::fromFile)
        }
    }

    suspend fun downloadedCount(reciterId: String): Int =
        downloadedStats(listOf(reciterId))[reciterId]?.files ?: 0

    suspend fun downloadedCounts(reciterIds: Collection<String>): Map<String, Int> =
        downloadedStats(reciterIds).mapValues { it.value.files }

    suspend fun downloadedStats(reciterIds: Collection<String>): Map<String, ReciterOfflineStats> =
        withContext(Dispatchers.IO) {
            val wanted = reciterIds.toSet()
            val files = wanted.associateWith { 0 }.toMutableMap()
            val bytes = wanted.associateWith { 0L }.toMutableMap()

            if (Build.VERSION.SDK_INT >= 29) {
                val projection = arrayOf(
                    MediaStore.Downloads.RELATIVE_PATH,
                    MediaStore.Downloads.SIZE
                )
                val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING}=0"
                val args = arrayOf("$ROOT/%")

                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null
                )?.use { c ->
                    val pathCol = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    while (c.moveToNext()) {
                        val size = c.getLong(sizeCol)
                        if (size < MIN_AUDIO_BYTES) continue
                        val relative = c.getString(pathCol).orEmpty()
                        val reciterId = relative.removePrefix("$ROOT/").substringBefore('/')
                        if (reciterId in wanted) {
                            files[reciterId] = (files[reciterId] ?: 0) + 1
                            bytes[reciterId] = (bytes[reciterId] ?: 0L) + size
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                wanted.forEach { reciterId ->
                    val localFiles = File(root, "Quran7Hours/audio/$reciterId")
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.length() >= MIN_AUDIO_BYTES }
                    files[reciterId] = localFiles.size
                    bytes[reciterId] = localFiles.sumOf { it.length() }
                }
            }

            wanted.associateWith { reciterId ->
                val partBytes = partialDir(reciterId)
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.name.endsWith(".part") }
                    .sumOf { it.length() }
                ReciterOfflineStats(
                    files = files[reciterId] ?: 0,
                    bytes = bytes[reciterId] ?: 0L,
                    partialBytes = partBytes
                )
            }
        }

    suspend fun isReciterComplete(reciterId: String): Boolean =
        downloadedCount(reciterId) >= EXPECTED_AYAH_FILES

    suspend fun deleteReciter(reciterId: String): Int = withContext(Dispatchers.IO) {
        val deletedFinal = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(relativePath(reciterId))
            )
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(root, "Quran7Hours/audio/$reciterId")
            val count = dir.listFiles()?.size ?: 0
            dir.deleteRecursively()
            count
        }
        val parts = partialDir(reciterId)
        val partCount = parts.listFiles()?.size ?: 0
        parts.deleteRecursively()
        // Passive listening cache uses only temporary files here. Final cached MP3s
        // are already counted in deletedFinal because they live in the same final
        // Downloads/Quran7Hours/audio/<reciter>/ structure.
        streamCacheDir(reciterId).deleteRecursively()
        deletedFinal + partCount
    }

    /**
     * Quietly cache one ayah that is already being streamed by ExoPlayer.
     *
     * Important differences from ensureDownloaded():
     *  - no WorkManager task is created;
     *  - no ReciterDownloadProgress is published;
     *  - no resumable bulk-download *.part file is created;
     *  - failures are swallowed because online playback itself must stay primary.
     *
     * The successful MP3 is committed into the SAME final reciter/surah structure,
     * so a later manual full-reciter download sees it through localUri() and skips it.
     */
    suspend fun cacheOnlineAyah(
        reciterId: String,
        surah: Int,
        providerAyah: Int,
        candidates: List<String>
    ): Uri? = withContext(Dispatchers.IO) {
        localUri(reciterId, surah, providerAyah)?.let { return@withContext it }

        // A user-requested full download has priority. Do not make the bulk worker
        // wait behind an automatic cache copy started by merely pressing Play.
        if (AudioDownloadScheduler.isManualTransferActive(reciterId)) return@withContext null

        val name = fileName(surah, providerAyah)
        val mutex = fileLocks.getOrPut(lockKey(reciterId, name)) { Mutex() }

        mutex.withLock {
            localUri(reciterId, surah, providerAyah)?.let { return@withLock it }
            if (AudioDownloadScheduler.isManualTransferActive(reciterId)) return@withLock null

            val dir = streamCacheDir(reciterId).apply { mkdirs() }
            val temp = File(dir, "$name.tmp")
            for (remote in candidates.distinct()) {
                try {
                    if (AudioDownloadScheduler.isManualTransferActive(reciterId)) {
                        temp.delete()
                        return@withLock null
                    }
                    temp.delete()
                    downloadEphemeralCache(reciterId, temp, remote)
                    if (AudioDownloadScheduler.isManualTransferActive(reciterId)) {
                        temp.delete()
                        return@withLock null
                    }
                    return@withLock commitPart(reciterId, name, temp)
                } catch (cancelled: CancellationException) {
                    temp.delete()
                    throw cancelled
                } catch (_: Throwable) {
                    // Silent cache is best-effort. Try the next source and never
                    // turn an online Play tap into a visible download error.
                    temp.delete()
                }
            }
            null
        }
    }

    private fun downloadEphemeralCache(reciterId: String, temp: File, url: String) {
        val connection = openRemote(url, 0L)
        try {
            val response = connection.responseCode
            if (response !in 200..299) error("HTTP $response: $url")
            val expected = connection.contentLengthLong.takeIf { it > 0L }
            var copied = 0L
            FileOutputStream(temp, false).buffered().use { out ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (AudioDownloadScheduler.isManualTransferActive(reciterId)) {
                            error("manual-download-started")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                    }
                    out.flush()
                }
            }
            if (copied < MIN_AUDIO_BYTES) error("Скачан пустой/повреждённый MP3")
            if (expected != null && copied < expected) {
                error("Загрузка прервана: $copied / $expected байт")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun ensureDownloaded(
        reciterId: String,
        surah: Int,
        providerAyah: Int,
        candidates: List<String>,
        onBytesProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Uri = withContext(Dispatchers.IO) {
        localUri(reciterId, surah, providerAyah)?.let { return@withContext it }
        val name = fileName(surah, providerAyah)
        val mutex = fileLocks.getOrPut(lockKey(reciterId, name)) { Mutex() }

        mutex.withLock {
            localUri(reciterId, surah, providerAyah)?.let { return@withLock it }
            var lastError: Throwable? = null
            for (remote in candidates.distinct()) {
                try {
                    return@withLock downloadOneResumable(reciterId, name, remote, onBytesProgress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw (lastError ?: IllegalStateException("Нет доступного источника аудио"))
        }
    }

    private fun openRemote(url: String, offset: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Quran7Hours-Android/1.4")
            setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.1")
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            connect()
        }

    private fun contentRangeTotal(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return Regex("/(\\d+)$").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /** Downloads to a persistent *.part file and resumes it with HTTP Range. */
    private suspend fun downloadOneResumable(
        reciterId: String,
        name: String,
        url: String,
        onBytesProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Uri {
        val dir = partialDir(reciterId).apply { mkdirs() }
        val part = File(dir, "$name.part")
        var offset = part.length().coerceAtLeast(0L)
        var connection = openRemote(url, offset)

        try {
            if (connection.responseCode == 416 && offset > 0L) {
                val remoteTotal = contentRangeTotal(connection.getHeaderField("Content-Range"))
                if (remoteTotal != null && remoteTotal == offset && offset >= MIN_AUDIO_BYTES) {
                    onBytesProgress(offset, remoteTotal)
                    return commitPart(reciterId, name, part)
                }
                connection.disconnect()
                part.delete()
                offset = 0L
                connection = openRemote(url, 0L)
            }

            val response = connection.responseCode
            if (response !in 200..299) error("HTTP $response: $url")

            val append = offset > 0L && response == HttpURLConnection.HTTP_PARTIAL
            if (!append && offset > 0L) {
                // Server ignored Range. Restart only this one file; completed ayahs remain untouched.
                offset = 0L
            }

            val expectedTotal = when {
                response == HttpURLConnection.HTTP_PARTIAL ->
                    contentRangeTotal(connection.getHeaderField("Content-Range"))
                        ?: connection.contentLengthLong.takeIf { it > 0 }?.let { offset + it }
                else -> connection.contentLengthLong.takeIf { it > 0 }
            }

            var copied = offset
            var lastReported = copied
            onBytesProgress(copied, expectedTotal)
            FileOutputStream(part, append).buffered().use { out ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReported >= 256 * 1024) {
                            out.flush()
                            onBytesProgress(copied, expectedTotal)
                            lastReported = copied
                        }
                    }
                    out.flush()
                }
            }
            onBytesProgress(part.length(), expectedTotal)

            val actual = part.length()
            if (actual < MIN_AUDIO_BYTES) error("Скачан пустой/повреждённый MP3")
            if (expectedTotal != null && actual < expectedTotal) {
                error("Загрузка прервана: $actual / $expectedTotal байт")
            }

            return commitPart(reciterId, name, part)
        } finally {
            connection.disconnect()
        }
    }

    private fun commitPart(reciterId: String, name: String, part: File): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath(reciterId))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val sourceSize = part.length()
            if (sourceSize < MIN_AUDIO_BYTES) error("Скачан пустой/повреждённый MP3")

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в Downloads")
            try {
                // Do not validate MediaStore.SIZE while IS_PENDING=1. On some Samsung/Android
                // builds that column is still 0 immediately after closing the stream even though
                // all bytes were successfully written. Validate the actual copy count instead.
                val copied = resolver.openOutputStream(uri, "w")?.buffered()?.use { out ->
                    part.inputStream().buffered().use { input -> input.copyTo(out) }
                } ?: error("Не удалось открыть файл в Downloads для записи")

                if (copied < MIN_AUDIO_BYTES || copied != sourceSize) {
                    error("Не удалось сохранить MP3 полностью: $copied / $sourceSize байт")
                }

                val updated = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                if (updated <= 0) error("Не удалось завершить сохранение MP3")

                // Only remove the resumable part after MediaStore has accepted the final file.
                part.delete()
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, "Quran7Hours/audio/$reciterId").apply { mkdirs() }
        val dest = File(dir, name)
        val tmp = File(dir, ".$name.import")
        part.inputStream().buffered().use { input ->
            tmp.outputStream().buffered().use { out -> input.copyTo(out) }
        }
        if (tmp.length() < MIN_AUDIO_BYTES) {
            tmp.delete()
            error("Не удалось сохранить MP3")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        part.delete()
        return Uri.fromFile(dest)
    }
}

/**
 * One WorkManager task installs one reciter. Every completed ayah is skipped on
 * every restart; the currently interrupted ayah resumes from its *.part byte.
 */
class ReciterFullDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "q7_audio_download"
        private const val CHANNEL_NAME = "Скачивание аудио Корана"
    }

    private val reciterId: String
        get() = inputData.getString("reciter").orEmpty()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reciter = RECITERS[reciterId] ?: return@withContext Result.failure()
        val repository = QuranRepository(applicationContext)
        val store = AudioOfflineStore(applicationContext)

        ensureNotificationChannel()
        var installed = store.downloadedCount(reciterId)
            .coerceIn(0, AudioOfflineStore.EXPECTED_AYAH_FILES)

        suspend fun publish(
            status: String,
            message: String,
            surah: Int = 0,
            ayah: Int = 0,
            currentBytes: Long = 0L,
            currentTotalBytes: Long? = null,
            foreground: Boolean = true
        ) {
            val safeInstalled = installed.coerceIn(0, AudioOfflineStore.EXPECTED_AYAH_FILES)
            val data = workDataOf(
                "reciter" to reciterId,
                "downloaded" to safeInstalled,
                "total" to AudioOfflineStore.EXPECTED_AYAH_FILES,
                "surah" to surah,
                "ayah" to ayah,
                "status" to status,
                "message" to message,
                "currentBytes" to currentBytes,
                "currentTotalBytes" to (currentTotalBytes ?: -1L)
            )
            setProgress(data)
            AudioDownloadScheduler.publish(
                ReciterDownloadProgress(
                    reciterId = reciterId,
                    downloaded = safeInstalled,
                    surah = surah,
                    ayah = ayah,
                    currentBytes = currentBytes,
                    currentTotalBytes = currentTotalBytes,
                    status = status,
                    message = message
                )
            )
            if (foreground) {
                setForeground(
                    createForegroundInfo(
                        reciter.name,
                        safeInstalled,
                        AudioOfflineStore.EXPECTED_AYAH_FILES,
                        message
                    )
                )
            }
        }

        publish("RUNNING", "Запуск загрузки · $installed / ${AudioOfflineStore.EXPECTED_AYAH_FILES}")

        try {
            for (surah in 1..114) {
                val ayahCount = repository.surah(surah).ayahs.size
                for (providerAyah in 1..ayahCount) {
                    if (isStopped) {
                        publish("PAUSED", "Загрузка остановлена", surah, providerAyah, foreground = false)
                        return@withContext Result.success()
                    }

                    if (store.localUri(reciterId, surah, providerAyah) != null) {
                        // Existing online cache / previous full download: count it and move on.
                        installed = store.downloadedCount(reciterId)
                            .coerceIn(0, AudioOfflineStore.EXPECTED_AYAH_FILES)
                        continue
                    }

                    var attempt = 0
                    while (store.localUri(reciterId, surah, providerAyah) == null) {
                        if (isStopped) {
                            publish("PAUSED", "Загрузка остановлена", surah, providerAyah, foreground = false)
                            return@withContext Result.success()
                        }

                        val fileLabel = AudioOfflineStore.fileName(surah, providerAyah)
                        publish(
                            "RUNNING",
                            "Скачиваем $fileLabel · $installed / ${AudioOfflineStore.EXPECTED_AYAH_FILES}",
                            surah,
                            providerAyah
                        )

                        var lastForegroundAt = 0L
                        try {
                            store.ensureDownloaded(
                                reciterId = reciterId,
                                surah = surah,
                                providerAyah = providerAyah,
                                candidates = listOf(
                                    githubAyahAudioUrl(reciterId, surah, providerAyah),
                                    everyAyahAudioUrl(reciterId, surah, providerAyah)
                                )
                            ) { bytes, totalBytes ->
                                val now = SystemClock.elapsedRealtime()
                                val byteText = if (totalBytes != null && totalBytes > 0L) {
                                    "${humanBytes(bytes)} / ${humanBytes(totalBytes)}"
                                } else {
                                    humanBytes(bytes)
                                }
                                val text = "Сура $surah · аят $providerAyah · $byteText"
                                AudioDownloadScheduler.publish(
                                    ReciterDownloadProgress(
                                        reciterId = reciterId,
                                        downloaded = installed,
                                        surah = surah,
                                        ayah = providerAyah,
                                        currentBytes = bytes,
                                        currentTotalBytes = totalBytes,
                                        status = "RUNNING",
                                        message = text
                                    )
                                )
                                if (now - lastForegroundAt >= 800L) {
                                    setProgress(
                                        workDataOf(
                                            "reciter" to reciterId,
                                            "downloaded" to installed,
                                            "total" to AudioOfflineStore.EXPECTED_AYAH_FILES,
                                            "surah" to surah,
                                            "ayah" to providerAyah,
                                            "status" to "RUNNING",
                                            "message" to text,
                                            "currentBytes" to bytes,
                                            "currentTotalBytes" to (totalBytes ?: -1L)
                                        )
                                    )
                                    setForeground(
                                        createForegroundInfo(
                                            reciter.name,
                                            installed,
                                            AudioOfflineStore.EXPECTED_AYAH_FILES,
                                            text
                                        )
                                    )
                                    lastForegroundAt = now
                                }
                            }

                            installed = store.downloadedCount(reciterId)
                                .coerceIn(0, AudioOfflineStore.EXPECTED_AYAH_FILES)
                            attempt = 0
                            publish(
                                "RUNNING",
                                "Готово: $installed / ${AudioOfflineStore.EXPECTED_AYAH_FILES} · следующий аят…",
                                surah,
                                providerAyah
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            val message = error.message.orEmpty()
                            val httpCode = Regex("HTTP (\\d{3})").find(message)
                                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                            val permanentHttpError = httpCode != null &&
                                    httpCode in 400..499 &&
                                    httpCode !in setOf(408, 425, 429)

                            if (permanentHttpError) {
                                val text = "Ошибка $httpCode для $fileLabel. Файл отсутствует в репозитории."
                                publish("ERROR", text, surah, providerAyah)
                                postErrorNotification(reciter.name, text)
                                return@withContext Result.failure(
                                    workDataOf(
                                        "reciter" to reciterId,
                                        "error" to text,
                                        "surah" to surah,
                                        "ayah" to providerAyah
                                    )
                                )
                            }

                            attempt++
                            val waitSeconds = min(30, 1 shl min(attempt, 4))
                            val text = buildString {
                                append("Соединение прервано на суре $surah, аяте $providerAyah")
                                if (message.isNotBlank()) append(" · ").append(message.take(90))
                                append(" · повтор через $waitSeconds с")
                            }
                            publish("RETRY", text, surah, providerAyah)
                            delay(waitSeconds * 1_000L)
                        }
                    }
                }
            }

            val finalCount = store.downloadedCount(reciterId)
            installed = finalCount
            if (finalCount < AudioOfflineStore.EXPECTED_AYAH_FILES) {
                val text = "Скачано $finalCount из ${AudioOfflineStore.EXPECTED_AYAH_FILES} файлов"
                publish("ERROR", text)
                postErrorNotification(reciter.name, text)
                return@withContext Result.failure(workDataOf("reciter" to reciterId, "error" to text))
            }

            publish("SUCCEEDED", "Скачано полностью · $finalCount файлов")
            postFinishedNotification(reciter.name, finalCount)
            Result.success(
                workDataOf(
                    "reciter" to reciterId,
                    "downloaded" to finalCount,
                    "total" to AudioOfflineStore.EXPECTED_AYAH_FILES
                )
            )
        } catch (cancelled: CancellationException) {
            AudioDownloadScheduler.publish(
                ReciterDownloadProgress(
                    reciterId = reciterId,
                    downloaded = installed,
                    status = "PAUSED",
                    message = "Загрузка остановлена"
                )
            )
            throw cancelled
        } catch (error: Throwable) {
            val text = error.message ?: "Неизвестная ошибка загрузки"
            AudioDownloadScheduler.publish(
                ReciterDownloadProgress(
                    reciterId = reciterId,
                    downloaded = installed,
                    status = "ERROR",
                    message = text
                )
            )
            postErrorNotification(reciter.name, text)
            Result.failure(workDataOf("reciter" to reciterId, "error" to text))
        }
    }

    private fun humanBytes(value: Long): String = when {
        value >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f МБ", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format(java.util.Locale.US, "%.0f КБ", value / 1024.0)
        else -> "$value Б"
    }

    private fun notificationId(): Int =
        0x71000000 or (reciterId.hashCode() and 0x00FFFFFF)

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновое скачивание полного аудио выбранного чтеца"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            applicationContext,
            notificationId(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createForegroundInfo(
        reciterName: String,
        downloaded: Int,
        total: Int,
        text: String
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Скачивание Корана · $reciterName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, downloaded.coerceIn(0, total), false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                cancelIntent
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun postFinishedNotification(reciterName: String, count: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Аудио скачано")
            .setContentText("$reciterName · $count аятов доступны офлайн")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            NotificationManagerCompat.from(applicationContext).notify(notificationId(), notification)
        }
    }

    private fun postErrorNotification(reciterName: String, message: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Загрузка аудио требует внимания")
            .setContentText("$reciterName · $message")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reciterName · $message"))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            NotificationManagerCompat.from(applicationContext).notify(notificationId(), notification)
        }
    }
}

object AudioDownloadScheduler {
    private const val PREFS = "q7_audio_download_settings"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_MANUAL_PREFIX = "manual_started_"

    private val _progress = MutableStateFlow<Map<String, ReciterDownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ReciterDownloadProgress>> = _progress.asStateFlow()

    internal fun publish(value: ReciterDownloadProgress) {
        _progress.update { old -> old + (value.reciterId to value) }
    }

    fun clearProgress(reciterId: String) {
        _progress.update { old -> old - reciterId }
    }

    private fun unique(reciterId: String) = "q7-audio-full-$reciterId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True only after the user explicitly pressed Download/Continue for this reciter. */
    fun wasManualDownloadStarted(context: Context, reciterId: String): Boolean =
        prefs(context).getBoolean(KEY_MANUAL_PREFIX + reciterId, false)

    private fun markManualDownloadStarted(context: Context, reciterId: String) {
        prefs(context).edit().putBoolean(KEY_MANUAL_PREFIX + reciterId, true).apply()
    }

    /** Used after deleting a reciter: cached listening alone must not look like a paused bulk install. */
    fun clearManualDownload(context: Context, reciterId: String) {
        prefs(context).edit().remove(KEY_MANUAL_PREFIX + reciterId).apply()
        clearProgress(reciterId)
    }

    /** In-memory fast path used by quiet online caching to yield to an explicit full download. */
    fun isManualTransferActive(reciterId: String): Boolean {
        val status = _progress.value[reciterId]?.status
        return status == "ENQUEUED" || status == "RUNNING" || status == "RETRY"
    }

    fun wifiOnly(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnly(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_ONLY, value)
            .apply()
    }

    fun enqueueReciter(context: Context, reciterId: String) {
        if (reciterId !in RECITERS) return
        val appContext = context.applicationContext
        // This flag is set ONLY by an explicit UI action that calls enqueueReciter().
        // Merely listening online never touches it.
        markManualDownloadStarted(appContext, reciterId)
        val networkType = if (wifiOnly(appContext)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<ReciterFullDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf("reciter" to reciterId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(unique(reciterId))
            .build()

        publish(
            ReciterDownloadProgress(
                reciterId = reciterId,
                status = "ENQUEUED",
                message = if (wifiOnly(appContext)) "Поставлено в очередь · ожидаем Wi‑Fi" else "Поставлено в очередь"
            )
        )
        WorkManager.getInstance(appContext)
            // REPLACE removes a stale ENQUEUED/FAILED request; completed files and *.part remain resumable.
            .enqueueUniqueWork(unique(reciterId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, reciterId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(unique(reciterId))
        _progress.update { old ->
            val current = old[reciterId]
            old + (reciterId to (current ?: ReciterDownloadProgress(reciterId)).copy(
                status = "PAUSED",
                message = "Загрузка приостановлена"
            ))
        }
    }

    suspend fun state(context: Context, reciterId: String): WorkInfo.State? =
        withContext(Dispatchers.IO) {
            runCatching {
                val infos = WorkManager.getInstance(context.applicationContext)
                    .getWorkInfosForUniqueWork(unique(reciterId))
                    .get()
                (infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?: infos.lastOrNull())
                    ?.state
            }.getOrNull()
        }
}

