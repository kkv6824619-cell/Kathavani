package com.example.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.ai.TeluguScriptChunker
import com.example.model.CameraMotion
import com.example.model.PipelineStatus
import com.example.model.SceneMediaState
import com.example.model.SceneMood
import com.example.model.StoryScene
import com.example.model.VideoProject
import com.example.ui.theme.CelestialTeal
import com.example.ui.theme.ElevatedVelvetCard
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.PlumCharcoalCard
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SubtlePlumOutline
import com.example.ui.theme.TeluguFontFamily
import com.example.ui.theme.VermilionCoral
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionStudioScreen(
    project: VideoProject?,
    scenes: List<StoryScene>,
    sceneMediaMap: Map<String, SceneMediaState>,
    pipelineStatus: PipelineStatus,
    onBack: () -> Unit,
    onEditScript: () -> Unit,
    onReanalyzeScript: () -> Unit,
    onGenerateAllPending: () -> Unit,
    onCancelPipeline: () -> Unit,
    onDismissPipelineError: () -> Unit,
    onGenerateVisual: (StoryScene) -> Unit,
    onGenerateNarration: (StoryScene) -> Unit,
    onUpdateScene: (
        scene: StoryScene,
        titleTelugu: String,
        speaker: String,
        dialogueTelugu: String,
        captionLines: List<String>,
        visualPrompt: String,
        cameraMotion: CameraMotion,
        mood: SceneMood,
        voiceDirection: String
    ) -> Unit,
    onOpenCinemaPlayer: () -> Unit
) {
    var editingScene by remember { mutableStateOf<StoryScene?>(null) }
    var playingSceneId by remember { mutableStateOf<String?>(null) }
    var activeMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopAudioPreview() {
        try {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
        } catch (_: Exception) {
        }
        activeMediaPlayer = null
        playingSceneId = null
    }

    fun toggleAudioPreview(sceneId: String, audioPath: String) {
        if (playingSceneId == sceneId) {
            stopAudioPreview()
            return
        }
        stopAudioPreview()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(audioPath)
                setOnCompletionListener {
                    stopAudioPreview()
                }
                prepare()
                start()
            }
            activeMediaPlayer = mp
            playingSceneId = sceneId
        } catch (_: Exception) {
            stopAudioPreview()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopAudioPreview() }
    }

    val visualsDone = scenes.count { sceneMediaMap[it.id]?.imagePath != null }
    val audioDone = scenes.count { sceneMediaMap[it.id]?.audioPath != null }
    val totalDurationSec = remember(scenes, sceneMediaMap) {
        (scenes.sumOf { sceneMediaMap[it.id]?.audioDurationMs ?: it.durationMs } / 1000L)
    }

    Scaffold(
        containerColor = ObsidianCanvas,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianCanvas,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            stopAudioPreview()
                            onBack()
                        },
                        modifier = Modifier.testTag("studio_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = project?.title ?: "Production Studio",
                            fontFamily = TeluguFontFamily,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${scenes.size} clips • ${TeluguScriptChunker.formatDuration(totalDurationSec)} • ${project?.voiceProfileEnum?.geminiVoiceName ?: "Kore"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onEditScript,
                        modifier = Modifier.testTag("edit_script_action")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit Telugu script",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 780.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Live Pipeline Progress or Error Banner
                if (pipelineStatus.active || !pipelineStatus.errorMessage.isNullOrBlank()) {
                    item(key = "pipeline_banner") {
                        PipelineBannerCard(
                            status = pipelineStatus,
                            onCancel = onCancelPipeline,
                            onDismissError = onDismissPipelineError
                        )
                    }
                }

                // Master Production Console Card
                item(key = "production_console") {
                    ProductionConsoleCard(
                        sceneCount = scenes.size,
                        visualsDone = visualsDone,
                        audioDone = audioDone,
                        totalDurationSec = totalDurationSec,
                        isBusy = pipelineStatus.active,
                        onGenerateAll = onGenerateAllPending,
                        onOpenPlayer = {
                            stopAudioPreview()
                            onOpenCinemaPlayer()
                        },
                        onReanalyze = onReanalyzeScript
                    )
                }

                if (scenes.isEmpty() && !pipelineStatus.active) {
                    item(key = "no_scenes") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(PlumCharcoalCard)
                                .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Theaters,
                                contentDescription = null,
                                tint = SaffronGold,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Storyboard Not Built Yet",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Run the AI Storyboard Director to split your Telugu script into visual clips, dialogue captions, and narration cues.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onReanalyzeScript,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SaffronGold,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("build_storyboard_retry_button")
                            ) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Analyze Telugu Script Now", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                items(scenes, key = { it.id }) { scene ->
                    val media = sceneMediaMap[scene.id] ?: SceneMediaState()
                    SceneClipCard(
                        scene = scene,
                        media = media,
                        isPlayingAudio = playingSceneId == scene.id,
                        onGenerateVisual = { onGenerateVisual(scene) },
                        onGenerateNarration = { onGenerateNarration(scene) },
                        onTogglePlayAudio = { path -> toggleAudioPreview(scene.id, path) },
                        onEditScene = { editingScene = scene }
                    )
                }
            }
        }
    }

    editingScene?.let { sceneToEdit ->
        SceneEditBottomSheet(
            scene = sceneToEdit,
            onDismiss = { editingScene = null },
            onSave = { titleT, speaker, dialogue, captions, visualP, motion, mood, voiceDir ->
                onUpdateScene(
                    sceneToEdit,
                    titleT,
                    speaker,
                    dialogue,
                    captions,
                    visualP,
                    motion,
                    mood,
                    voiceDir
                )
                editingScene = null
            }
        )
    }
}

