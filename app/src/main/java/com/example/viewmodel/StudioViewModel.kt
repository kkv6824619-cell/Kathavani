package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.ai.KathaAiEngine
import com.example.ai.TeluguScriptChunker
import com.example.data.ProjectMediaStore
import com.example.data.ProjectRepository
import com.example.model.CameraMotion
import com.example.model.CaptionStyle
import com.example.model.ExportVideoState
import com.example.model.PipelineStatus
import com.example.model.ProjectStage
import com.example.model.SceneMediaState
import com.example.model.SceneMood
import com.example.model.StoryScene
import com.example.model.UiState
import com.example.model.VideoProject
import com.example.model.VisualStyle
import com.example.model.VoiceProfile
import com.example.video.ExportSceneInput
import com.example.video.LongVideoExporter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "StudioViewModel"

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModel(
    private val repository: ProjectRepository,
    private val mediaStore: ProjectMediaStore,
    private val aiEngine: KathaAiEngine,
    private val videoExporter: LongVideoExporter,
    private val currentUserId: String
) : ViewModel() {

    val projectsState: StateFlow<UiState<List<VideoProject>>> = repository.observeProjects(currentUserId)
        .map<List<VideoProject>, UiState<List<VideoProject>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Error observing projects", root)
            emit(UiState.Error(root.localizedMessage ?: "Failed to load projects."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UiState.Loading
        )

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    val activeProject: StateFlow<VideoProject?> = _selectedProjectId
        .flatMapLatest { pid ->
            if (pid.isNullOrBlank()) flowOf(null)
            else repository.observeProject(currentUserId, pid)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing active project", firestoreError ?: error)
                    emit(null)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null
        )

    val activeScenes: StateFlow<List<StoryScene>> = _selectedProjectId
        .flatMapLatest { pid ->
            if (pid.isNullOrBlank()) flowOf(emptyList())
            else repository.observeScenes(currentUserId, pid)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing scenes", firestoreError ?: error)
                    emit(emptyList())
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    private val _sceneMediaMap = MutableStateFlow<Map<String, SceneMediaState>>(emptyMap())
    val sceneMediaMap: StateFlow<Map<String, SceneMediaState>> = _sceneMediaMap.asStateFlow()

    private val _pipelineStatus = MutableStateFlow(PipelineStatus())
    val pipelineStatus: StateFlow<PipelineStatus> = _pipelineStatus.asStateFlow()

    private val _exportState = MutableStateFlow(ExportVideoState())
    val exportState: StateFlow<ExportVideoState> = _exportState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var activePipelineJob: Job? = null
    private var activeExportJob: Job? = null

    init {
        viewModelScope.launch {
            activeScenes.collect { scenes ->
                val projectId = _selectedProjectId.value ?: return@collect
                refreshLocalMediaStates(projectId, scenes)
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun dismissPipelineError() {
        _pipelineStatus.update { it.copy(errorMessage = null) }
    }

    fun selectProject(projectId: String) {
        if (_selectedProjectId.value != projectId) {
            _selectedProjectId.value = projectId
            _sceneMediaMap.value = emptyMap()
            val existingExport = mediaStore.getExistingExportPath(currentUserId, projectId)
            _exportState.value = ExportVideoState(exportedFilePath = existingExport)
        }
    }

    private fun refreshLocalMediaStates(projectId: String, scenes: List<StoryScene>) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentMap = _sceneMediaMap.value
            val updated = scenes.associate { scene ->
                val prev = currentMap[scene.id] ?: SceneMediaState()
                val imgPath = mediaStore.getExistingImagePath(currentUserId, projectId, scene.id)
                val audioInfo = mediaStore.getExistingAudioInfo(currentUserId, projectId, scene.id)
                scene.id to prev.copy(
                    imagePath = imgPath,
                    audioPath = audioInfo?.first,
                    audioDurationMs = audioInfo?.second
                )
            }
            _sceneMediaMap.value = updated
        }
    }

    fun createProjectAndAnalyze(
        title: String,
        scriptText: String,
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle,
        onProjectCreated: (String) -> Unit
    ) {
        val wordCount = TeluguScriptChunker.countWords(scriptText)
        if (wordCount == 0) {
            _snackbarMessage.value = "Please upload or paste a Telugu script first."
            return
        }
        if (wordCount > TeluguScriptChunker.MAX_WORD_COUNT) {
            _snackbarMessage.value = "Script exceeds 10,000 words ($wordCount words). Please trim to 10,000 words or fewer."
            return
        }

        val estSec = TeluguScriptChunker.estimateDurationSeconds(wordCount)
        activePipelineJob?.cancel()
        activePipelineJob = viewModelScope.launch {
            _pipelineStatus.value = PipelineStatus(
                active = true,
                phaseTitle = "Saving Telugu Script…",
                detailMessage = "Syncing $wordCount words to your cloud studio",
                currentStep = 0,
                totalSteps = 1
            )

            val createResult = repository.createProject(
                userId = currentUserId,
                title = title,
                scriptText = scriptText,
                wordCount = wordCount.toLong(),
                visualStyle = visualStyle,
                voiceProfile = voiceProfile,
                captionStyle = captionStyle,
                estimatedDurationSec = estSec
            )

            val projectId = createResult.getOrElse { err ->
                _pipelineStatus.value = PipelineStatus(
                    active = false,
                    errorMessage = err.localizedMessage ?: "Could not save project to Firestore."
                )
                return@launch
            }

            selectProject(projectId)
            onProjectCreated(projectId)
            runStoryboardAnalysisInternal(projectId, scriptText, visualStyle)
        }
    }

    fun updateScriptAndAnalyze(
        projectId: String,
        title: String,
        scriptText: String,
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle,
        onCompleteNavigate: () -> Unit
    ) {
        val wordCount = TeluguScriptChunker.countWords(scriptText)
        if (wordCount == 0) {
            _snackbarMessage.value = "Please enter a Telugu script before analyzing."
            return
        }
        if (wordCount > TeluguScriptChunker.MAX_WORD_COUNT) {
            _snackbarMessage.value = "Script exceeds 10,000 words ($wordCount words)."
            return
        }
        val estSec = TeluguScriptChunker.estimateDurationSeconds(wordCount)

        activePipelineJob?.cancel()
        activePipelineJob = viewModelScope.launch {
            _pipelineStatus.value = PipelineStatus(
                active = true,
                phaseTitle = "Updating Script…",
                detailMessage = "Saving updated Telugu script ($wordCount words)"
            )
            val updateResult = repository.updateProjectScriptAndSettings(
                userId = currentUserId,
                projectId = projectId,
                title = title,
                scriptText = scriptText,
                wordCount = wordCount.toLong(),
                visualStyle = visualStyle,
                voiceProfile = voiceProfile,
                captionStyle = captionStyle,
                estimatedDurationSec = estSec
            )
            if (updateResult.isFailure) {
                _pipelineStatus.value = PipelineStatus(
                    active = false,
                    errorMessage = updateResult.exceptionOrNull()?.localizedMessage ?: "Failed to update script."
                )
                return@launch
            }
            selectProject(projectId)
            onCompleteNavigate()
            runStoryboardAnalysisInternal(projectId, scriptText, visualStyle)
        }
    }

    fun reanalyzeCurrentProject() {
        val project = activeProject.value ?: return
        activePipelineJob?.cancel()
        activePipelineJob = viewModelScope.launch {
            runStoryboardAnalysisInternal(project.id, project.scriptText, project.visualStyleEnum)
        }
    }

    private suspend fun runStoryboardAnalysisInternal(
        projectId: String,
        scriptText: String,
        visualStyle: VisualStyle
    ) {
        val chunks = TeluguScriptChunker.chunkScript(scriptText)
        if (chunks.isEmpty()) {
            _pipelineStatus.value = PipelineStatus(
                active = false,
                errorMessage = "Script text is empty."
            )
            return
        }

        val allScenes = mutableListOf<StoryScene>()
        try {
            for (index in chunks.indices) {
                _pipelineStatus.value = PipelineStatus(
                    active = true,
                    phaseTitle = "AI Storyboard Director (${index + 1}/${chunks.size})",
                    detailMessage = "Breaking Telugu script chunk ${index + 1} into cinematic scenes, captions & visual prompts…",
                    currentStep = index,
                    totalSteps = chunks.size
                )
                val chunkScenes = aiEngine.analyzeScriptChunk(
                    userId = currentUserId,
                    projectId = projectId,
                    chunkText = chunks[index],
                    chunkIndex = index,
                    totalChunks = chunks.size,
                    startingSceneIndex = allScenes.size,
                    visualStyle = visualStyle
                )
                allScenes.addAll(chunkScenes)
            }

            _pipelineStatus.value = PipelineStatus(
                active = true,
                phaseTitle = "Saving ${allScenes.size} Scenes…",
                detailMessage = "Writing synchronized Telugu dialogues and captions to cloud storyboard",
                currentStep = chunks.size,
                totalSteps = chunks.size
            )

            val saveRes = repository.replaceProjectScenes(currentUserId, projectId, allScenes)
            if (saveRes.isFailure) {
                throw saveRes.exceptionOrNull() ?: IllegalStateException("Failed to save scenes.")
            }

            val totalDurationSec = (allScenes.sumOf { it.durationMs } / 1000L).coerceAtLeast(1L)
            repository.updateProjectProgress(
                userId = currentUserId,
                projectId = projectId,
                sceneCount = allScenes.size.toLong(),
                completedVisualsCount = 0L,
                completedAudioCount = 0L,
                stage = ProjectStage.STORYBOARD,
                estimatedDurationSec = totalDurationSec
            )

            _pipelineStatus.value = PipelineStatus(active = false)
            _snackbarMessage.value = "Storyboard ready with ${allScenes.size} Telugu scenes!"
        } catch (e: CancellationException) {
            _pipelineStatus.value = PipelineStatus(active = false)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Storyboard pipeline error", e)
            _pipelineStatus.value = PipelineStatus(
                active = false,
                errorMessage = e.localizedMessage ?: "Failed to analyze Telugu script."
            )
        }
    }

    fun generateVisualForScene(scene: StoryScene) {
        val project = activeProject.value ?: return
        viewModelScope.launch {
            generateSingleVisualInternal(project, scene)
            syncProjectMediaCounters(project.id)
        }
    }

    fun generateNarrationForScene(scene: StoryScene) {
        val project = activeProject.value ?: return
        viewModelScope.launch {
            generateSingleNarrationInternal(project, scene)
            syncProjectMediaCounters(project.id)
        }
    }

    fun generateAllPendingMedia() {
        val project = activeProject.value ?: return
        val scenes = activeScenes.value
        if (scenes.isEmpty()) {
            _snackbarMessage.value = "Analyze your Telugu script first to build scenes."
            return
        }

        activePipelineJob?.cancel()
        activePipelineJob = viewModelScope.launch {
            val mediaMap = _sceneMediaMap.value
            val tasks = mutableListOf<Pair<StoryScene, Boolean>>() // Pair<Scene, isVisual>
            scenes.forEach { scene ->
                val media = mediaMap[scene.id]
                if (media?.imagePath == null) tasks.add(scene to true)
                if (media?.audioPath == null) tasks.add(scene to false)
            }

            if (tasks.isEmpty()) {
                _snackbarMessage.value = "All AI visual clips and Telugu narrations are already ready!"
                return@launch
            }

            var completedSteps = 0
            var failureCount = 0
            try {
                for ((scene, isVisual) in tasks) {
                    val label = if (isVisual) "AI Visual Clip" else "Telugu Voice Narration"
                    _pipelineStatus.value = PipelineStatus(
                        active = true,
                        phaseTitle = "Producing $label (${completedSteps + 1}/${tasks.size})",
                        detailMessage = "Scene ${scene.sceneIndex + 1}: ${scene.titleTelugu}",
                        currentStep = completedSteps,
                        totalSteps = tasks.size
                    )
                    val ok = if (isVisual) {
                        generateSingleVisualInternal(project, scene)
                    } else {
                        generateSingleNarrationInternal(project, scene)
                    }
                    if (!ok) failureCount++
                    completedSteps++
                    syncProjectMediaCounters(project.id)
                }

                _pipelineStatus.value = PipelineStatus(
                    active = false,
                    errorMessage = if (failureCount > 0) {
                        "$failureCount clip asset(s) encountered errors. Check individual scene cards to retry."
                    } else {
                        null
                    }
                )
                if (failureCount == 0) {
                    _snackbarMessage.value = "All AI clips & synchronized Telugu narration generated!"
                }
            } catch (e: CancellationException) {
                _pipelineStatus.value = PipelineStatus(active = false)
                throw e
            }
        }
    }

    fun cancelActivePipeline() {
        activePipelineJob?.cancel()
        activePipelineJob = null
        _pipelineStatus.value = PipelineStatus(active = false)
        _snackbarMessage.value = "Generation paused. Completed clips are saved."
    }

    private suspend fun generateSingleVisualInternal(
        project: VideoProject,
        scene: StoryScene
    ): Boolean {
        _sceneMediaMap.update { map ->
            val prev = map[scene.id] ?: SceneMediaState()
            map + (scene.id to prev.copy(imageLoading = true, imageError = null))
        }

        return try {
            val bitmap = aiEngine.generateSceneVisual(scene, project.visualStyleEnum)
            val savedFile = withContext(Dispatchers.IO) {
                mediaStore.saveSceneBitmap(currentUserId, project.id, scene.id, bitmap)
            }
            _sceneMediaMap.update { map ->
                val prev = map[scene.id] ?: SceneMediaState()
                map + (scene.id to prev.copy(
                    imagePath = savedFile.absolutePath,
                    imageLoading = false,
                    imageError = null
                ))
            }
            true
        } catch (e: CancellationException) {
            _sceneMediaMap.update { map ->
                val prev = map[scene.id] ?: SceneMediaState()
                map + (scene.id to prev.copy(imageLoading = false))
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Visual error for scene ${scene.id}", e)
            _sceneMediaMap.update { map ->
                val prev = map[scene.id] ?: SceneMediaState()
                map + (scene.id to prev.copy(
                    imageLoading = false,
                    imageError = e.localizedMessage ?: "Visual generation failed."
                ))
            }
            false
        }
    }

    private suspend fun generateSingleNarrationInternal(
        project: VideoProject,
        scene: StoryScene
    ): Boolean {
        _sceneMediaMap.update { map ->
            val prev = map[scene.id] ?: SceneMediaState()
            map + (scene.id to prev.copy(audioLoading = true, audioError = null))
        }

        return try {
            val synthesized = aiEngine.generateSceneNarration(scene, project.voiceProfileEnum)
            val (savedFile, measuredMs) = withContext(Dispatchers.IO) {
                mediaStore.saveSceneAudio(
                    userId = currentUserId,
                    projectId = project.id,
                    sceneId = scene.id,
                    audioBytes = synthesized.bytes,
                    mimeType = synthesized.mimeType
                )
            }
            val safeDurationMs = measuredMs.coerceAtLeast(2_000L)
            repository.updateSceneDuration(currentUserId, project.id, scene.id, safeDurationMs)
            _sceneMediaMap.update { map ->
                val prev = map[scene.id] ?: SceneMediaState()
                map + (scene.id to prev.copy(
                    audioPath = savedFile.absolutePath,
                    audioDurationMs = safeDurationMs,
                    audioLoading = false,
                    audioError = null
                ))
            }
            true
        } catch (e: CancellationException) {
            _sceneMediaMap.update { map ->
                val prev = map[scene.id] ?: SceneMediaState()
                map + (scene.id to prev.copy(audioLoading = false))
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio error for scene ${scene.id}", e)
            _sceneMediaMap.update { map ->
                val prev = map[scene.id] ?: SceneMediaState()
                map + (scene.id to prev.copy(
                    audioLoading = false,
                    audioError = e.localizedMessage ?: "Telugu narration synthesis failed."
                ))
            }
            false
        }
    }

    private suspend fun syncProjectMediaCounters(projectId: String) {
        val scenes = activeScenes.value
        val mediaMap = _sceneMediaMap.value
        val visualCount = scenes.count { mediaMap[it.id]?.imagePath != null }.toLong()
        val audioCount = scenes.count { mediaMap[it.id]?.audioPath != null }.toLong()
        val totalDurationSec = (scenes.sumOf { scene ->
            mediaMap[scene.id]?.audioDurationMs ?: scene.durationMs
        } / 1000L).coerceAtLeast(1L)
        val stage = when {
            mediaStore.getExistingExportPath(currentUserId, projectId) != null -> ProjectStage.RENDERED
            visualCount > 0 || audioCount > 0 -> ProjectStage.PRODUCING
            scenes.isNotEmpty() -> ProjectStage.STORYBOARD
            else -> ProjectStage.SCRIPT
        }
        repository.updateProjectProgress(
            userId = currentUserId,
            projectId = projectId,
            sceneCount = scenes.size.toLong(),
            completedVisualsCount = visualCount,
            completedAudioCount = audioCount,
            stage = stage,
            estimatedDurationSec = totalDurationSec
        )
    }

    fun updateSceneDetails(
        scene: StoryScene,
        titleTelugu: String,
        speaker: String,
        dialogueTelugu: String,
        captionLines: List<String>,
        visualPrompt: String,
        cameraMotion: CameraMotion,
        mood: SceneMood,
        voiceDirection: String
    ) {
        val projectId = activeProject.value?.id ?: return
        viewModelScope.launch {
            val result = repository.updateScene(
                userId = currentUserId,
                projectId = projectId,
                sceneId = scene.id,
                titleTelugu = titleTelugu,
                speaker = speaker,
                dialogueTelugu = dialogueTelugu,
                captionLines = captionLines,
                visualPrompt = visualPrompt,
                cameraMotion = cameraMotion,
                mood = mood,
                voiceDirection = voiceDirection,
                durationMs = scene.durationMs
            )
            if (result.isSuccess) {
                _snackbarMessage.value = "Scene ${scene.sceneIndex + 1} updated."
            } else {
                _snackbarMessage.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to update scene."
            }
        }
    }

    fun updateProjectStyles(
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle
    ) {
        val project = activeProject.value ?: return
        viewModelScope.launch {
            val res = repository.updateProjectStylePreferences(
                userId = currentUserId,
                projectId = project.id,
                visualStyle = visualStyle,
                voiceProfile = voiceProfile,
                captionStyle = captionStyle
            )
            if (res.isFailure) {
                _snackbarMessage.value = res.exceptionOrNull()?.localizedMessage ?: "Could not save style preferences."
            }
        }
    }

    fun exportLongVideo() {
        val project = activeProject.value ?: return
        val scenes = activeScenes.value
        if (scenes.isEmpty()) {
            _snackbarMessage.value = "No scenes available to export."
            return
        }

        val mediaMap = _sceneMediaMap.value
        val inputs = scenes.map { scene ->
            val media = mediaMap[scene.id]
            ExportSceneInput(
                scene = scene.copy(durationMs = media?.audioDurationMs ?: scene.durationMs),
                imagePath = media?.imagePath,
                audioPath = media?.audioPath
            )
        }

        activeExportJob?.cancel()
        activeExportJob = viewModelScope.launch {
            _exportState.value = ExportVideoState(
                exporting = true,
                progressFraction = 0.02f,
                statusText = "Initializing HD 720p H.264 + AAC compiler…"
            )
            try {
                val outFile = mediaStore.exportedVideoFile(currentUserId, project.id)
                val result = videoExporter.exportLongVideo(
                    projectTitle = project.title,
                    captionStyle = project.captionStyleEnum,
                    scenes = inputs,
                    outputFile = outFile
                ) { fraction, message ->
                    _exportState.update {
                        it.copy(
                            exporting = true,
                            progressFraction = fraction,
                            statusText = message
                        )
                    }
                }

                syncProjectMediaCounters(project.id)
                _exportState.value = ExportVideoState(
                    exporting = false,
                    progressFraction = 1f,
                    statusText = "Saved to Movies/KathaVani",
                    exportedFilePath = result.localFile.absolutePath,
                    savedToGalleryUri = result.galleryUri?.toString()
                )
                _snackbarMessage.value = "Long HD Telugu MP4 exported & saved to Movies/KathaVani!"
            } catch (e: CancellationException) {
                _exportState.update { it.copy(exporting = false, statusText = "Export cancelled.") }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "MP4 export failed", e)
                _exportState.value = ExportVideoState(
                    exporting = false,
                    errorMessage = e.localizedMessage ?: "Video export failed."
                )
            }
        }
    }

    fun cancelExport() {
        activeExportJob?.cancel()
        activeExportJob = null
        _exportState.update { it.copy(exporting = false, statusText = "") }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            val res = repository.deleteProject(currentUserId, projectId)
            if (res.isSuccess) {
                withContext(Dispatchers.IO) {
                    mediaStore.deleteProjectMedia(currentUserId, projectId)
                }
                if (_selectedProjectId.value == projectId) {
                    _selectedProjectId.value = null
                }
                _snackbarMessage.value = "Project deleted."
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.localizedMessage ?: "Could not delete project."
            }
        }
    }

    companion object {
        fun provideFactory(currentUserId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as Application
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                StudioViewModel(
                    repository = ProjectRepository(db),
                    mediaStore = ProjectMediaStore(app),
                    aiEngine = KathaAiEngine(),
                    videoExporter = LongVideoExporter(app),
                    currentUserId = currentUserId
                )
            }
        }
    }
}
