const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_director_01";
const BOB_UID = "bob_director_02";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProjectPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    title: "మహాభారత గాథ - తెలుగు చిత్రం",
    scriptPreview: "అనగనగా ఒక మహా నగరంలో...",
    scriptText: "అనగనగా ఒక మహా నగరంలో ఒక వీరుడు జన్మించాడు. అతని కథ చరిత్రలో నిలిచిపోయింది.",
    wordCount: 12,
    sceneCount: 0,
    completedVisualsCount: 0,
    completedAudioCount: 0,
    visualStyle: "CINEMATIC_TOLLYWOOD",
    voiceProfile: "KORE_WARM",
    captionStyle: "CINEMA_GOLD",
    stage: "SCRIPT",
    estimatedDurationSec: 6,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validScenePayload(uid, projectId, overrides = {}) {
  const now = new Date(Date.now() - 1500);
  return {
    userId: uid,
    projectId,
    sceneIndex: 0,
    titleTelugu: "దృశ్యం 1: ప్రారంభం",
    speaker: "నరేటర్",
    dialogueTelugu: "అనగనగా ఒక మహా నగరంలో ఒక వీరుడు జన్మించాడు.",
    captionLines: ["అనగనగా ఒక మహా నగరంలో", "ఒక వీరుడు జన్మించాడు."],
    visualPrompt: "Cinematic golden hour view of an ancient South Indian kingdom, volumetric light",
    cameraMotion: "ZOOM_IN",
    mood: "EPIC",
    voiceDirection: "Warm cinematic Telugu storytelling voice with dramatic pause",
    durationMs: 6500,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read, list, or write projects or scenes", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_1")
      .set(validProjectPayload(ALICE_UID))
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_1")
      .collection("scenes")
      .get()
  );
});

test("Owner: valid VideoProject create, list, partial updates, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const projRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects")
    .doc("proj_01");

  // Create project (mirrors Kotlin repository create payload without @DocumentId id field)
  await assertSucceeds(projRef.set(validProjectPayload(ALICE_UID)));

  // List projects (mirrors observeProjects())
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .orderBy("updatedAt", "desc")
      .get()
  );

  // Action 1: Edit script & style settings
  await assertSucceeds(
    projRef.update({
      title: "కొత్త తెలుగు కథ",
      visualStyle: "MYTHOLOGICAL_EPIC",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Action 2: Update production progress & storyboard counters
  await assertSucceeds(
    projRef.update({
      sceneCount: 8,
      completedVisualsCount: 4,
      completedAudioCount: 4,
      stage: "PRODUCING",
      estimatedDurationSec: 52,
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Delete project
  await assertSucceeds(projRef.delete());
});

test("Security bounds: reject shadow fields, @DocumentId id field, future timestamps, and immutable mutations", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const projectsCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects");

  // Reject ghost/shadow field
  await assertFails(
    projectsCol
      .doc("proj_ghost")
      .set(validProjectPayload(ALICE_UID, { isVerified: true }))
  );

  // Reject redundant @DocumentId `id` field
  await assertFails(
    projectsCol
      .doc("proj_with_id")
      .set(validProjectPayload(ALICE_UID, { id: "proj_with_id" }))
  );

  // Reject tampered future timestamp
  await assertFails(
    projectsCol
      .doc("proj_future")
      .set(
        validProjectPayload(ALICE_UID, {
          createdAt: new Date(Date.now() + 60000),
          updatedAt: new Date(Date.now() + 60000),
        })
      )
  );

  // Reject invalid enum
  await assertFails(
    projectsCol
      .doc("proj_bad_style")
      .set(validProjectPayload(ALICE_UID, { visualStyle: "UNKNOWN_STYLE" }))
  );

  // Reject spoofing another userId
  await assertFails(
    projectsCol
      .doc("proj_spoof")
      .set(validProjectPayload(BOB_UID))
  );

  // Create valid doc, then verify immutable fields cannot be modified
  await assertSucceeds(
    projectsCol.doc("proj_immut").set(validProjectPayload(ALICE_UID))
  );
  await assertFails(
    projectsCol.doc("proj_immut").update({
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );
  await assertFails(
    projectsCol.doc("proj_immut").update({
      userId: BOB_UID,
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("StoryScene: parent existence gate, owner CRUD, ordered list query, and cross-user isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Orphan scene creation without parent project MUST fail
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("missing_proj")
      .collection("scenes")
      .doc("scene_000")
      .set(validScenePayload(ALICE_UID, "missing_proj"))
  );

  // Create parent project first
  const projRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects")
    .doc("proj_epic");
  await assertSucceeds(projRef.set(validProjectPayload(ALICE_UID)));

  // Create scene under existing parent project
  const sceneRef = projRef.collection("scenes").doc("scene_000");
  await assertSucceeds(
    sceneRef.set(validScenePayload(ALICE_UID, "proj_epic"))
  );

  // Query ordered scenes (mirrors observeScenes())
  await assertSucceeds(
    projRef.collection("scenes").orderBy("sceneIndex", "asc").get()
  );

  // Partial update: edit dialogue & captions
  await assertSucceeds(
    sceneRef.update({
      dialogueTelugu: "సవరించిన తెలుగు సంభాషణ.",
      captionLines: ["సవరించిన తెలుగు సంభాషణ."],
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Partial update: sync measured audio duration
  await assertSucceeds(
    sceneRef.update({
      durationMs: 8400,
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Cross-user isolation: Bob cannot read, list, update, or delete Alice's project or scenes
  const bobViewOfAliceProj = bobDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects")
    .doc("proj_epic");
  await assertFails(bobViewOfAliceProj.get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertFails(bobViewOfAliceProj.collection("scenes").get());
  await assertFails(
    bobViewOfAliceProj.collection("scenes").doc("scene_000").update({
      durationMs: 9999,
      updatedAt: new Date(Date.now() - 100),
    })
  );
  await assertFails(bobViewOfAliceProj.delete());

  // Owner can delete scene
  await assertSucceeds(sceneRef.delete());
});
