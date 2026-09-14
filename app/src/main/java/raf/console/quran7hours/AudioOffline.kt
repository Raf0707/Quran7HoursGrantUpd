package raf.quran7hours.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

class AudioOfflineStore(private val context: Context) {
    companion object {
        const val EXPECTED_AYAH_FILES = 6236
        private const val MIN_AUDIO_BYTES = 512L
        private const val ROOT = "Download/Quran7Hours/audio"
        private const val PARTS_ROOT = "q7_audio_parts"

        private val fileLocks = ConcurrentHashMap<String, Mutex>()

        fun fileName(surah: Int, providerAyah: Int): String =
            surah.toString().padStart(3, '0') + providerAyah.toString().padStart(3, '0') + ".mp3"
    }

    private fun relativePath(reciterId: String) = "$ROOT/$reciterId/"
    private fun partialDir(reciterId: String) = File(context.filesDir, "$PARTS_ROOT/$reciterId")
    private fun partialFile(reciterId: String, name: String) = File(partialDir(reciterId), "$name.part")
    private fun lockKey(reciterId: String, name: String) = "$reciterId/$name"

    suspend fun localUri(reciterId: String, surah: Int, providerAyah: Int): Uri? = withContext(Dispatchers.IO) {
        val name = fileName(surah, providerAyah)
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
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
                val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
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
        deletedFinal + partCount
    }

    suspend fun ensureDownloaded(
        reciterId: String,
        surah: Int,
        providerAyah: Int,
        candidates: List<String>
    ): Uri = withContext(Dispatchers.IO) {
        localUri(reciterId, surah, providerAyah)?.let { return@withContext it }
        val name = fileName(surah, providerAyah)
        val mutex = fileLocks.getOrPut(lockKey(reciterId, name)) { Mutex() }

        mutex.withLock {
            localUri(reciterId, surah, providerAyah)?.let { return@withLock it }
            var lastError: Throwable? = null
            for (remote in candidates.distinct()) {
                try {
                    return@withLock downloadOneResumable(reciterId, name, remote)
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
    private fun downloadOneResumable(reciterId: String, name: String, url: String): Uri {
        val dir = partialDir(reciterId).apply { mkdirs() }
        val part = File(dir, "$name.part")
        var offset = part.length().coerceAtLeast(0L)
        var connection = openRemote(url, offset)

        try {
            if (connection.responseCode == 416 && offset > 0L) {
                val remoteTotal = contentRangeTotal(connection.getHeaderField("Content-Range"))
                if (remoteTotal != null && remoteTotal == offset && offset >= MIN_AUDIO_BYTES) {
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

            FileOutputStream(part, append).buffered().use { out ->
                connection.inputStream.buffered().use { input -> input.copyTo(out) }
            }

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
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в Downloads")
            try {
                resolver.openOutputStream(uri, "w")!!.buffered().use { out ->
                    part.inputStream().buffered().use { input -> input.copyTo(out) }
                }
                val size = resolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                } ?: 0L
                if (size < MIN_AUDIO_BYTES) error("Не удалось сохранить MP3")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
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
        private const val PROGRESS_STEP = 8
    }

    private val reciterId: String
        get() = inputData.getString("reciter").orEmpty()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reciter = RECITERS[reciterId] ?: return@withContext Result.failure()
        val repository = QuranRepository(applicationContext)
        val store = AudioOfflineStore(applicationContext)

        ensureNotificationChannel()
        setForeground(
            createForegroundInfo(
                reciter.name,
                store.downloadedCount(reciterId),
                AudioOfflineStore.EXPECTED_AYAH_FILES,
                "Проверяем уже сохранённые аяты…"
            )
        )

        try {
            var installed = store.downloadedCount(reciterId)
                .coerceIn(0, AudioOfflineStore.EXPECTED_AYAH_FILES)
            var processed = 0

            for (surah in 1..114) {
                val ayahCount = repository.surah(surah).ayahs.size
                for (providerAyah in 1..ayahCount) {
                    if (isStopped) return@withContext Result.retry()
                    processed++

                    if (store.localUri(reciterId, surah, providerAyah) == null) {
                        // Full packs use exactly the same GitHub repository structure as online playback.
                        store.ensureDownloaded(
                            reciterId = reciterId,
                            surah = surah,
                            providerAyah = providerAyah,
                            candidates = listOf(githubAyahAudioUrl(reciterId, surah, providerAyah))
                        )
                        installed++
                    }

                    if (
                        processed % PROGRESS_STEP == 0 ||
                        providerAyah == ayahCount ||
                        installed >= AudioOfflineStore.EXPECTED_AYAH_FILES
                    ) {
                        val safeInstalled = installed.coerceIn(0, AudioOfflineStore.EXPECTED_AYAH_FILES)
                        val text = "Сура $surah · аят $providerAyah · $safeInstalled / ${AudioOfflineStore.EXPECTED_AYAH_FILES}"
                        setProgress(
                            workDataOf(
                                "reciter" to reciterId,
                                "downloaded" to safeInstalled,
                                "total" to AudioOfflineStore.EXPECTED_AYAH_FILES,
                                "surah" to surah,
                                "ayah" to providerAyah
                            )
                        )
                        setForeground(
                            createForegroundInfo(
                                reciter.name,
                                safeInstalled,
                                AudioOfflineStore.EXPECTED_AYAH_FILES,
                                text
                            )
                        )
                    }
                }
            }

            val finalCount = store.downloadedCount(reciterId)
            if (finalCount < AudioOfflineStore.EXPECTED_AYAH_FILES) {
                error("Скачано $finalCount из ${AudioOfflineStore.EXPECTED_AYAH_FILES} файлов")
            }

            postFinishedNotification(reciter.name, finalCount)
            Result.success(
                workDataOf(
                    "reciter" to reciterId,
                    "downloaded" to finalCount,
                    "total" to AudioOfflineStore.EXPECTED_AYAH_FILES
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!isStopped) postErrorNotification(reciter.name, error.message ?: "Ошибка загрузки")
            Result.retry()
        }
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
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, downloaded.coerceIn(0, total), downloaded <= 0)
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
            NotificationManagerCompat.from(applicationContext).notify(notificationId(), notification)
        }
    }

    private fun postErrorNotification(reciterName: String, message: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Загрузка аудио приостановлена")
            .setContentText("$reciterName · $message")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(notificationId(), notification)
        }
    }
}

object AudioDownloadScheduler {
    private const val PREFS = "q7_audio_download_settings"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private fun unique(reciterId: String) = "q7-audio-full-$reciterId"

    fun wifiOnly(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, true)

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

        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(unique(reciterId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, reciterId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(unique(reciterId))
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
