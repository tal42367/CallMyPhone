package com.yishai.parasha

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object LocalWhisper {
    interface Callback {
        fun onProgress(percent: Int, status: String)
        fun onSuccess(text: String, segmentsJson: String)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MODEL_NAME = "ggml-base.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true"

    @JvmStatic
    fun transcribeHebrew(
        context: Context,
        inputVideo: File,
        answerStartSec: Double,
        callback: Callback
    ) {
        scope.launch {
            var modelHandle: dev.ffmpegkit.whisper.WhisperModel? = null
            var wav: File? = null
            try {
                callback.onProgress(1, "מכין מנוע כתוביות")
                val modelFile = ensureModel(context, callback)

                callback.onProgress(86, "מחלץ את הקול מהתשובה")
                val cacheDir = File(context.cacheDir, "whisper")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                wav = File(cacheDir, "answer_${System.currentTimeMillis()}.wav")

                val start = answerStartSec.coerceAtLeast(0.0)
                val cmd = "-y -ss $start -i \"${inputVideo.absolutePath}\" -vn -ac 1 -ar 16000 -c:a pcm_s16le \"${wav.absolutePath}\""
                val session = FFmpegKit.execute(cmd)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    throw IllegalStateException("audio extraction failed")
                }

                callback.onProgress(90, "טוען מנוע עברית")
                modelHandle = Whisper.loadModel(context, modelFile.absolutePath)

                callback.onProgress(94, "מזהה את הדיבור בעברית")
                val result = Whisper.transcribe(
                    modelHandle,
                    wav.absolutePath,
                    WhisperConfig(
                        language = "he",
                        translate = false,
                        threads = 4,
                        maxSegmentLength = 64,
                        printTimestamps = true
                    )
                )

                val segments = JSONArray()
                result.segments.forEach { segment ->
                    val item = JSONObject()
                    item.put("startMs", segment.startMs)
                    item.put("endMs", segment.endMs)
                    item.put("text", segment.text)
                    segments.put(item)
                }

                callback.onProgress(100, "הכתוביות מוכנות")
                callback.onSuccess(result.text.trim(), segments.toString())
            } catch (e: Exception) {
                callback.onError(e.message ?: e.javaClass.simpleName)
            } finally {
                modelHandle?.let {
                    try {
                        Whisper.releaseModel(it)
                    } catch (_: Exception) {
                    }
                }
                wav?.let {
                    try {
                        if (it.exists()) it.delete()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun ensureModel(context: Context, callback: Callback): File {
        val dir = File(context.filesDir, "whisper")
        if (!dir.exists()) dir.mkdirs()

        val model = File(dir, MODEL_NAME)
        if (model.exists() && model.length() > 100_000_000L) {
            callback.onProgress(84, "מנוע הכתוביות כבר מותקן")
            return model
        }

        val temp = File(dir, "$MODEL_NAME.download")
        if (temp.exists()) temp.delete()

        callback.onProgress(2, "מוריד פעם אחת מנוע כתוביות — כ־142MB")

        var currentUrl = URL(MODEL_URL)
        var connection: HttpURLConnection? = null

        try {
            var connected = false
            repeat(6) {
                val conn = currentUrl.openConnection() as HttpURLConnection
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "YishaiParasha/0.8")
                conn.connect()

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("model redirect failed")
                    conn.disconnect()
                    currentUrl = URL(currentUrl, location)
                } else {
                    connected = true
                    return@repeat
                }
            }

            val conn = connection ?: throw IllegalStateException("model connection failed")
            if (!connected || conn.responseCode !in 200..299) {
                throw IllegalStateException("model download HTTP ${conn.responseCode}")
            }

            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1

            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read

                        if (total > 0) {
                            val percent = (2 + ((done * 80) / total)).toInt().coerceIn(2, 82)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                callback.onProgress(percent, "מוריד מנוע כתוביות — $percent%")
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (temp.length() < 100_000_000L) {
                throw IllegalStateException("model download incomplete")
            }

            if (model.exists()) model.delete()
            if (!temp.renameTo(model)) {
                temp.copyTo(model, overwrite = true)
                temp.delete()
            }

            callback.onProgress(84, "מנוע הכתוביות הותקן")
            return model
        } finally {
            connection?.disconnect()
        }
    }
}
