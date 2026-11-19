package com.example.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.ai.TeluguScriptChunker
import com.example.model.CameraMotion
import com.example.model.CaptionCue
import com.example.model.CaptionStyle
import com.example.model.StoryScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

data class ExportSceneInput(
    val scene: StoryScene,
    val imagePath: String?,
    val audioPath: String?
)

data class ExportResult(
    val localFile: File,
    val galleryUri: Uri?
)

/**
 * Native Android HD 720p MP4 Long-Video Compiler.
 * Streams scene-by-scene through MediaCodec (H.264 AVC + AAC) + MediaMuxer so even long
 * multi-minute Telugu videos use low, constant RAM while burning in Ken Burns camera motion,
 * dialogue-synchronized Telugu captions (Noto Sans Telugu), and synchronized narration audio.
 */
class LongVideoExporter(private val context: Context) {

    companion object {
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 24
        private const val VIDEO_BITRATE = 3_200_000
        private const val AUDIO_SAMPLE_RATE = 24_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BITRATE = 96_000
        private const val TIMEOUT_US = 10_000L
    }

    suspend fun exportLongVideo(
        projectTitle: String,
        captionStyle: CaptionStyle,
        scenes: List<ExportSceneInput>,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        require(scenes.isNotEmpty()) { "At least one scene is required to export a video." }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val teluguTypeface = ResourcesCompat.getFont(context, R.font.noto_sans_telugu) ?: Typeface.DEFAULT_BOLD
        val displayTypeface = ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.DEFAULT_BOLD

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        val pendingVideoSamples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        val pendingAudioSamples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

        fun maybeStartMuxer() {
            if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                muxer.start()
                muxerStarted = true
                for ((bytes, info) in pendingVideoSamples) {
                    muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(bytes), info)
                }
                pendingVideoSamples.clear()
                for ((bytes, info) in pendingAudioSamples) {
                    muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(bytes), info)
                }
                pendingAudioSamples.clear()
            }
        }

        fun drainEncoder(codec: MediaCodec, isVideo: Boolean, endOfStream: Boolean) {
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (isVideo) {
                            videoTrackIndex = muxer.addTrack(newFormat)
                        } else {
                            audioTrackIndex = muxer.addTrack(newFormat)
                        }
                        maybeStartMuxer()
                    }
                    outIndex >= 0 -> {
                        val encodedData = codec.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerStarted) {
                                muxer.writeSampleData(
                                    if (isVideo) videoTrackIndex else audioTrackIndex,
                                    encodedData,
                                    bufferInfo
                                )
                            } else {
                                val copy = ByteArray(bufferInfo.size)
                                encodedData.get(copy)
                                val infoCopy = MediaCodec.BufferInfo().apply {
                                    set(0, copy.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                if (isVideo) {
                                    pendingVideoSamples.add(copy to infoCopy)
                                } else {
                                    pendingAudioSamples.add(copy to infoCopy)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
        }

        try {
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            var videoPtsUs = 0L
            var audioSamplesWritten = 0L
            val frameDurationUs = 1_000_000L / VIDEO_FPS

            val totalScenes = scenes.size

            scenes.forEachIndexed { sceneIdx, input ->
                coroutineContext.ensureActive()
                onProgress(
                    sceneIdx.toFloat() / totalScenes.toFloat(),
                    "Rendering scene ${sceneIdx + 1} of $totalScenes (${input.scene.titleTelugu})…"
                )

                val scenePcm = loadWavAs24kMonoPcm(input.audioPath)
                val sceneDurationMs = if (scenePcm != null && scenePcm.size >= 4800) {
                    ((scenePcm.size / 2L) * 1000L) / AUDIO_SAMPLE_RATE
                } else {
                    input.scene.durationMs.coerceAtLeast(3_000L)
                }

                val targetPcmBytes = ((sceneDurationMs * AUDIO_SAMPLE_RATE / 1000L) * 2L).toInt()
                val alignedPcm = ByteArray(targetPcmBytes)
                if (scenePcm != null) {
                    val copyLen = min(scenePcm.size, alignedPcm.size)
                    System.arraycopy(scenePcm, 0, alignedPcm, 0, copyLen)
                }

                val sceneBitmap = loadScaledBitmap(input.imagePath)
                val cues = TeluguScriptChunker.buildSyncedCaptionCues(
                    captionLines = input.scene.captionLines,
                    fallbackDialogue = input.scene.dialogueTelugu,
                    totalDurationMs = sceneDurationMs
                )

                val totalFrames = ((sceneDurationMs * VIDEO_FPS) / 1000L).toInt().coerceAtLeast(VIDEO_FPS)
                var pcmOffset = 0
                val bytesPerFrameAudio = ((AUDIO_SAMPLE_RATE / VIDEO_FPS) * 2).coerceAtLeast(1024)

                for (frameIdx in 0 until totalFrames) {
                    coroutineContext.ensureActive()
                    val progress = frameIdx.toFloat() / (totalFrames - 1).coerceAtLeast(1).toFloat()
                    val sceneTimeMs = (frameIdx * 1000L) / VIDEO_FPS
                    val activeCue = cues.firstOrNull { sceneTimeMs in it.startMs..it.endMs }
                        ?: cues.lastOrNull()

                    val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        inputSurface.lockHardwareCanvas()
                    } else {
                        inputSurface.lockCanvas(null)
                    }
                    try {
                        drawCinemaFrame(
                            canvas = canvas,
                            bitmap = sceneBitmap,
                            scene = input.scene,
                            motionProgress = progress,
                            activeCaption = activeCue,
                            captionStyle = captionStyle,
                            teluguTypeface = teluguTypeface,
                            displayTypeface = displayTypeface,
                            fadeAlpha = computeFadeAlpha(frameIdx, totalFrames)
                        )
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                    }

                    videoPtsUs += frameDurationUs
                    drainEncoder(videoEncoder, isVideo = true, endOfStream = false)

                    // Feed matching chunk of audio PCM interleaved with video frames
                    if (pcmOffset < alignedPcm.size) {
                        val chunkLen = min(bytesPerFrameAudio, alignedPcm.size - pcmOffset)
                        feedAudioChunk(
                            audioEncoder = audioEncoder,
                            pcmData = alignedPcm,
                            offset = pcmOffset,
                            length = chunkLen,
                            ptsUs = (audioSamplesWritten * 1_000_000L) / AUDIO_SAMPLE_RATE
                        )
                        pcmOffset += chunkLen
                        audioSamplesWritten += (chunkLen / 2)
                        drainEncoder(audioEncoder, isVideo = false, endOfStream = false)
                    }

                    if (frameIdx % 12 == 0) {
                        val overall = (sceneIdx + progress) / totalScenes.toFloat()
                        onProgress(
                            overall.coerceIn(0f, 0.96f),
                            "Encoding HD clip ${sceneIdx + 1}/$totalScenes • ${(overall * 100).toInt()}%"
                        )
                    }
                }

                // Flush any remaining PCM bytes for this scene
                while (pcmOffset < alignedPcm.size) {
                    val chunkLen = min(4096, alignedPcm.size - pcmOffset)
                    feedAudioChunk(
                        audioEncoder = audioEncoder,
                        pcmData = alignedPcm,
                        offset = pcmOffset,
                        length = chunkLen,
                        ptsUs = (audioSamplesWritten * 1_000_000L) / AUDIO_SAMPLE_RATE
                    )
                    pcmOffset += chunkLen
                    audioSamplesWritten += (chunkLen / 2)
                    drainEncoder(audioEncoder, isVideo = false, endOfStream = false)
                }

                sceneBitmap?.recycle()
            }

            // Signal End of Stream on both encoders
            videoEncoder.signalEndOfInputStream()
            drainEncoder(videoEncoder, isVideo = true, endOfStream = true)

            val eosInputIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US * 10)
            if (eosInputIdx >= 0) {
                audioEncoder.queueInputBuffer(
                    eosInputIdx,
                    0,
                    0,
                    (audioSamplesWritten * 1_000_000L) / AUDIO_SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drainEncoder(audioEncoder, isVideo = false, endOfStream = true)

            onProgress(0.98f, "Saving video to Movies/KathaVani…")
        } finally {
            try { videoEncoder.stop() } catch (_: Exception) {}
            try { videoEncoder.release() } catch (_: Exception) {}
            try { audioEncoder.stop() } catch (_: Exception) {}
            try { audioEncoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }

        val galleryUri = saveVideoToMediaStore(outputFile, projectTitle)
        onProgress(1f, "Video export complete!")
        ExportResult(localFile = outputFile, galleryUri = galleryUri)
    }

    private fun feedAudioChunk(
        audioEncoder: MediaCodec,
        pcmData: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long
    ) {
        var remaining = length
        var cursor = offset
        var currentPts = ptsUs
        while (remaining > 0) {
            val inputIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIdx < 0) break
            val inputBuffer = audioEncoder.getInputBuffer(inputIdx) ?: break
            inputBuffer.clear()
            val toWrite = min(remaining, inputBuffer.capacity())
            inputBuffer.put(pcmData, cursor, toWrite)
            audioEncoder.queueInputBuffer(inputIdx, 0, toWrite, currentPts, 0)
            cursor += toWrite
            remaining -= toWrite
            currentPts += ((toWrite / 2L) * 1_000_000L) / AUDIO_SAMPLE_RATE
        }
    }

    private fun computeFadeAlpha(frameIdx: Int, totalFrames: Int): Float {
        val fadeFrames = 10f
        return when {
            frameIdx < fadeFrames -> (frameIdx / fadeFrames).coerceIn(0f, 1f)
            frameIdx > totalFrames - fadeFrames -> ((totalFrames - frameIdx) / fadeFrames).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    private fun drawCinemaFrame(
        canvas: Canvas,
        bitmap: Bitmap?,
        scene: StoryScene,
        motionProgress: Float,
        activeCaption: CaptionCue?,
        captionStyle: CaptionStyle,
        teluguTypeface: Typeface,
        displayTypeface: Typeface,
        fadeAlpha: Float
    ) {
        canvas.drawColor(Color.parseColor("#0E0A16"))

        if (bitmap != null) {
            val w = VIDEO_WIDTH.toFloat()
            val h = VIDEO_HEIGHT.toFloat()
            val motion = scene.cameraMotionEnum
            val scale: Float
            val tx: Float
            val ty: Float

            when (motion) {
                CameraMotion.ZOOM_IN -> {
                    scale = 1.0f + 0.12f * motionProgress
                    tx = 0f
                    ty = 0f
                }
                CameraMotion.ZOOM_OUT -> {
                    scale = 1.12f - 0.12f * motionProgress
                    tx = 0f
                    ty = 0f
                }
                CameraMotion.PAN_LEFT -> {
                    scale = 1.12f
                    tx = (0.045f - 0.09f * motionProgress) * w
                    ty = 0f
                }
                CameraMotion.PAN_RIGHT -> {
                    scale = 1.12f
                    tx = (-0.045f + 0.09f * motionProgress) * w
                    ty = 0f
                }
                CameraMotion.DRIFT_UP -> {
                    scale = 1.12f
                    tx = 0f
                    ty = (0.04f - 0.08f * motionProgress) * h
                }
            }

            canvas.save()
            canvas.translate(w / 2f + tx, h / 2f + ty)
            canvas.scale(scale, scale)
            val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            val dstAspect = w / h
            val drawW: Float
            val drawH: Float
            if (srcAspect > dstAspect) {
                drawH = h
                drawW = h * srcAspect
            } else {
                drawW = w
                drawH = w / srcAspect
            }
            val dstRect = RectF(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, null, dstRect, paint)
            canvas.restore()
        } else {
            // Subtle dark gradient backdrop if user exported before generating image for a scene
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(),
                    Color.parseColor("#1D1333"),
                    Color.parseColor("#0B0714"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(), bgPaint)
        }

        // Bottom cinema gradient scrim for crisp Telugu caption legibility
        val scrimPaint = Paint().apply {
            shader = LinearGradient(
                0f, VIDEO_HEIGHT * 0.56f, 0f, VIDEO_HEIGHT.toFloat(),
                Color.TRANSPARENT,
                Color.argb(225, 10, 7, 18),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, VIDEO_HEIGHT * 0.56f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(), scrimPaint)

        // Subtle scene badge top-left
        val badgeText = "SCENE ${scene.sceneIndex + 1} • ${scene.titleTelugu}"
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 245, 166, 35)
            textSize = 22f
            typeface = teluguTypeface
        }
        canvas.drawText(badgeText, 44f, 54f, badgePaint)

        // Draw synchronized Telugu caption
        if (activeCaption != null && activeCaption.text.isNotBlank()) {
            val maxCaptionWidth = (VIDEO_WIDTH * 0.84f).toInt()
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = teluguTypeface
                textSize = when (captionStyle) {
                    CaptionStyle.BOLD_KARAOKE -> 42f
                    else -> 36f
                }
                color = when (captionStyle) {
                    CaptionStyle.CINEMA_GOLD -> Color.parseColor("#FFD275")
                    CaptionStyle.CLEAN_SUBTITLE -> Color.WHITE
                    CaptionStyle.BOLD_KARAOKE -> Color.parseColor("#FAF6F0")
                }
                setShadowLayer(6f, 0f, 3f, Color.BLACK)
            }

            val staticLayout = StaticLayout.Builder.obtain(
                activeCaption.text, 0, activeCaption.text.length, textPaint, maxCaptionWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(6f, 1.05f)
                .build()

            val boxWidth = min(maxCaptionWidth.toFloat(), getMaxLineWidth(staticLayout) + 64f)
            val boxHeight = staticLayout.height + 34f
            val boxLeft = (VIDEO_WIDTH - boxWidth) / 2f
            val boxBottom = VIDEO_HEIGHT - 44f
            val boxTop = boxBottom - boxHeight

            // Speaker pill above caption if speaker is present
            if (scene.speaker.isNotBlank()) {
                val speakerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = teluguTypeface
                    textSize = 24f
                    color = Color.parseColor("#1A1002")
                }
                val spWidth = speakerPaint.measureText(scene.speaker) + 36f
                val spRect = RectF(
                    (VIDEO_WIDTH - spWidth) / 2f,
                    boxTop - 44f,
                    (VIDEO_WIDTH + spWidth) / 2f,
                    boxTop - 8f
                )
                val spBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F5A623")
                }
                canvas.drawRoundRect(spRect, 18f, 18f, spBg)
                canvas.drawText(
                    scene.speaker,
                    spRect.centerX() - speakerPaint.measureText(scene.speaker) / 2f,
                    spRect.centerY() + 8f,
                    speakerPaint
                )
            }

            // Caption backdrop pill
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (captionStyle) {
                    CaptionStyle.CINEMA_GOLD -> Color.argb(185, 18, 12, 28)
                    CaptionStyle.CLEAN_SUBTITLE -> Color.argb(175, 0, 0, 0)
                    CaptionStyle.BOLD_KARAOKE -> Color.argb(210, 42, 24, 66)
                }
            }
            val pillRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxBottom)
            canvas.drawRoundRect(pillRect, 20f, 20f, pillPaint)

            if (captionStyle == CaptionStyle.CINEMA_GOLD || captionStyle == CaptionStyle.BOLD_KARAOKE) {
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    color = Color.argb(140, 245, 166, 35)
                }
                canvas.drawRoundRect(pillRect, 20f, 20f, strokePaint)
            }

            canvas.save()
            canvas.translate((VIDEO_WIDTH - maxCaptionWidth) / 2f, boxTop + 16f)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        // Smooth fade-in / fade-out transition veil
        if (fadeAlpha < 0.99f) {
            val fadePaint = Paint().apply {
                color = Color.argb(((1f - fadeAlpha) * 255f).toInt().coerceIn(0, 255), 14, 10, 22)
            }
            canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(), fadePaint)
        }
    }

    private fun getMaxLineWidth(layout: StaticLayout): Float {
        var max = 0f
        for (i in 0 until layout.lineCount) {
            val w = layout.getLineWidth(i)
            if (w > max) max = w
        }
        return max
    }

    private fun loadScaledBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path)
    }

    /**
     * Reads a RIFF WAV file and converts it into 24kHz 16-bit mono PCM suitable for AAC muxing.
     */
    private fun loadWavAs24kMonoPcm(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 44L) return null
        return try {
            val allBytes = FileInputStream(file).use { it.readBytes() }
            if (allBytes.size <= 44) return null
            val bb = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)
            val channels = bb.getShort(22).toInt().coerceAtLeast(1)
            val sampleRate = bb.getInt(24).coerceAtLeast(8000)

            // Find 'data' chunk offset safely
            var dataOffset = 44
            var pos = 12
            while (pos + 8 <= allBytes.size) {
                val chunkId = String(allBytes, pos, 4)
                val chunkSize = bb.getInt(pos + 4)
                if (chunkId == "data") {
                    dataOffset = pos + 8
                    break
                }
                pos += 8 + chunkSize.coerceAtLeast(0)
            }

            val rawPcm = allBytes.copyOfRange(dataOffset, allBytes.size)
            if (channels == 1 && sampleRate == AUDIO_SAMPLE_RATE) {
                return rawPcm
            }

            // Downmix to mono & linear resample to AUDIO_SAMPLE_RATE
            val inputSamples = rawPcm.size / (2 * channels)
            if (inputSamples <= 0) return null
            val inBuf = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)
            val mono = ShortArray(inputSamples)
            for (i in 0 until inputSamples) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += inBuf.getShort((i * channels + ch) * 2).toInt()
                }
                mono[i] = (sum / channels).toShort()
            }

            if (sampleRate == AUDIO_SAMPLE_RATE) {
                val outBuf = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                mono.forEach { outBuf.putShort(it) }
                return outBuf.array()
            }

            val outSampleCount = ((inputSamples.toLong() * AUDIO_SAMPLE_RATE) / sampleRate).toInt().coerceAtLeast(1)
            val outBuf = ByteBuffer.allocate(outSampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            val ratio = inputSamples.toDouble() / outSampleCount.toDouble()
            for (i in 0 until outSampleCount) {
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, inputSamples - 1)
                val idx1 = (idx0 + 1).coerceIn(0, inputSamples - 1)
                val frac = srcPos - idx0
                val sample = (mono[idx0] * (1.0 - frac) + mono[idx1] * frac).toInt().toShort()
                outBuf.putShort(sample)
            }
            outBuf.array()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveVideoToMediaStore(sourceFile: File, projectTitle: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val safeName = projectTitle
                .replace(Regex("[^a-zA-Z0-9\\u0C00-\\u0C7F_\\- ]"), "")
                .trim()
                .take(40)
                .ifEmpty { "KathaVani_Video" }
            val fileName = "${safeName}_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/KathaVani")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }
}
