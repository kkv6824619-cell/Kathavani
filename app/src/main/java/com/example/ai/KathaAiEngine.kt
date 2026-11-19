package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.model.CameraMotion
import com.example.model.SceneMood
import com.example.model.StoryScene
import com.example.model.VisualStyle
import com.example.model.VoiceProfile
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "KathaAiEngine"

data class SynthesizedAudio(
    val bytes: ByteArray,
    val mimeType: String
)

class KathaAiEngine {

    private val storyboardSchema = Schema.obj(
        properties = mapOf(
            "scenes" to Schema.array(
                description = "Sequential cinematic video scenes covering every part of this Telugu script chunk.",
                items = Schema.obj(
                    properties = mapOf(
                        "titleTelugu" to Schema.string(description = "Short vivid scene title in Telugu script."),
                        "speaker" to Schema.string(description = "Speaker or narrator name in Telugu (e.g., నరేటర్ or character name)."),
                        "dialogueTelugu" to Schema.string(description = "Complete Telugu dialogue or narration spoken in this scene, preserving authentic Telugu script."),
                        "captionLines" to Schema.array(
                            items = Schema.string(),
                            description = "2 to 6 crisp, dialogue-synchronized Telugu caption lines (each 4 to 9 words) that together cover the exact spoken dialogueTelugu in order."
                        ),
                        "visualPromptEn" to Schema.string(description = "Detailed cinematic English visual prompt describing setting, lighting, composition, costumes, and emotion for 16:9 image generation. Never request embedded text."),
                        "cameraMotion" to Schema.enumeration(
                            values = listOf("ZOOM_IN", "ZOOM_OUT", "PAN_LEFT", "PAN_RIGHT", "DRIFT_UP"),
                            description = "Best Ken Burns camera motion for this clip."
                        ),
                        "mood" to Schema.enumeration(
                            values = listOf("DRAMATIC", "SERENE", "EPIC", "EMOTIONAL", "MYSTERIOUS", "JOYFUL"),
                            description = "Dominant emotional atmosphere of the scene."
                        ),
                        "voiceDirection" to Schema.string(description = "Brief English performance note for natural human-like Telugu voice narration.")
                    )
                )
            )
        )
    )

