package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ai.TeluguScriptChunker
import com.example.model.CaptionStyle
import com.example.model.PipelineStatus
import com.example.model.VideoProject
import com.example.model.VisualStyle
import com.example.model.VoiceProfile
import com.example.ui.theme.CelestialTeal
import com.example.ui.theme.ElevatedVelvetCard
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.PlumCharcoalCard
import com.example.ui.theme.SaffronGold
import com.example.ui.theme.SubtlePlumOutline
import com.example.ui.theme.TeluguFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    existingProject: VideoProject?,
    pipelineStatus: PipelineStatus,
    onBack: () -> Unit,
    onAnalyzeAndBuild: (
        title: String,
        scriptText: String,
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember(existingProject?.id) {
        mutableStateOf(existingProject?.title ?: "")
    }
    var scriptText by remember(existingProject?.id) {
        mutableStateOf(existingProject?.scriptText ?: "")
    }
    var visualStyle by remember(existingProject?.id) {
        mutableStateOf(existingProject?.visualStyleEnum ?: VisualStyle.CINEMATIC_TOLLYWOOD)
    }
    var voiceProfile by remember(existingProject?.id) {
        mutableStateOf(existingProject?.voiceProfileEnum ?: VoiceProfile.KORE_WARM)
    }
    var captionStyle by remember(existingProject?.id) {
        mutableStateOf(existingProject?.captionStyleEnum ?: CaptionStyle.CINEMA_GOLD)
    }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var isReadingFile by remember { mutableStateOf(false) }

    val wordCount by remember(scriptText) {
        derivedStateOf { TeluguScriptChunker.countWords(scriptText) }
    }
    val estimatedSeconds by remember(wordCount) {
        derivedStateOf { TeluguScriptChunker.estimateDurationSeconds(wordCount) }
    }
    val estimatedChunks by remember(scriptText) {
        derivedStateOf { TeluguScriptChunker.chunkScript(scriptText).size }
    }
    val isOverLimit = wordCount > TeluguScriptChunker.MAX_WORD_COUNT
    val fillFraction = (wordCount.toFloat() / TeluguScriptChunker.MAX_WORD_COUNT.toFloat()).coerceIn(0f, 1f)

    // Zero-permission SAF Document Picker for UTF-8 Telugu .txt / .md scripts
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isReadingFile = true
                uploadError = null
                try {
                    val loadedText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                        }.orEmpty()
                    }
                    if (loadedText.isBlank()) {
                        uploadError = "Selected file appears to be empty."
                    } else {
                        scriptText = loadedText.trim()
                        if (title.isBlank()) {
                            title = loadedText.lineSequence()
                                .firstOrNull { it.isNotBlank() }
                                ?.trim()
                                ?.take(48)
                                ?: "తెలుగు స్క్రిప్ట్"
                        }
                    }
                } catch (e: Exception) {
                    uploadError = e.localizedMessage ?: "Failed to read script file."
                } finally {
                    isReadingFile = false
                }
            }
        }
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
                        onClick = onBack,
                        modifier = Modifier.testTag("editor_back_button")
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
                            text = if (existingProject == null) "New Telugu Script" else "Edit Telugu Script",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Up to 10,000 words • Multi-chunk AI pipeline",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaffronGold
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = PlumCharcoalCard,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            onAnalyzeAndBuild(
                                title.ifBlank { "తెలుగు కథా చిత్రం" },
                                scriptText,
                                visualStyle,
                                voiceProfile,
                                captionStyle
                            )
                        },
                        enabled = wordCount > 0 && !isOverLimit && !pipelineStatus.active && !isReadingFile,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SaffronGold,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp)
                            .height(54.dp)
                            .testTag("analyze_script_button")
                    ) {
                        if (pipelineStatus.active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(pipelineStatus.phaseTitle, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_analyze_script),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 740.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Upload & Quick Sample Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            documentLauncher.launch(arrayOf("text/plain", "text/markdown", "text/*"))
                        },
                        enabled = !isReadingFile,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("upload_script_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.UploadFile,
                            contentDescription = null,
                            tint = SaffronGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isReadingFile) "Reading…" else stringResource(R.string.action_upload_script),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            title = TeluguScriptChunker.SAMPLE_TELUGU_TITLE
                            scriptText = TeluguScriptChunker.SAMPLE_TELUGU_SCRIPT
                            uploadError = null
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("load_sample_script_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = CelestialTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_load_sample),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                uploadError?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Project Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(160) },
                    label = { Text("Video Title (తెలుగు / English)") },
                    placeholder = {
                        Text(
                            text = "ఉదా: గోదావరి తీరంలో బంగారు దీపం",
                            fontFamily = TeluguFontFamily
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = TeluguFontFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SaffronGold,
                        unfocusedBorderColor = SubtlePlumOutline,
                        focusedContainerColor = PlumCharcoalCard,
                        unfocusedContainerColor = PlumCharcoalCard
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_title_input")
                )

                // Live 10,000-Word Capacity & Runtime Meter
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(PlumCharcoalCard)
                        .border(1.dp, SubtlePlumOutline, MaterialTheme.shapes.medium)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$wordCount / 10,000 words",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isOverLimit) MaterialTheme.colorScheme.error else SaffronGold
                        )
                        Text(
                            text = "Est. Video: ${TeluguScriptChunker.formatDuration(estimatedSeconds)} • $estimatedChunks AI ${if (estimatedChunks == 1) "chunk" else "chunks"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CelestialTeal
                        )
                    }
                    LinearProgressIndicator(
                        progress = { fillFraction },
                        color = if (isOverLimit) MaterialTheme.colorScheme.error else SaffronGold,
                        trackColor = ElevatedVelvetCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                    )
                    if (isOverLimit) {
                        Text(
                            text = "Script exceeds 10,000 words. Trim ${wordCount - TeluguScriptChunker.MAX_WORD_COUNT} words to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Telugu Script Body Editor
                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = it },
                    label = { Text("Telugu Script & Dialogues (తెలుగు స్క్రిప్ట్)") },
                    placeholder = {
                        Text(
                            text = "మీ తెలుగు కథ లేదా సంభాషణలను ఇక్కడ పేస్ట్ చేయండి లేదా .txt ఫైల్‌ను అప్‌లోడ్ చేయండి (10,000 పదాల వరకు)…",
                            fontFamily = TeluguFontFamily
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = TeluguFontFamily,
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    minLines = 8,
                    maxLines = 14,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SaffronGold,
                        unfocusedBorderColor = SubtlePlumOutline,
                        focusedContainerColor = PlumCharcoalCard,
                        unfocusedContainerColor = PlumCharcoalCard
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("telugu_script_input")
                )

                // Visual Style Selector
                SectionSelectorHeader(
                    icon = Icons.Filled.Palette,
                    title = "AI Clip Visual Direction"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VisualStyle.entries.forEach { style ->
                        val selected = style == visualStyle
                        Column(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (selected) SaffronGold.copy(alpha = 0.18f) else PlumCharcoalCard
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) SaffronGold else SubtlePlumOutline,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .clickable { visualStyle = style }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("visual_style_${style.name}")
                        ) {
                            Text(
                                text = style.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) SaffronGold else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = style.teluguLabel,
                                fontFamily = TeluguFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Human-Like Telugu Narration Voice Selector
                SectionSelectorHeader(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Human-Like Telugu Narration Voice"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VoiceProfile.entries.forEach { profile ->
                        val selected = profile == voiceProfile
                        Column(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (selected) CelestialTeal.copy(alpha = 0.16f) else PlumCharcoalCard
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) CelestialTeal else SubtlePlumOutline,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .clickable { voiceProfile = profile }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("voice_profile_${profile.name}")
                        ) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) CelestialTeal else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = profile.teluguLabel,
                                fontFamily = TeluguFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Dialogue Caption Style Selector
                SectionSelectorHeader(
                    icon = Icons.Filled.ClosedCaption,
                    title = "Dialogue Caption Style"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CaptionStyle.entries.forEach { cap ->
                        val selected = cap == captionStyle
                        FilterChip(
                            selected = selected,
                            onClick = { captionStyle = cap },
                            label = {
                                Text(
                                    text = "${cap.displayName} (${cap.teluguLabel})",
                                    fontFamily = TeluguFontFamily
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SaffronGold.copy(alpha = 0.22f),
                                selectedLabelColor = SaffronGold
                            ),
                            modifier = Modifier.testTag("caption_style_${cap.name}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionSelectorHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SaffronGold,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
