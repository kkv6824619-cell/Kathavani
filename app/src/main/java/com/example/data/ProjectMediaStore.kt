package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProjectMediaStore(private val context: Context) {

    private fun projectRoot(userId: String, projectId: String): File {
        val dir = File(context.filesDir, "kathavani/${sanitize(userId)}/${sanitize(projectId)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

    fun sceneImageFile(userId: String, projectId: String, sceneId: String): File {
        val dir = File(projectRoot(userId, projectId), "visuals")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${sanitize(sceneId)}.jpg")
    }

    fun sceneAudioFile(userId: String, projectId: String, sceneId: String): File {
        val dir = File(projectRoot(userId, projectId), "audio")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${sanitize(sceneId)}.wav")
    }

    fun exportedVideoFile(userId: String, projectId: String): File {
        val dir = File(projectRoot(userId, projectId), "exports")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "kathavani_${sanitize(projectId)}.mp4")
    }

    fun saveSceneBitmap(userId: String, projectId: String, sceneId: String, bitmap: Bitmap): File {
        val outFile = sceneImageFile(userId, projectId, sceneId)
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        return outFile
    }

    /**
     * Saves synthesized narration audio as a standard RIFF WAV file.
     * If Gemini returns raw PCM (audio/L16 or audio/pcm), wraps it with a valid WAV header.
     * If it already starts with RIFF, writes directly.
     */
    fun saveSceneAudio(
        userId: String,
        projectId: String,
        sceneId: String,
        audioBytes: ByteArray,
        mimeType: String
    ): Pair<File, Long> {
        val outFile = sceneAudioFile(userId, projectId, sceneId)
        val isAlreadyWav = audioBytes.size >= 12 &&
            audioBytes[0] == 'R'.code.toByte() &&
            audioBytes[1] == 'I'.code.toByte() &&
            audioBytes[2] == 'F'.code.toByte() &&
            audioBytes[3] == 'F'.code.toByte()

        val finalBytes = if (isAlreadyWav) {
            audioBytes
        } else {
            val sampleRate = parseSampleRateFromMime(mimeType) ?: 24_000
            wrapPcmAsWav(pcmBytes = audioBytes, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        }

        FileOutputStream(outFile).use { fos ->
            fos.write(finalBytes)
        }

        val durationMs = measureAudioDurationMs(outFile)
        return outFile to durationMs
    }

    fun measureAudioDurationMs(file: File): Long {
        if (!file.exists() || file.length() <= 44L) return 0L
        // First inspect WAV header directly for instant, deterministic duration
        val wavDuration = readWavDurationMs(file)
        if (wavDuration > 0L) return wavDuration

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun getExistingImagePath(userId: String, projectId: String, sceneId: String): String? {
        val file = sceneImageFile(userId, projectId, sceneId)
        return if (file.exists() && file.length() > 512L) file.absolutePath else null
    }

    fun getExistingAudioInfo(userId: String, projectId: String, sceneId: String): Pair<String, Long>? {
        val file = sceneAudioFile(userId, projectId, sceneId)
        if (!file.exists() || file.length() <= 44L) return null
        val durationMs = measureAudioDurationMs(file).coerceAtLeast(1_000L)
        return file.absolutePath to durationMs
    }

    fun getExistingExportPath(userId: String, projectId: String): String? {
        val file = exportedVideoFile(userId, projectId)
        return if (file.exists() && file.length() > 4096L) file.absolutePath else null
    }

    fun deleteProjectMedia(userId: String, projectId: String) {
        val dir = projectRoot(userId, projectId)
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun parseSampleRateFromMime(mimeType: String): Int? {
        val match = Regex("rate=(\\d+)").find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun readWavDurationMs(file: File): Long {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(44)
                if (input.read(header) < 44) return 0L
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") return 0L
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val byteRate = bb.getInt(28)
                if (byteRate <= 0) return 0L
                val dataBytes = (file.length() - 44L).coerceAtLeast(0L)
                (dataBytes * 1000L) / byteRate.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        fun wrapPcmAsWav(
            pcmBytes: ByteArray,
            sampleRate: Int = 24_000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcmBytes.size
            val chunkSize = 36 + dataSize

            val out = ByteArrayOutputStream(44 + dataSize)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM subchunk size
            header.putShort(1) // PCM audio format
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())
            out.write(pcmBytes)
            return out.toByteArray()
        }
    }
}
