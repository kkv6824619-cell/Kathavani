package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.model.CameraMotion
import com.example.model.CaptionStyle
import com.example.model.ProjectStage
import com.example.model.SceneMood
import com.example.model.StoryScene
import com.example.model.VideoProject
import com.example.model.VisualStyle
import com.example.model.VoiceProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ProjectRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUserId: String? = null): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        if (expectedUserId != null && uid != expectedUserId) {
            throw IllegalStateException("Signed-in user mismatch; refusing cross-user operation.")
        }
        return uid
    }

    fun observeProjects(userId: String): Flow<List<VideoProject>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/projects"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("projects")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VideoProject::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeProject(userId: String, projectId: String): Flow<VideoProject?> = flow {
        val uid = requireUserId(userId)
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val path = "users/$uid/projects/$projectId"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            VideoProject::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeScenes(userId: String, projectId: String): Flow<List<StoryScene>> = flow {
        val uid = requireUserId(userId)
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val path = "users/$uid/projects/$projectId/scenes"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .collection("scenes")
                .orderBy("sceneIndex", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        StoryScene::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createProject(
        userId: String,
        title: String,
        scriptText: String,
        wordCount: Long,
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle,
        estimatedDurationSec: Long
    ): Result<String> {
        var docPath = "users/$userId/projects"
        return try {
            val uid = requireUserId(userId)
            val cleanTitle = title.trim().take(160).ifEmpty { "తెలుగు కథ" }
            val cleanScript = scriptText.trim().take(250_000)
            require(cleanScript.isNotEmpty()) { "Script text cannot be empty." }
            val preview = cleanScript.replace(Regex("\\s+"), " ").take(280)
            val docRef = db.collection("users").document(uid).collection("projects").document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to uid,
                "title" to cleanTitle,
                "scriptPreview" to preview,
                "scriptText" to cleanScript,
                "wordCount" to wordCount.coerceIn(0L, 15_000L),
                "sceneCount" to 0L,
                "completedVisualsCount" to 0L,
                "completedAudioCount" to 0L,
                "visualStyle" to visualStyle.name,
                "voiceProfile" to voiceProfile.name,
                "captionStyle" to captionStyle.name,
                "stage" to ProjectStage.SCRIPT.name,
                "estimatedDurationSec" to estimatedDurationSec.coerceIn(0L, 36_000L),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateProjectScriptAndSettings(
        userId: String,
        projectId: String,
        title: String,
        scriptText: String,
        wordCount: Long,
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle,
        estimatedDurationSec: Long
    ): Result<Unit> {
        val docPath = "users/$userId/projects/$projectId"
        return try {
            val uid = requireUserId(userId)
            require(projectId.isNotBlank()) { "Project ID required" }
            val cleanTitle = title.trim().take(160).ifEmpty { "తెలుగు కథ" }
            val cleanScript = scriptText.trim().take(250_000)
            require(cleanScript.isNotEmpty()) { "Script text cannot be empty." }
            val preview = cleanScript.replace(Regex("\\s+"), " ").take(280)

            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .update(
                    mapOf(
                        "title" to cleanTitle,
                        "scriptPreview" to preview,
                        "scriptText" to cleanScript,
                        "wordCount" to wordCount.coerceIn(0L, 15_000L),
                        "visualStyle" to visualStyle.name,
                        "voiceProfile" to voiceProfile.name,
                        "captionStyle" to captionStyle.name,
                        "estimatedDurationSec" to estimatedDurationSec.coerceIn(0L, 36_000L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateProjectProgress(
        userId: String,
        projectId: String,
        sceneCount: Long,
        completedVisualsCount: Long,
        completedAudioCount: Long,
        stage: ProjectStage,
        estimatedDurationSec: Long
    ): Result<Unit> {
        val docPath = "users/$userId/projects/$projectId"
        return try {
            val uid = requireUserId(userId)
            require(projectId.isNotBlank()) { "Project ID required" }
            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .update(
                    mapOf(
                        "sceneCount" to sceneCount.coerceIn(0L, 400L),
                        "completedVisualsCount" to completedVisualsCount.coerceIn(0L, 400L),
                        "completedAudioCount" to completedAudioCount.coerceIn(0L, 400L),
                        "stage" to stage.name,
                        "estimatedDurationSec" to estimatedDurationSec.coerceIn(0L, 36_000L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateProjectStylePreferences(
        userId: String,
        projectId: String,
        visualStyle: VisualStyle,
        voiceProfile: VoiceProfile,
        captionStyle: CaptionStyle
    ): Result<Unit> {
        val docPath = "users/$userId/projects/$projectId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .update(
                    mapOf(
                        "visualStyle" to visualStyle.name,
                        "voiceProfile" to voiceProfile.name,
                        "captionStyle" to captionStyle.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun replaceProjectScenes(
        userId: String,
        projectId: String,
        scenes: List<StoryScene>
    ): Result<Unit> {
        val collectionPath = "users/$userId/projects/$projectId/scenes"
        return try {
            val uid = requireUserId(userId)
            require(projectId.isNotBlank()) { "Project ID required" }
            val scenesCol = db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .collection("scenes")

            // Delete existing scenes in batches of 400
            val existingDocs = scenesCol.get().await().documents
            existingDocs.chunked(400).forEach { chunk ->
                val deleteBatch = db.batch()
                chunk.forEach { deleteBatch.delete(it.reference) }
                deleteBatch.commit().await()
            }

            // Write new scenes in batches of 350
            val boundedScenes = scenes.take(400)
            boundedScenes.chunked(350).forEach { chunk ->
                val writeBatch = db.batch()
                chunk.forEach { scene ->
                    val sceneId = "scene_${scene.sceneIndex.toString().padStart(3, '0')}"
                    val docRef = scenesCol.document(sceneId)
                    val payload = mapOf(
                        "userId" to uid,
                        "projectId" to projectId,
                        "sceneIndex" to scene.sceneIndex.coerceIn(0L, 400L),
                        "titleTelugu" to scene.titleTelugu.trim().take(200).ifEmpty { "దృశ్యం ${scene.sceneIndex + 1}" },
                        "speaker" to scene.speaker.trim().take(100),
                        "dialogueTelugu" to scene.dialogueTelugu.trim().take(4000).ifEmpty { "తెలుగు కథనం" },
                        "captionLines" to scene.captionLines.map { it.trim() }.filter { it.isNotEmpty() }.take(40),
                        "visualPrompt" to scene.visualPrompt.trim().take(2000).ifEmpty { "Cinematic South Indian film frame, warm golden lighting" },
                        "cameraMotion" to CameraMotion.fromRaw(scene.cameraMotion).name,
                        "mood" to SceneMood.fromRaw(scene.mood).name,
                        "voiceDirection" to scene.voiceDirection.trim().take(300),
                        "durationMs" to scene.durationMs.coerceIn(1_000L, 600_000L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    writeBatch.set(docRef, payload)
                }
                writeBatch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, collectionPath)
            Result.failure(e)
        }
    }

    suspend fun updateScene(
        userId: String,
        projectId: String,
        sceneId: String,
        titleTelugu: String,
        speaker: String,
        dialogueTelugu: String,
        captionLines: List<String>,
        visualPrompt: String,
        cameraMotion: CameraMotion,
        mood: SceneMood,
        voiceDirection: String,
        durationMs: Long
    ): Result<Unit> {
        val docPath = "users/$userId/projects/$projectId/scenes/$sceneId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .collection("scenes")
                .document(sceneId)
                .update(
                    mapOf(
                        "titleTelugu" to titleTelugu.trim().take(200).ifEmpty { "దృశ్యం" },
                        "speaker" to speaker.trim().take(100),
                        "dialogueTelugu" to dialogueTelugu.trim().take(4000).ifEmpty { "తెలుగు సంభాషణ" },
                        "captionLines" to captionLines.map { it.trim() }.filter { it.isNotEmpty() }.take(40),
                        "visualPrompt" to visualPrompt.trim().take(2000).ifEmpty { "Cinematic film frame" },
                        "cameraMotion" to cameraMotion.name,
                        "mood" to mood.name,
                        "voiceDirection" to voiceDirection.trim().take(300),
                        "durationMs" to durationMs.coerceIn(1_000L, 600_000L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateSceneDuration(
        userId: String,
        projectId: String,
        sceneId: String,
        durationMs: Long
    ): Result<Unit> {
        val docPath = "users/$userId/projects/$projectId/scenes/$sceneId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users")
                .document(uid)
                .collection("projects")
                .document(projectId)
                .collection("scenes")
                .document(sceneId)
                .update(
                    mapOf(
                        "durationMs" to durationMs.coerceIn(1_000L, 600_000L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteProject(userId: String, projectId: String): Result<Unit> {
        val docPath = "users/$userId/projects/$projectId"
        return try {
            val uid = requireUserId(userId)
            val projRef = db.collection("users").document(uid).collection("projects").document(projectId)
            val sceneDocs = projRef.collection("scenes").get().await().documents
            sceneDocs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            projRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
