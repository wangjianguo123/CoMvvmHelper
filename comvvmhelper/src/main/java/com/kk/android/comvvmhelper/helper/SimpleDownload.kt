@file:Suppress("MemberVisibilityCanBePrivate")

package com.kk.android.comvvmhelper.helper

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.kk.android.comvvmhelper.extension.otherwise
import com.kk.android.comvvmhelper.extension.yes
import com.kk.android.comvvmhelper.utils.getMimeTypeByFile
import kotlinx.coroutines.Dispatchers
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat

/**
 * @author kuky.
 * @description Download helper, support progress listener, cancel download task, pause download task
 */
data class DownloadWrapper(
    var downloadUrl: String = "",
    var storedFilePath: String = "", // store file path for download file below AndroidQ or use legacy On Q
    var qWrapper: QWrapper = QWrapper(), // Download Params for AndroidQ
    var urlParams: HashMap<String, Any> = hashMapOf(),
    var onProgressChange: suspend (Float) -> Unit = {},
    var onDownloadFinished: suspend () -> Unit = {},
    var onDownloadFailed: suspend (Throwable) -> Unit = {}
)

data class QWrapper(
    var displayFileNameForQ: String = "", // Display name For File
    var relativePathForQ: String = "", // relative path for file
    var useLegacyOnQ: Boolean = false,
    var downloadType: DownloadType = DownloadType.DOWNLOADS
)


/**
 * Download Type for Android Q
 * @see [Context.getExternalFilesDir]
 */
enum class DownloadType(internal val type: String) {
    PICTURES(Environment.DIRECTORY_PICTURES),
    MOVIES(Environment.DIRECTORY_MOVIES),
    MUSIC(Environment.DIRECTORY_MUSIC),
    DOWNLOADS(Environment.DIRECTORY_DOWNLOADS)
}

class DownloadHelper private constructor(private val context: Context) {

    companion object : SingletonHelperArg1<DownloadHelper, Context>(::DownloadHelper)

    private val mPausedPool = hashMapOf<String, Boolean>()

    private val mCancelPool = hashMapOf<String, Boolean>()

