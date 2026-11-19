package com.example.ui.screens

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.ai.TeluguScriptChunker
import com.example.model.CameraMotion
import com.example.model.CaptionStyle
import com.example.model.ExportVideoState
import com.example.model.SceneMediaState
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
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaPlayerScreen(
    project: VideoProject?,
    scenes: List<StoryScene>,
    sceneMediaMap: Map<String, SceneMediaState>,
    exportState: ExportVideoState,
    onBack: () -> Unit,
    onExportLongVideo: () -> Unit,
    onCancelExport: () -> Unit
) {
    val context = LocalContext.current

    var currentSceneIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var sceneElapsedMs by remember { mutableLongStateOf(0L) }
    var showCaptions by remember { mutableStateOf(true) }
    var activeCaptionStyle by remember(project?.captionStyle) {
        mutableStateOf(project?.captionStyleEnum ?: CaptionStyle.CINEMA_GOLD)
    }

    val safeIndex = currentSceneIndex.coerceIn(0, (scenes.size - 1).coerceAtLeast(0))
    val currentScene = scenes.getOrNull(safeIndex)
    val currentMedia = currentScene?.let { sceneMediaMap[it.id] }
    val currentSceneDurationMs = (currentMedia?.audioDurationMs ?: currentScene?.durationMs ?: 5_000L)
        .coerceAtLeast(2_000L)

    val sceneDurations = remember(scenes, sceneMediaMap) {
        scenes.map { (sceneMediaMap[it.id]?.audioDurationMs ?: it.durationMs).coerceAtLeast(2_000L) }
    }
    val totalVideoMs = remember(sceneDurations) { sceneDurations.sum().coerceAtLeast(1_000L) }
    val elapsedBeforeSceneMs = remember(safeIndex, sceneDurations) {
        sceneDurations.take(safeIndex).sum()
    }
    val globalPositionMs = (elapsedBeforeSceneMs + sceneElapsedMs).coerceIn(0L, totalVideoMs)

    val currentCues = remember(currentScene?.id, currentSceneDurationMs) {
        if (currentScene == null) emptyList()
        else TeluguScriptChunker.buildSyncedCaptionCues(
            captionLines = currentScene.captionLines,
            fallbackDialogue = currentScene.dialogueTelugu,
            totalDurationMs = currentSceneDurationMs
        )
    }
    val activeCue = currentCues.firstOrNull { sceneElapsedMs in it.startMs..it.endMs }
        ?: currentCues.lastOrNull()

    // Managed MediaPlayer synchronized to active scene narration
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun releasePlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    // Start/sync narration audio whenever playing state or scene changes
    LaunchedEffect(isPlaying, safeIndex, currentMedia?.audioPath) {
        releasePlayer()
        if (!isPlaying || currentScene == null) return@LaunchedEffect

        val audioPath = currentMedia?.audioPath
        if (!audioPath.isNullOrBlank() && File(audioPath).exists()) {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(audioPath)
                    prepare()
                    if (sceneElapsedMs in 100L until duration.toLong()) {
                        seekTo(sceneElapsedMs.toInt())
                    }
                    start()
                }
                mediaPlayer = mp
            } catch (_: Exception) {
                releasePlayer()
            }
        }

        // High-precision 30fps playback & Ken Burns animation loop
        val tickMs = 33L
        while (isPlaying) {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                sceneElapsedMs = mp.currentPosition.toLong().coerceAtMost(currentSceneDurationMs)
            } else {
                sceneElapsedMs += tickMs
            }

            if (sceneElapsedMs >= currentSceneDurationMs) {
                if (safeIndex < scenes.lastIndex) {
                    currentSceneIndex = safeIndex + 1
                    sceneElapsedMs = 0L
                } else {
                    isPlaying = false
                    sceneElapsedMs = 0L
                    currentSceneIndex = 0
                }
                break
            }
            delay(tickMs)
        }
    }

    val motionProgress = (sceneElapsedMs.toFloat() / currentSceneDurationMs.toFloat()).coerceIn(0f, 1f)

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
                            isPlaying = false
                            releasePlayer()
                            onBack()
                        },
                        modifier = Modifier.testTag("player_back_button")
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
                            text = project?.title ?: "Cinema Preview",
                            fontFamily = TeluguFontFamily,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Clip ${safeIndex + 1}/${scenes.size} • ${TeluguScriptChunker.formatDuration(totalVideoMs / 1000L)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCaptions = !showCaptions },
                        modifier = Modifier.testTag("toggle_captions_button")
                    ) {
                        Icon(
                            imageVector = if (showCaptions) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                            contentDescription = stringResource(R.string.cd_toggle_captions),
                            tint = if (showCaptions) SaffronGold else MaterialTheme.colorScheme.onSurfaceVariant
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 780.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 16:9 Live Synchronized Cinema Viewport
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.large)
                        .background(Color(0xFF0A0712))
                        .border(1.dp, SaffronGold.copy(alpha = 0.4f), MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentScene != null) {
                        val motion = currentScene.cameraMotionEnum
                        val scale = when (motion) {
                            CameraMotion.ZOOM_IN -> 1.0f + 0.12f * motionProgress
                            CameraMotion.ZOOM_OUT -> 1.12f - 0.12f * motionProgress
                            else -> 1.12f
                        }
                        val txFraction = when (motion) {
                            CameraMotion.PAN_LEFT -> 0.04f - 0.08f * motionProgress
                            CameraMotion.PAN_RIGHT -> -0.04f + 0.08f * motionProgress
                            else -> 0f
                        }
                        val tyFraction = when (motion) {
                            CameraMotion.DRIFT_UP -> 0.035f - 0.07f * motionProgress
                            else -> 0f
                        }

                        if (!currentMedia?.imagePath.isNullOrBlank()) {
                            AsyncImage(
                                model = File(currentMedia!!.imagePath!!),
                                contentDescription = stringResource(R.string.cd_scene_visual),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = size.width * txFraction
                                        translationY = size.height * tyFraction
                                    }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(PlumCharcoalCard, ObsidianCanvas)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Visual clip not generated yet for Scene ${safeIndex + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Cinema gradient scrim
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            ObsidianCanvas.copy(alpha = 0.45f),
                                            Color.Transparent,
                                            ObsidianCanvas.copy(alpha = 0.85f)
                                        )
                                    )
                                )
                        )

                        // Top-left Scene Title HUD
                        Text(
                            text = "SCENE ${currentScene.sceneIndex + 1} • ${currentScene.titleTelugu}",
                            fontFamily = TeluguFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        )

                        // Synchronized Telugu Caption Overlay
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showCaptions && activeCue != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (currentScene.speaker.isNotBlank()) {
                                    Surface(
                                        color = SaffronGold,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = currentScene.speaker,
                                            fontFamily = TeluguFontFamily,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val captionBg = when (activeCaptionStyle) {
                                    CaptionStyle.CINEMA_GOLD -> ObsidianCanvas.copy(alpha = 0.82f)
                                    CaptionStyle.CLEAN_SUBTITLE -> Color.Black.copy(alpha = 0.78f)
                                    CaptionStyle.BOLD_KARAOKE -> Color(0xCC2A1842)
                                }
                                val captionColor = when (activeCaptionStyle) {
                                    CaptionStyle.CINEMA_GOLD -> Color(0xFFFFD275)
                                    CaptionStyle.CLEAN_SUBTITLE -> Color.White
                                    CaptionStyle.BOLD_KARAOKE -> Color(0xFFFAF6F0)
                                }
                                Surface(
                                    color = captionBg,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.border(
                                        width = if (activeCaptionStyle != CaptionStyle.CLEAN_SUBTITLE) 1.dp else 0.dp,
                                        color = SaffronGold.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ) {
                                    Text(
                                        text = activeCue?.text.orEmpty(),
                                        fontFamily = TeluguFontFamily,
                                        fontSize = if (activeCaptionStyle == CaptionStyle.BOLD_KARAOKE) 16.sp else 14.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = captionColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Master Video Timeline Scrubber & Transport Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(PlumCharcoalCard)
                        .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Slider(
                        value = globalPositionMs.toFloat(),
                        onValueChange = { targetMs ->
                            var remaining = targetMs.toLong()
                            var targetIdx = 0
                            for (i in sceneDurations.indices) {
                                if (remaining <= sceneDurations[i] || i == sceneDurations.lastIndex) {
                                    targetIdx = i
                                    break
                                }
                                remaining -= sceneDurations[i]
                            }
                            currentSceneIndex = targetIdx
                            sceneElapsedMs = remaining.coerceAtLeast(0L)
                        },
                        valueRange = 0f..totalVideoMs.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = SaffronGold,
                            activeTrackColor = SaffronGold,
                            inactiveTrackColor = ElevatedVelvetCard
                        ),
                        modifier = Modifier.testTag("cinema_timeline_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = TeluguScriptChunker.formatDuration(globalPositionMs / 1000L),
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold
                        )
                        Text(
                            text = TeluguScriptChunker.formatDuration(totalVideoMs / 1000L),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (safeIndex > 0) {
                                    currentSceneIndex = safeIndex - 1
                                    sceneElapsedMs = 0L
                                }
                            },
                            enabled = safeIndex > 0,
                            modifier = Modifier.size(48.dp).testTag("player_prev_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_prev_scene),
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(18.dp))

                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(SaffronGold)
                                .clickable { isPlaying = !isPlaying }
                                .testTag("player_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(18.dp))

                        IconButton(
                            onClick = {
                                if (safeIndex < scenes.lastIndex) {
                                    currentSceneIndex = safeIndex + 1
                                    sceneElapsedMs = 0L
                                }
                            },
                            enabled = safeIndex < scenes.lastIndex,
                            modifier = Modifier.size(48.dp).testTag("player_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.cd_next_scene),
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                // Scene Chapters Strip
                if (scenes.isNotEmpty()) {
                    Text(
                        text = "Storyboard Timeline (${scenes.size} Clips)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 8.dp)
                    ) {
                        itemsIndexed(scenes, key = { _, s -> s.id }) { idx, scene ->
                            val selected = idx == safeIndex
                            val media = sceneMediaMap[scene.id]
                            Column(
                                modifier = Modifier
                                    .width(142.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (selected) SaffronGold.copy(alpha = 0.16f) else PlumCharcoalCard)
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) SaffronGold else SubtlePlumOutline,
                                        shape = MaterialTheme.shapes.medium
                                    )
                                    .clickable {
                                        currentSceneIndex = idx
                                        sceneElapsedMs = 0L
                                    }
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ElevatedVelvetCard),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!media?.imagePath.isNullOrBlank()) {
                                        AsyncImage(
                                            model = File(media!!.imagePath!!),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = "#${idx + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SaffronGold
                                        )
                                    }
                                }
                                Text(
                                    text = scene.titleTelugu,
                                    fontFamily = TeluguFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) SaffronGold else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // HD 720p MP4 Long-Video Compiler & Export Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(PlumCharcoalCard)
                        .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MovieCreation,
                            contentDescription = null,
                            tint = SaffronGold
                        )
                        Column {
                            Text(
                                text = "Compile & Export Long HD Video (.MP4)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Burns animated Ken Burns clips, synced Telugu captions & narration into 720p H.264 MP4.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (exportState.exporting) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = exportState.statusText,
                                    fontFamily = TeluguFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SaffronGold,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onCancelExport) {
                                    Text(stringResource(R.string.action_cancel), color = VermilionCoral)
                                }
                            }
                            LinearProgressIndicator(
                                progress = { exportState.progressFraction.coerceIn(0.02f, 1f) },
                                color = SaffronGold,
                                trackColor = ElevatedVelvetCard,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                        }
                    } else {
                        AnimatedVisibility(visible = !exportState.errorMessage.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = exportState.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        if (!exportState.exportedFilePath.isNullOrBlank() && File(exportState.exportedFilePath).exists()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = CelestialTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "MP4 ready (${File(exportState.exportedFilePath).length() / 1024} KB) • Saved to Movies/KathaVani",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CelestialTeal
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    isPlaying = false
                                    releasePlayer()
                                    onExportLongVideo()
                                },
                                enabled = scenes.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SaffronGold,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("export_mp4_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.action_export_mp4),
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (!exportState.exportedFilePath.isNullOrBlank() && File(exportState.exportedFilePath).exists()) {
                                OutlinedButton(
                                    onClick = {
                                        val file = File(exportState.exportedFilePath)
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Share Telugu AI Video")
                                        )
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier
                                        .height(50.dp)
                                        .testTag("share_mp4_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = null,
                                        tint = CelestialTeal
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.action_share_video),
                                        color = CelestialTeal
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
