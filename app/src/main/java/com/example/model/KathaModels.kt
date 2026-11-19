package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class VisualStyle(
    val displayName: String,
    val teluguLabel: String,
    val promptDirective: String
) {
    CINEMATIC_TOLLYWOOD(
        displayName = "Cinematic Cinema",
        teluguLabel = "సినీ దృశ్యం",
        promptDirective = "Ultra-cinematic South Indian feature film still, 35mm anamorphic lens, dramatic golden volumetric lighting, lush color grading, photorealistic, high depth of field, no text or watermarks"
    ),
    MYTHOLOGICAL_EPIC(
        displayName = "Mythological Epic",
        teluguLabel = "పౌరాణిక గాథ",
        promptDirective = "Grand Indian mythological epic concept art, ornate temple architecture, radiant divine rays, regal silk and gold accents, dramatic clouds, painterly realism, no text or watermarks"
    ),
    WATERCOLOR_STORYBOOK(
        displayName = "Illustrated Tale",
        teluguLabel = "కథా చిత్రం",
        promptDirective = "Lush warm hand-painted gouache and watercolor storybook illustration, soft golden hour glow, expressive characters, rich South Indian village and nature details, no text or watermarks"
    ),
    NOIR_DRAMA(
        displayName = "Noir Thriller",
        teluguLabel = "థ్రిల్లర్",
        promptDirective = "Moody neo-noir cinema frame, rain-slicked twilight streets, warm amber streetlamp reflections against deep indigo shadows, atmospheric fog, 4K film grain, no text or watermarks"
    ),
    MODERN_ANIME(
        displayName = "Stylized Anime",
        teluguLabel = "యానిమేషన్",
        promptDirective = "High-budget theatrical anime film key visual, vibrant sky, expressive cinematic lighting, sweeping perspective, finely detailed environments, no text or watermarks"
    );

    companion object {
        fun fromRaw(raw: String): VisualStyle =
            entries.firstOrNull { it.name == raw } ?: CINEMATIC_TOLLYWOOD
    }
}

enum class VoiceProfile(
    val displayName: String,
    val teluguLabel: String,
    val geminiVoiceName: String,
    val toneDescription: String
) {
    KORE_WARM(
        displayName = "Kore • Warm Narrator",
        teluguLabel = "కోరె • ఆత్మీయ కథనం",
        geminiVoiceName = "Kore",
        toneDescription = "Warm, expressive, natural Telugu storyteller voice with clear emotional cadence"
    ),
    PUCK_DYNAMIC(
        displayName = "Puck • Energetic",
        teluguLabel = "పక్ • ఉత్సాహభరిత స్వరం",
        geminiVoiceName = "Puck",
        toneDescription = "Lively, engaging, youthful Telugu dialogue delivery with vibrant pacing"
    ),
    CHARON_DEEP(
        displayName = "Charon • Deep Cinema",
        teluguLabel = "చెరాన్ • గంభీర స్వరం",
        geminiVoiceName = "Charon",
        toneDescription = "Deep, resonant cinematic baritone ideal for epic and dramatic Telugu narration"
    ),
    AOEDE_LYRICAL(
        displayName = "Aoede • Lyrical",
        teluguLabel = "అయోడి • మృదువైన స్వరం",
        geminiVoiceName = "Aoede",
        toneDescription = "Gentle, melodic, emotionally rich Telugu storytelling voice"
    ),
    FENRIR_DRAMATIC(
        displayName = "Fenrir • Intense",
        teluguLabel = "ఫెన్రిర్ • ఉద్వేగ స్వరం",
        geminiVoiceName = "Fenrir",
        toneDescription = "Powerful, intense dramatic Telugu voice for suspenseful and heroic scenes"
    );

    companion object {
        fun fromRaw(raw: String): VoiceProfile =
            entries.firstOrNull { it.name == raw } ?: KORE_WARM
    }
}