    fun installApkFile(context: Context, file: File) {
        try {
            val uri = (Build.VERSION.SDK_INT > Build.VERSION_CODES.M)
                .yes { FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file) }
                .otherwise { Uri.fromFile(file) }

            context.startActivity(Intent().apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseOrResumeDownload(downloadUrl: String, paused: Boolean) {
        mPausedPool[downloadUrl] = paused
    }

    fun cancelDownload(downloadUrl: String) {
        mCancelPool[downloadUrl] = true
    }

    fun pausedOrResumeAllTask(paused: Boolean) {
        mPausedPool.forEach { entry ->
            pauseOrResumeDownload(entry.key, paused)
        }
    }

    fun cancelAllTask() {
        mCancelPool.forEach { entry ->
            cancelDownload(entry.key)
        }
    }

    suspend fun simpleDownload(wrapper: DownloadWrapper.() -> Unit) {
        val doConfig = DownloadWrapper().apply(wrapper)

        mPausedPool[doConfig.downloadUrl] = false
        mCancelPool[doConfig.downloadUrl] = false

        check(doConfig.storedFilePath.matches(Regex("[a-zA-Z_0-9.\\-()%/]+"))) { "illegal file store path" }

        http {
            flowDispatcher = Dispatchers.IO

            baseUrl = doConfig.downloadUrl

            params = doConfig.urlParams

            onSuccess = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    realDownload(it, doConfig)
                } else {
                    (doConfig.qWrapper.useLegacyOnQ && Environment.isExternalStorageLegacy())
                        .yes { realDownload(it, doConfig) }
                        .otherwise { realDownloadForQ(it, doConfig) }
                }
            }

            onFail = { doConfig.onDownloadFailed(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("BlockingMethodInNonBlockingContext")
    internal suspend fun realDownloadForQ(response: Response, wrapper: DownloadWrapper) {
        val qWrapper = wrapper.qWrapper
        check(qWrapper.displayFileNameForQ.isNotBlank()) { "DisplayName is necessary for Q download" }

        val inputStream = response.body?.byteStream()

        if (inputStream == null || inputStream.available() <= 0) {
            wrapper.onDownloadFailed(IllegalStateException("illegal input stream"))
            return
        }

        val progressFormat = DecimalFormat("00.00")

        val downloadValues = ContentValues()
        val externalState = Environment.getExternalStorageState()

        val uri = when (qWrapper.downloadType) {
            DownloadType.PICTURES -> {
                downloadValues.put(MediaStore.Images.Media.DISPLAY_NAME, qWrapper.displayFileNameForQ)
                downloadValues.put(MediaStore.Images.Media.MIME_TYPE, getMimeTypeByFile(qWrapper.displayFileNameForQ))
                if (qWrapper.relativePathForQ.isNotBlank()) {
                    downloadValues.put(MediaStore.Images.Media.RELATIVE_PATH, qWrapper.relativePathForQ)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadValues.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                }

                val url = (externalState == Environment.MEDIA_MOUNTED)
                    .yes { MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
                    .otherwise { MediaStore.Images.Media.INTERNAL_CONTENT_URI }
                context.contentResolver.insert(url, downloadValues)
            }

            DownloadType.MOVIES -> {
                downloadValues.put(MediaStore.Video.Media.DISPLAY_NAME, qWrapper.displayFileNameForQ)
                downloadValues.put(MediaStore.Video.Media.MIME_TYPE, getMimeTypeByFile(qWrapper.displayFileNameForQ))
                if (qWrapper.relativePathForQ.isNotBlank()) {
                    downloadValues.put(MediaStore.Video.Media.RELATIVE_PATH, qWrapper.relativePathForQ)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadValues.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                }

                val url = (externalState == Environment.MEDIA_MOUNTED)
                    .yes { MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
                    .otherwise { MediaStore.Video.Media.INTERNAL_CONTENT_URI }
                context.contentResolver.insert(url, downloadValues)
            }

            DownloadType.MUSIC -> {
                downloadValues.put(MediaStore.Audio.Media.DISPLAY_NAME, qWrapper.displayFileNameForQ)
                downloadValues.put(MediaStore.Audio.Media.MIME_TYPE, getMimeTypeByFile(qWrapper.displayFileNameForQ))
                if (qWrapper.relativePathForQ.isNotBlank()) {
                    downloadValues.put(MediaStore.Audio.Media.RELATIVE_PATH, qWrapper.relativePathForQ)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadValues.put(MediaStore.Audio.Media.DATE_TAKEN, System.currentTimeMillis())
                }

                val url = (externalState == Environment.MEDIA_MOUNTED)
                    .yes { MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
                    .otherwise { MediaStore.Audio.Media.INTERNAL_CONTENT_URI }
                context.contentResolver.insert(url, downloadValues)
            }

            DownloadType.DOWNLOADS -> {
                downloadValues.put(MediaStore.Downloads.DISPLAY_NAME, qWrapper.displayFileNameForQ)
                downloadValues.put(MediaStore.Downloads.MIME_TYPE, getMimeTypeByFile(qWrapper.displayFileNameForQ))
                if (qWrapper.relativePathForQ.isNotBlank()) {
                    downloadValues.put(MediaStore.Downloads.RELATIVE_PATH, qWrapper.relativePathForQ)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadValues.put(MediaStore.Downloads.DATE_TAKEN, System.currentTimeMillis())
                }

                val url = (externalState == Environment.MEDIA_MOUNTED)
                    .yes { MediaStore.Downloads.EXTERNAL_CONTENT_URI }
                    .otherwise { MediaStore.Downloads.INTERNAL_CONTENT_URI }
                context.contentResolver.insert(url, downloadValues)
            }
        }

        uri?.run {
            val buffer = ByteArray(1024)
            var bos: BufferedOutputStream? = null
            val outputStream = context.contentResolver.openOutputStream(this) ?: return
            val bis = BufferedInputStream(inputStream)

            try {
                var sum = 0L
                val total = response.body?.contentLength() ?: -1

                if (total == -1L) {
                    Log.e("Download", "error on get contentLength for download url: ${wrapper.downloadUrl}, maybe service internal error")
                }

                bos = BufferedOutputStream(outputStream)
                var length = bis.read(buffer)

                while (length != -1) {
                    if (mCancelPool[wrapper.downloadUrl] == true) {
                        context.contentResolver.delete(this, null, null)
                        mCancelPool.remove(wrapper.downloadUrl)
                        break
                    }

                    while (mPausedPool[wrapper.downloadUrl] == true) {
                        try {
                            Thread.sleep(300)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    bos.write(buffer, 0, length)
                    bos.flush()
                    length = bis.read(buffer)
                    sum += length
                    val progress = sum * 1f / total
                    wrapper.onProgressChange(progressFormat.format(progress).toFloat())
                }

                wrapper.onProgressChange(1f)
                wrapper.onDownloadFinished()
            } catch (e: Exception) {
                e.printStackTrace()
                wrapper.onDownloadFailed(e)
            } finally {
                bis.close()
                bos?.close()
                mPausedPool.remove(wrapper.downloadUrl)
                mCancelPool.remove(wrapper.downloadUrl)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    internal suspend fun realDownload(response: Response, wrapper: DownloadWrapper) {
        val inputStream = response.body?.byteStream()

        if (inputStream == null || inputStream.available() <= 0) {
            wrapper.onDownloadFailed(IllegalStateException("illegal input stream"))
            return
        }

        val progressFormat = DecimalFormat("00.00")
        val storeFile = File(wrapper.storedFilePath)

        if (storeFile.parentFile?.exists() == false) {
            storeFile.parentFile?.mkdirs()
        }

        if (!storeFile.exists()) {
            storeFile.createNewFile()
        }

        val buffer = ByteArray(1024)
        var fileOutputStream: FileOutputStream? = null

        try {
            var sum = 0L
            val total = response.body?.contentLength() ?: -1

            if (total == -1L) {
                Log.e("Download", "error on get contentLength for download url: ${wrapper.downloadUrl}, maybe service internal error")
            }

            fileOutputStream = FileOutputStream(storeFile)
            var length = inputStream.read(buffer)

            while (length != -1) {
                if (mCancelPool[wrapper.downloadUrl] == true) {
                    storeFile.delete()
                    mCancelPool.remove(wrapper.downloadUrl)
                    break
                }

                while (mPausedPool[wrapper.downloadUrl] == true) {
                    try {
                        Thread.sleep(300)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                fileOutputStream.write(buffer, 0, length)
                length = inputStream.read(buffer)
                sum += length
                val progress = sum * 1f / total
                wrapper.onProgressChange(progressFormat.format(progress).toFloat())
            }

            fileOutputStream.flush()
            wrapper.onProgressChange(1f)
            wrapper.onDownloadFinished()
        } catch (e: Exception) {
            e.printStackTrace()
            wrapper.onDownloadFailed(e)
        } finally {
            inputStream.close()
            fileOutputStream?.close()
            mPausedPool.remove(wrapper.downloadUrl)
            mCancelPool.remove(wrapper.downloadUrl)
        }
    }
}