@Composable
private fun PipelineBannerCard(
    status: PipelineStatus,
    onCancel: () -> Unit,
    onDismissError: () -> Unit
) {
    if (status.active) {
        val fraction = if (status.totalSteps > 0) {
            (status.currentStep.toFloat() / status.totalSteps.toFloat()).coerceIn(0.05f, 1f)
        } else 0f

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            SaffronGold.copy(alpha = 0.22f),
                            PlumCharcoalCard
                        )
                    )
                )
                .border(1.dp, SaffronGold.copy(alpha = 0.5f), MaterialTheme.shapes.large)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    CircularProgressIndicator(
                        color = SaffronGold,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = status.phaseTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = SaffronGold
                        )
                        Text(
                            text = status.detailMessage,
                            fontFamily = TeluguFontFamily,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel_pipeline_button")
                ) {
                    Text(stringResource(R.string.action_cancel), color = VermilionCoral)
                }
            }
            if (status.totalSteps > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = SaffronGold,
                    trackColor = ElevatedVelvetCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                )
            } else {
                LinearProgressIndicator(
                    color = SaffronGold,
                    trackColor = ElevatedVelvetCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                )
            }
        }
    } else if (!status.errorMessage.isNullOrBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = status.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismissError) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ProductionConsoleCard(
    sceneCount: Int,
    visualsDone: Int,
    audioDone: Int,
    totalDurationSec: Long,
    isBusy: Boolean,
    onGenerateAll: () -> Unit,
    onOpenPlayer: () -> Unit,
    onReanalyze: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(PlumCharcoalCard)
            .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AI Production Desk",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Visual Clips $visualsDone/$sceneCount • Telugu Voice $audioDone/$sceneCount • ${TeluguScriptChunker.formatDuration(totalDurationSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onReanalyze,
                enabled = !isBusy,
                modifier = Modifier.testTag("reanalyze_script_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Re-analyze Telugu script",
                    tint = SaffronGold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onGenerateAll,
                enabled = sceneCount > 0 && !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaffronGold,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .weight(1.25f)
                    .height(50.dp)
                    .testTag("generate_all_clips_button")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_generate_all),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onOpenPlayer,
                enabled = sceneCount > 0,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("open_cinema_player_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = CelestialTeal,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.action_open_player),
                    color = CelestialTeal,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SceneClipCard(
    scene: StoryScene,
    media: SceneMediaState,
    isPlayingAudio: Boolean,
    onGenerateVisual: () -> Unit,
    onGenerateNarration: () -> Unit,
    onTogglePlayAudio: (String) -> Unit,
    onEditScene: () -> Unit
) {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(PlumCharcoalCard)
            .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
            .testTag("scene_card_${scene.sceneIndex}")
    ) {
        // 16:9 AI Visual Frame Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(ElevatedVelvetCard),
            contentAlignment = Alignment.Center
        ) {
            when {
                media.imageLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SaffronGold.copy(alpha = shimmerAlpha * 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = SaffronGold, strokeWidth = 3.dp)
                            Text(
                                text = "Generating 16:9 AI clip frame (30–60s)…",
                                style = MaterialTheme.typography.labelMedium,
                                color = SaffronGold
                            )
                        }
                    }
                }

                !media.imagePath.isNullOrBlank() -> {
                    AsyncImage(
                        model = File(media.imagePath),
                        contentDescription = stringResource(R.string.cd_scene_visual),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        ObsidianCanvas.copy(alpha = 0.55f),
                                        ObsidianCanvas.copy(alpha = 0.0f),
                                        ObsidianCanvas.copy(alpha = 0.72f)
                                    )
                                )
                            )
                    )
                }

                !media.imageError.isNullOrBlank() -> {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = media.imageError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        OutlinedButton(onClick = onGenerateVisual) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clickable(onClick = onGenerateVisual)
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            tint = SaffronGold.copy(alpha = 0.75f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Tap to generate AI 16:9 clip visual",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Top-left Scene Badge
            Surface(
                color = ObsidianCanvas.copy(alpha = 0.78f),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = "CLIP ${scene.sceneIndex + 1} • ${scene.cameraMotionEnum.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SaffronGold,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Bottom-left Synced Caption Preview
            scene.captionLines.firstOrNull()?.let { firstCaption ->
                Surface(
                    color = ObsidianCanvas.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = firstCaption,
                        fontFamily = TeluguFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = SaffronGold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Scene Metadata & Dialogue Details
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scene.titleTelugu,
                        fontFamily = TeluguFontFamily,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = scene.speaker.ifBlank { "నరేటర్" },
                            fontFamily = TeluguFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = VermilionCoral,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• ${scene.moodEnum.label} • ${TeluguScriptChunker.formatDuration((media.audioDurationMs ?: scene.durationMs) / 1000L)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onEditScene,
                    modifier = Modifier.testTag("edit_scene_${scene.sceneIndex}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit scene ${scene.sceneIndex + 1}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = scene.dialogueTelugu,
                fontFamily = TeluguFontFamily,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 23.sp
            )

            // Synced Caption Lines Strip
            if (scene.captionLines.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ClosedCaption,
                        contentDescription = null,
                        tint = CelestialTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${scene.captionLines.size} synced Telugu captions",
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestialTeal
                    )
                }
            }

            // Transparent Narration Audio Error (never hidden)
            AnimatedVisibility(visible = !media.audioError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Narration error: ${media.audioError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Per-Scene Action Bar (Visual AI, Voice AI, Play Voice Preview)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onGenerateVisual,
                    enabled = !media.imageLoading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("gen_visual_${scene.sceneIndex}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = SaffronGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (media.imagePath != null) "Redo Clip" else "Gen Clip",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                OutlinedButton(
                    onClick = onGenerateNarration,
                    enabled = !media.audioLoading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("gen_voice_${scene.sceneIndex}")
                ) {
                    if (media.audioLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = CelestialTeal,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            tint = CelestialTeal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = when {
                            media.audioLoading -> "Voicing…"
                            media.audioPath != null -> "Redo Voice"
                            else -> "Gen Voice"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (!media.audioPath.isNullOrBlank()) {
                    IconButton(
                        onClick = { onTogglePlayAudio(media.audioPath) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPlayingAudio) VermilionCoral.copy(alpha = 0.22f)
                                else CelestialTeal.copy(alpha = 0.2f)
                            )
                            .testTag("play_voice_${scene.sceneIndex}")
                    ) {
                        Icon(
                            imageVector = if (isPlayingAudio) Icons.Filled.Stop else Icons.Filled.GraphicEq,
                            contentDescription = if (isPlayingAudio) "Stop voice preview" else "Play Telugu narration",
                            tint = if (isPlayingAudio) VermilionCoral else CelestialTeal
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SceneEditBottomSheet(
    scene: StoryScene,
    onDismiss: () -> Unit,
    onSave: (
        titleTelugu: String,
        speaker: String,
        dialogueTelugu: String,
        captionLines: List<String>,
        visualPrompt: String,
        cameraMotion: CameraMotion,
        mood: SceneMood,
        voiceDirection: String
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var titleTelugu by remember(scene.id) { mutableStateOf(scene.titleTelugu) }
    var speaker by remember(scene.id) { mutableStateOf(scene.speaker) }
    var dialogueTelugu by remember(scene.id) { mutableStateOf(scene.dialogueTelugu) }
    var captionsText by remember(scene.id) { mutableStateOf(scene.captionLines.joinToString("\n")) }
    var visualPrompt by remember(scene.id) { mutableStateOf(scene.visualPrompt) }
    var cameraMotion by remember(scene.id) { mutableStateOf(scene.cameraMotionEnum) }
    var mood by remember(scene.id) { mutableStateOf(scene.moodEnum) }
    var voiceDirection by remember(scene.id) { mutableStateOf(scene.voiceDirection) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ElevatedVelvetCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Edit Clip ${scene.sceneIndex + 1}",
                style = MaterialTheme.typography.titleLarge,
                color = SaffronGold
            )

            OutlinedTextField(
                value = titleTelugu,
                onValueChange = { titleTelugu = it },
                label = { Text("Scene Title (తెలుగు)") },
                textStyle = TextStyle(fontFamily = TeluguFontFamily, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = speaker,
                onValueChange = { speaker = it },
                label = { Text("Speaker / Narrator") },
                textStyle = TextStyle(fontFamily = TeluguFontFamily, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = dialogueTelugu,
                onValueChange = { dialogueTelugu = it },
                label = { Text("Telugu Narration / Dialogue") },
                textStyle = TextStyle(fontFamily = TeluguFontFamily, fontSize = 15.sp, lineHeight = 22.sp),
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = captionsText,
                onValueChange = { captionsText = it },
                label = { Text("Synced Captions (one cue per line)") },
                textStyle = TextStyle(fontFamily = TeluguFontFamily, fontSize = 14.sp, lineHeight = 20.sp),
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = visualPrompt,
                onValueChange = { visualPrompt = it },
                label = { Text("AI Visual Prompt (English)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = voiceDirection,
                onValueChange = { voiceDirection = it },
                label = { Text("Voice Emotion Direction") },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Camera Motion",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraMotion.entries.forEach { m ->
                    FilterChip(
                        selected = m == cameraMotion,
                        onClick = { cameraMotion = m },
                        label = { Text(m.label) }
                    )
                }
            }

            Text(
                text = "Scene Mood",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SceneMood.entries.forEach { md ->
                    FilterChip(
                        selected = md == mood,
                        onClick = { mood = md },
                        label = { Text(md.label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val lines = captionsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        onSave(
                            titleTelugu,
                            speaker,
                            dialogueTelugu,
                            lines.ifEmpty { TeluguScriptChunker.splitDialogueIntoCaptionLines(dialogueTelugu) },
                            visualPrompt,
                            cameraMotion,
                            mood,
                            voiceDirection
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaffronGold,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_scene_edits_button")
                ) {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