enum class CaptionStyle(
    val displayName: String,
    val teluguLabel: String
) {
    CINEMA_GOLD("Cinema Gold", "బంగారు శీర్షిక"),
    CLEAN_SUBTITLE("Clean Subtitle", "స్పష్టమైన ఉపశీర్షిక"),
    BOLD_KARAOKE("Bold Highlight", "ప్రధాన హైలైట్");

    companion object {
        fun fromRaw(raw: String): CaptionStyle =
            entries.firstOrNull { it.name == raw } ?: CINEMA_GOLD
    }
}

enum class ProjectStage(val label: String) {
    SCRIPT("Script Draft"),
    STORYBOARD("Storyboard Ready"),
    PRODUCING("In Production"),
    RENDERED("Video Exported");

    companion object {
        fun fromRaw(raw: String): ProjectStage =
            entries.firstOrNull { it.name == raw } ?: SCRIPT
    }
}

enum class CameraMotion(val label: String) {
    ZOOM_IN("Slow Zoom In"),
    ZOOM_OUT("Pull Back"),
    PAN_LEFT("Pan Left"),
    PAN_RIGHT("Pan Right"),
    DRIFT_UP("Tilt Up");

    companion object {
        fun fromRaw(raw: String): CameraMotion =
            entries.firstOrNull { it.name == raw } ?: ZOOM_IN
    }
}

enum class SceneMood(val label: String) {
    DRAMATIC("Dramatic"),
    SERENE("Serene"),
    EPIC("Epic"),
    EMOTIONAL("Emotional"),
    MYSTERIOUS("Mysterious"),
    JOYFUL("Joyful");

    companion object {
        fun fromRaw(raw: String): SceneMood =
            entries.firstOrNull { it.name == raw } ?: DRAMATIC
    }
}

@Keep
@IgnoreExtraProperties
data class VideoProject(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val scriptPreview: String = "",
    val scriptText: String = "",
    val wordCount: Long = 0L,
    val sceneCount: Long = 0L,
    val completedVisualsCount: Long = 0L,
    val completedAudioCount: Long = 0L,
    val visualStyle: String = VisualStyle.CINEMATIC_TOLLYWOOD.name,
    val voiceProfile: String = VoiceProfile.KORE_WARM.name,
    val captionStyle: String = CaptionStyle.CINEMA_GOLD.name,
    val stage: String = ProjectStage.SCRIPT.name,
    val estimatedDurationSec: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val visualStyleEnum: VisualStyle get() = VisualStyle.fromRaw(visualStyle)
    val voiceProfileEnum: VoiceProfile get() = VoiceProfile.fromRaw(voiceProfile)
    val captionStyleEnum: CaptionStyle get() = CaptionStyle.fromRaw(captionStyle)
    val stageEnum: ProjectStage get() = ProjectStage.fromRaw(stage)
}

@Keep
@IgnoreExtraProperties
data class StoryScene(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val sceneIndex: Long = 0L,
    val titleTelugu: String = "",
    val speaker: String = "",
    val dialogueTelugu: String = "",
    val captionLines: List<String> = emptyList(),
    val visualPrompt: String = "",
    val cameraMotion: String = CameraMotion.ZOOM_IN.name,
    val mood: String = SceneMood.DRAMATIC.name,
    val voiceDirection: String = "",
    val durationMs: Long = 6000L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val cameraMotionEnum: CameraMotion get() = CameraMotion.fromRaw(cameraMotion)
    val moodEnum: SceneMood get() = SceneMood.fromRaw(mood)
}

data class CaptionCue(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class SceneMediaState(
    val imagePath: String? = null,
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val imageLoading: Boolean = false,
    val audioLoading: Boolean = false,
    val imageError: String? = null,
    val audioError: String? = null
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class PipelineStatus(
    val active: Boolean = false,
    val phaseTitle: String = "",
    val detailMessage: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val errorMessage: String? = null
)

data class ExportVideoState(
    val exporting: Boolean = false,
    val progressFraction: Float = 0f,
    val statusText: String = "",
    val exportedFilePath: String? = null,
    val savedToGalleryUri: String? = null,
    val errorMessage: String? = null
)
