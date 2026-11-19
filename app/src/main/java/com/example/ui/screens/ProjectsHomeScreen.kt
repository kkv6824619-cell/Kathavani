package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.TeluguScriptChunker
import com.example.model.ProjectStage
import com.example.model.UiState
import com.example.model.VideoProject
import com.example.ui.theme.CelestialTeal
import com.example.ui.theme.ElevatedVelvetCard
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.PlumCharcoalCard
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SubtlePlumOutline
import com.example.ui.theme.TeluguFontFamily
import com.example.ui.theme.VermilionCoral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsHomeScreen(
    userDisplayName: String,
    projectsState: UiState<List<VideoProject>>,
    onCreateNewProject: () -> Unit,
    onOpenProject: (VideoProject) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var pendingDeleteProject by remember { mutableStateOf<VideoProject?>(null) }

    Scaffold(
        containerColor = ObsidianCanvas,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianCanvas,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Column {
                        Text(
                            text = "KathaVani",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "కథావాణి స్టూడియో • $userDisplayName",
                            fontFamily = TeluguFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier.testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.sign_out),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateNewProject,
                containerColor = SaffronGold,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = {
                    Text(
                        text = stringResource(R.string.action_create_script),
                        fontWeight = FontWeight.Bold
                    )
                },
                modifier = Modifier.testTag("fab_new_script")
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
                    .widthIn(max = 760.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "hero_banner") {
                    StudioHeroBannerCard(onCreateNewProject = onCreateNewProject)
                }

                when (projectsState) {
                    is UiState.Loading -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = SaffronGold)
                            }
                        }
                    }

                    is UiState.Error -> {
                        item(key = "error") {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Could not load studio projects",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = projectsState.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    is UiState.Success -> {
                        val projects = projectsState.data
                        if (projects.isEmpty()) {
                            item(key = "empty_state") {
                                EmptyStudioStateCard(onCreateNewProject = onCreateNewProject)
                            }
                        } else {
                            item(key = "section_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Your Telugu Productions",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "${projects.size} ${if (projects.size == 1) "Project" else "Projects"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SaffronGold
                                    )
                                }
                            }

                            items(projects, key = { it.id }) { project ->
                                ProjectReelCard(
                                    project = project,
                                    onClick = { onOpenProject(project) },
                                    onDeleteClick = { pendingDeleteProject = project }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteProject?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProject = null },
            containerColor = ElevatedVelvetCard,
            title = {
                Text(
                    text = "Delete Production?",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "Remove \"${target.title}\" and all its AI-generated clips and Telugu narration files?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProject(target.id)
                        pendingDeleteProject = null
                    },
                    modifier = Modifier.testTag("confirm_delete_project_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProject = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun StudioHeroBannerCard(onCreateNewProject: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(198.dp)
            .clip(MaterialTheme.shapes.large)
            .border(1.dp, SaffronGold.copy(alpha = 0.35f), MaterialTheme.shapes.large)
            .clickable(onClick = onCreateNewProject)
            .testTag("hero_create_project_card")
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_studio_hero),
            contentDescription = stringResource(R.string.cd_hero_banner),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ObsidianCanvas.copy(alpha = 0.92f),
                            ObsidianCanvas.copy(alpha = 0.62f),
                            Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = SaffronGold.copy(alpha = 0.2f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.border(
                    1.dp,
                    SaffronGold.copy(alpha = 0.5f),
                    RoundedCornerShape(50)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = SaffronGold,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "UP TO 10,000 TELUGU WORDS",
                        style = MaterialTheme.typography.labelSmall,
                        color = SaffronGold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "కథ నుండి సినిమా వరకు",
                    fontFamily = TeluguFontFamily,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "AI scene clips • Dialogue captions • Human-like Telugu narration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyStudioStateCard(onCreateNewProject: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(PlumCharcoalCard)
            .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_reels),
            contentDescription = stringResource(R.string.cd_empty_reels),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(150.dp)
                .clip(MaterialTheme.shapes.medium)
        )
        Text(
            text = stringResource(R.string.empty_projects_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.empty_projects_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onCreateNewProject,
            colors = ButtonDefaults.buttonColors(
                containerColor = SaffronGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("empty_create_script_button")
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_create_script),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProjectReelCard(
    project: VideoProject,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val totalTargets = (project.sceneCount * 2L).coerceAtLeast(1L)
    val doneTargets = (project.completedVisualsCount + project.completedAudioCount).coerceAtMost(totalTargets)
    val progress = if (project.sceneCount > 0L) doneTargets.toFloat() / totalTargets.toFloat() else 0f

    val stageColor = when (project.stageEnum) {
        ProjectStage.RENDERED -> CelestialTeal
        ProjectStage.PRODUCING -> SaffronGold
        ProjectStage.STORYBOARD -> VermilionCoral
        ProjectStage.SCRIPT -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(PlumCharcoalCard)
            .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(18.dp)
            .testTag("project_card_${project.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    color = stageColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = project.stageEnum.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = stageColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = project.title,
                    fontFamily = TeluguFontFamily,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.testTag("delete_project_${project.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (project.scriptPreview.isNotBlank()) {
            Text(
                text = project.scriptPreview,
                fontFamily = TeluguFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricPill(
                icon = Icons.Filled.VideoLibrary,
                text = "${project.wordCount} words"
            )
            MetricPill(
                icon = Icons.Filled.Movie,
                text = "${project.sceneCount} clips"
            )
            MetricPill(
                icon = Icons.Filled.Schedule,
                text = TeluguScriptChunker.formatDuration(project.estimatedDurationSec)
            )
        }

        if (project.sceneCount > 0L) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Visuals ${project.completedVisualsCount}/${project.sceneCount} • Voice ${project.completedAudioCount}/${project.sceneCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = SaffronGold,
                        fontWeight = FontWeight.Bold
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    color = if (progress >= 1f) CelestialTeal else SaffronGold,
                    trackColor = ElevatedVelvetCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = CelestialTeal,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${project.visualStyleEnum.displayName} • ${project.voiceProfileEnum.geminiVoiceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SaffronGold.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = SaffronGold,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedVelvetCard)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SaffronGold,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
