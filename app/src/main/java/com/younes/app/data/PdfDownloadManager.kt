package com.younes.app.data

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "PdfDownloadManager"

object PdfDownloadManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val progressCallbacks = ConcurrentHashMap<String, (Int) -> Unit>()

    interface DownloadCallback {
        fun onProgress(examId: String, progress: Int)
        fun onSuccess(examId: String, file: File)
        fun onError(examId: String, message: String)
    }

    fun downloadPdf(
        examId: String,
        pdfUrl: String,
        callback: DownloadCallback,
        scope: CoroutineScope
    ) {
        // Annuler un download en cours pour le même exam
        activeJobs[examId]?.cancel()

        activeJobs[examId] = scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Téléchargement: $pdfUrl")

                val request = Request.Builder().url(pdfUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback.onError(examId, "Erreur HTTP ${response.code}")
                    }
                    return@launch
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) {
                        callback.onError(examId, "Réponse vide")
                    }
                    return@launch
                }

                val contentLength = body.contentLength()
                val file = ExamRepository.getDownloadedExamFile(examId)

                // S'assurer que le répertoire parent existe
                file.parentFile?.mkdirs()

                FileOutputStream(file).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Long = 0
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read

                            if (contentLength > 0) {
                                val progress = ((bytesRead * 100) / contentLength).toInt()
                                withContext(Dispatchers.Main) {
                                    callback.onProgress(examId, progress)
                                }
                            }

                            ensureActive()
                        }
                    }
                }

                Log.d(TAG, "Téléchargement terminé: ${file.absolutePath} (${file.length()} bytes)")
                withContext(Dispatchers.Main) {
                    callback.onSuccess(examId, file)
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Téléchargement annulé: $examId")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur téléchargement: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback.onError(examId, "Erreur: ${e.message}")
                }
            } finally {
                activeJobs.remove(examId)
            }
        }
    }

    fun cancelDownload(examId: String) {
        activeJobs[examId]?.cancel()
        activeJobs.remove(examId)
    }

    fun isDownloading(examId: String): Boolean = activeJobs.containsKey(examId)
}
