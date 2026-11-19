package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.firebase.authStateFlow
import com.example.firebase.configureAppCheck
import com.example.firebase.signOutUser
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CinemaPlayerScreen
import com.example.ui.screens.ProductionStudioScreen
import com.example.ui.screens.ProjectsHomeScreen
import com.example.ui.screens.ScriptEditorScreen
import com.example.ui.theme.KathaVaniTheme
import com.example.ui.theme.ObsidianCanvas
import com.example.viewmodel.StudioViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

private const val ROUTE_HOME = "home"
private const val ROUTE_SCRIPT_EDITOR = "script_editor?projectId={projectId}"
private const val ROUTE_STUDIO = "studio/{projectId}"
private const val ROUTE_PLAYER = "player/{projectId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1 (MANDATORY): Configure Firebase App Check with launch intent before UI/ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            KathaVaniTheme {
                KathaVaniApp()
            }
        }
    }
}

@Composable
fun KathaVaniApp(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { /* authStateFlow updates automatically */ })
    } else {
        key(user.uid) {
            AuthenticatedStudioShell(
                currentUserId = user.uid,
                userDisplayName = user.displayName?.takeIf { it.isNotBlank() }
                    ?: user.email?.substringBefore("@")
                    ?: "Director"
            )
        }
    }
}

@Composable
private fun AuthenticatedStudioShell(
    currentUserId: String,
    userDisplayName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val studioViewModel: StudioViewModel = viewModel(
        key = currentUserId,
        factory = StudioViewModel.provideFactory(currentUserId)
    )

    val projectsState by studioViewModel.projectsState.collectAsStateWithLifecycle()
    val activeProject by studioViewModel.activeProject.collectAsStateWithLifecycle()
    val activeScenes by studioViewModel.activeScenes.collectAsStateWithLifecycle()
    val sceneMediaMap by studioViewModel.sceneMediaMap.collectAsStateWithLifecycle()
    val pipelineStatus by studioViewModel.pipelineStatus.collectAsStateWithLifecycle()
    val exportState by studioViewModel.exportState.collectAsStateWithLifecycle()
    val snackbarMessage by studioViewModel.snackbarMessage.collectAsStateWithLifecycle()

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            studioViewModel.clearSnackbar()
        }
    }

    Scaffold(
        containerColor = ObsidianCanvas,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { outerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = outerPadding.calculateBottomPadding() * 0f)
        ) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_HOME
            ) {
                composable(ROUTE_HOME) {
                    ProjectsHomeScreen(
                        userDisplayName = userDisplayName,
                        projectsState = projectsState,
                        onCreateNewProject = {
                            navController.navigate("script_editor")
                        },
                        onOpenProject = { project ->
                            studioViewModel.selectProject(project.id)
                            navController.navigate("studio/${project.id}")
                        },
                        onDeleteProject = { projectId ->
                            studioViewModel.deleteProject(projectId)
                        },
                        onSignOut = {
                            studioViewModel.cancelActivePipeline()
                            studioViewModel.cancelExport()
                            signOutUser(credentialManager, scope)
                        }
                    )
                }

                composable(
                    route = ROUTE_SCRIPT_EDITOR,
                    arguments = listOf(
                        navArgument("projectId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val editProjectId = backStackEntry.arguments?.getString("projectId")
                    val existing = if (editProjectId != null && activeProject?.id == editProjectId) {
                        activeProject
                    } else null

                    ScriptEditorScreen(
                        existingProject = existing,
                        pipelineStatus = pipelineStatus,
                        onBack = { navController.popBackStack() },
                        onAnalyzeAndBuild = { title, scriptText, visualStyle, voiceProfile, captionStyle ->
                            if (existing != null) {
                                studioViewModel.updateScriptAndAnalyze(
                                    projectId = existing.id,
                                    title = title,
                                    scriptText = scriptText,
                                    visualStyle = visualStyle,
                                    voiceProfile = voiceProfile,
                                    captionStyle = captionStyle,
                                    onCompleteNavigate = {
                                        navController.popBackStack()
                                    }
                                )
                            } else {
                                studioViewModel.createProjectAndAnalyze(
                                    title = title,
                                    scriptText = scriptText,
                                    visualStyle = visualStyle,
                                    voiceProfile = voiceProfile,
                                    captionStyle = captionStyle,
                                    onProjectCreated = { newId ->
                                        navController.navigate("studio/$newId") {
                                            popUpTo(ROUTE_HOME)
                                        }
                                    }
                                )
                            }
                        }
                    )
                }

                composable(
                    route = ROUTE_STUDIO,
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
                    LaunchedEffect(projectId) {
                        if (projectId.isNotBlank()) studioViewModel.selectProject(projectId)
                    }

                    ProductionStudioScreen(
                        project = activeProject,
                        scenes = activeScenes,
                        sceneMediaMap = sceneMediaMap,
                        pipelineStatus = pipelineStatus,
                        onBack = { navController.popBackStack() },
                        onEditScript = {
                            navController.navigate("script_editor?projectId=$projectId")
                        },
                        onReanalyzeScript = { studioViewModel.reanalyzeCurrentProject() },
                        onGenerateAllPending = { studioViewModel.generateAllPendingMedia() },
                        onCancelPipeline = { studioViewModel.cancelActivePipeline() },
                        onDismissPipelineError = { studioViewModel.dismissPipelineError() },
                        onGenerateVisual = { scene -> studioViewModel.generateVisualForScene(scene) },
                        onGenerateNarration = { scene -> studioViewModel.generateNarrationForScene(scene) },
                        onUpdateScene = { scene, titleT, speaker, dialogue, captions, visualP, motion, mood, voiceDir ->
                            studioViewModel.updateSceneDetails(
                                scene,
                                titleT,
                                speaker,
                                dialogue,
                                captions,
                                visualP,
                                motion,
                                mood,
                                voiceDir
                            )
                        },
                        onOpenCinemaPlayer = {
                            navController.navigate("player/$projectId")
                        }
                    )
                }

                composable(
                    route = ROUTE_PLAYER,
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
                    LaunchedEffect(projectId) {
                        if (projectId.isNotBlank()) studioViewModel.selectProject(projectId)
                    }

                    CinemaPlayerScreen(
                        project = activeProject,
                        scenes = activeScenes,
                        sceneMediaMap = sceneMediaMap,
                        exportState = exportState,
                        onBack = { navController.popBackStack() },
                        onExportLongVideo = { studioViewModel.exportLongVideo() },
                        onCancelExport = { studioViewModel.cancelExport() }
                    )
                }
            }
        }
    }
}