    private val storyboardModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = storyboardSchema
                temperature = 0.6f
            }
        )
    }

    private val imageModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-nano-banana-2.1",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
                imageConfig = ImageConfig.builder()
                    .setAspectRatio(AspectRatio.LANDSCAPE_16x9)
                    .setImageSize(ImageSize.SIZE_1K)
                    .build()
            }
        )
    }

    @OptIn(PublicPreviewAPI::class)
    private fun buildTtsModel(voiceProfile: VoiceProfile) = Firebase.ai.generativeModel(
        modelName = "gemini-3.1-flash-tts-preview",
        generationConfig = generationConfig {
            responseModalities = listOf(ResponseModality.AUDIO)
            speechConfig = SpeechConfig(voice = Voice(voiceProfile.geminiVoiceName))
        }
    )

    /**
     * Breaks one chunk of a Telugu script (up to 10,000 words total across chunks) into
     * structured scenes with Telugu dialogue, dialogue-aligned captions, and visual prompts.
     */
    suspend fun analyzeScriptChunk(
        userId: String,
        projectId: String,
        chunkText: String,
        chunkIndex: Int,
        totalChunks: Int,
        startingSceneIndex: Int,
        visualStyle: VisualStyle
    ): List<StoryScene> {
        val prompt = """
You are an acclaimed Telugu film director and storyboard supervisor.
Convert the following Telugu script segment (Part ${chunkIndex + 1} of $totalChunks) into a sequence of engaging cinematic video scenes.

Requirements:
1. Preserve ALL meaningful narration and character dialogues in natural, grammatically accurate Telugu Unicode script (తెలుగు). Do not summarize away key lines.
2. Group lines into natural visual beats (roughly 35 to 90 Telugu words per scene clip, producing 2 to 8 scenes for this segment depending on length).
3. For each scene, split `dialogueTelugu` into `captionLines`: sequential, easy-to-read Telugu subtitle lines (4 to 9 words each) strictly synchronized with the spoken dialogue.
4. Write `visualPromptEn` in vivid cinematic English matching the visual style: "${visualStyle.displayName} — ${visualStyle.promptDirective}". Include South Indian / Telugu cultural authenticity, costumes, lighting, and camera framing. Never ask for text or letters inside the image.
5. Choose varied `cameraMotion` values across consecutive scenes so the final long video feels dynamic.

TELUGU SCRIPT SEGMENT:
$chunkText
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                storyboardModel.generateContent(prompt)
            }
            val jsonText = response.text
                ?: throw IllegalStateException("Gemini returned an empty storyboard response.")

            val root = JSONObject(jsonText)
            val scenesArray = root.getJSONArray("scenes")
            val parsed = mutableListOf<StoryScene>()

            for (i in 0 until scenesArray.length()) {
                val obj = scenesArray.getJSONObject(i)
                val dialogue = obj.optString("dialogueTelugu").trim()
                if (dialogue.isEmpty()) continue

                val captionJson = obj.optJSONArray("captionLines")
                val captionLines = mutableListOf<String>()
                if (captionJson != null) {
                    for (c in 0 until captionJson.length()) {
                        val line = captionJson.optString(c).trim()
                        if (line.isNotEmpty()) captionLines.add(line)
                    }
                }
                val finalCaptions = if (captionLines.isEmpty()) {
                    TeluguScriptChunker.splitDialogueIntoCaptionLines(dialogue)
                } else {
                    captionLines
                }

                val sceneNum = (startingSceneIndex + parsed.size).toLong()
                parsed.add(
                    StoryScene(
                        id = "scene_${sceneNum.toString().padStart(3, '0')}",
                        userId = userId,
                        projectId = projectId,
                        sceneIndex = sceneNum,
                        titleTelugu = obj.optString("titleTelugu").trim()
                            .ifEmpty { "దృశ్యం ${sceneNum + 1}" },
                        speaker = obj.optString("speaker").trim().ifEmpty { "నరేటర్" },
                        dialogueTelugu = dialogue,
                        captionLines = finalCaptions,
                        visualPrompt = obj.optString("visualPromptEn").trim()
                            .ifEmpty { "Cinematic South Indian film still, golden hour light" },
                        cameraMotion = CameraMotion.fromRaw(obj.optString("cameraMotion")).name,
                        mood = SceneMood.fromRaw(obj.optString("mood")).name,
                        voiceDirection = obj.optString("voiceDirection").trim()
                            .ifEmpty { "Warm expressive Telugu cinematic narration" },
                        durationMs = TeluguScriptChunker.estimateSceneDurationMs(dialogue)
                    )
                )
            }

            if (parsed.isEmpty()) {
                throw IllegalStateException("No scenes could be extracted from script chunk ${chunkIndex + 1}.")
            }
            return parsed
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Storyboard analysis timed out on chunk ${chunkIndex + 1}", e)
            throw IllegalStateException("Script analysis timed out after 60 seconds on part ${chunkIndex + 1}. Please retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Storyboard analysis failed on chunk ${chunkIndex + 1}", e)
            throw e
        }
    }

    /**
     * Generates a 16:9 AI cinema clip visual frame using gemini-nano-banana-2.1.
     */
    suspend fun generateSceneVisual(
        scene: StoryScene,
        visualStyle: VisualStyle
    ): Bitmap {
        val fullPrompt = buildString {
            append(visualStyle.promptDirective)
            append(". Scene atmosphere: ")
            append(scene.moodEnum.label)
            append(". ")
            append(scene.visualPrompt)
            append(". Widescreen 16:9 cinema composition, richly detailed, absolutely no text, subtitles, or watermarks in the artwork.")
        }

        try {
            val response = withTimeout(60_000L) {
                imageModel.generateContent(fullPrompt)
            }
            val imagePart = response.candidates.firstOrNull()
                ?.content?.parts
                ?.filterIsInstance<ImagePart>()
                ?.firstOrNull()
            return imagePart?.image
                ?: throw IllegalStateException("No image frame was returned by gemini-nano-banana-2.1.")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Visual generation timed out for scene ${scene.sceneIndex}", e)
            throw IllegalStateException("AI clip visual generation timed out after 60 seconds.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Visual generation failed for scene ${scene.sceneIndex}", e)
            throw e
        }
    }

    /**
     * Synthesizes human-like Telugu narration audio using gemini-3.1-flash-tts-preview.
     */
    suspend fun generateSceneNarration(
        scene: StoryScene,
        voiceProfile: VoiceProfile
    ): SynthesizedAudio {
        val ttsPrompt = buildString {
            append("Read the following Telugu dialogue aloud with authentic Telugu pronunciation, natural human breathing, and expressive cinematic emotion. ")
            append("Voice persona: ${voiceProfile.toneDescription}. ")
            if (scene.voiceDirection.isNotBlank()) {
                append("Delivery direction: ${scene.voiceDirection}. ")
            }
            if (scene.speaker.isNotBlank()) {
                append("Speaker: ${scene.speaker}. ")
            }
            append("Speak ONLY the Telugu text below without adding extra commentary:\n\n")
            append(scene.dialogueTelugu)
        }

        try {
            val ttsModel = buildTtsModel(voiceProfile)
            val response = withTimeout(60_000L) {
                ttsModel.generateContent(ttsPrompt)
            }
            val audioPart = response.candidates.firstOrNull()
                ?.content?.parts
                ?.filterIsInstance<InlineDataPart>()
                ?.firstOrNull { it.mimeType.startsWith("audio/") }
                ?: throw IllegalStateException("No narration audio was returned by gemini-3.1-flash-tts-preview.")

            if (audioPart.inlineData.isEmpty()) {
                throw IllegalStateException("Synthesized narration audio stream was empty.")
            }
            return SynthesizedAudio(
                bytes = audioPart.inlineData,
                mimeType = audioPart.mimeType
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Telugu TTS timed out for scene ${scene.sceneIndex}", e)
            throw IllegalStateException("Narration synthesis timed out after 60 seconds.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Telugu narration synthesis failed for scene ${scene.sceneIndex}", e)
            throw e
        }
    }
}
