const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validBotPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    name: "Focus",
    role: "Settings & Timers",
    colorHex: 0xFF059669,
    active: true,
    opacityPercent: 90,
    inferenceMode: "PREFER_ON_DEVICE",
    systemPrompt: "Return ultra-compact Generative UI widgets for Android settings, timers, and focus controls.",
    allowScreenRead: false,
    allowActions: true,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validChatPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    role: "USER",
    botId: "focus",
    botName: "Focus",
    text: "Focus Mode (25m)",
    engineUsed: "NONE",
    createdAt: now,
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

// --- BOTS COLLECTION TESTS ---

test("Unauthenticated user: cannot read or write bots", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("bots").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("bots").doc("focus").set(validBotPayload(ALICE_UID))
  );
});

test("Authenticated owner: valid Kotlin create, list, get, toggle update, config update, and delete succeed for bots", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const botsCol = aliceDb.collection("users").doc(ALICE_UID).collection("bots");

  await assertSucceeds(botsCol.get());
  await assertSucceeds(botsCol.doc("focus").set(validBotPayload(ALICE_UID)));
  await assertSucceeds(botsCol.doc("focus").get());
  await assertSucceeds(botsCol.get());

  await assertSucceeds(
    botsCol.doc("focus").update({
      active: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    botsCol.doc("focus").update({
      name: "Deep Focus",
      inferenceMode: "ONLY_IN_CLOUD",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  await assertSucceeds(botsCol.doc("focus").delete());
});

test("Bots security bounds: reject extra/ghost fields, invalid IDs, future timestamps, immutable field edits, and invalid enums", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const botsCol = aliceDb.collection("users").doc(ALICE_UID).collection("bots");

  await assertFails(botsCol.doc("focus_extra").set(validBotPayload(ALICE_UID, { id: "focus_extra" })));
  await assertFails(botsCol.doc("focus_ghost").set(validBotPayload(ALICE_UID, { isVerified: true })));
  await assertFails(botsCol.doc("focus_bad_mode").set(validBotPayload(ALICE_UID, { inferenceMode: "INVALID_MODE" })));
  await assertFails(botsCol.doc("focus_future").set(validBotPayload(ALICE_UID, { createdAt: new Date(Date.now() + 60000) })));
  await assertFails(botsCol.doc("focus_long").set(validBotPayload(ALICE_UID, { name: "A".repeat(51) })));
  await assertFails(botsCol.doc("focus_spoof").set(validBotPayload(BOB_UID)));

  await assertSucceeds(botsCol.doc("focus_immut").set(validBotPayload(ALICE_UID)));
  await assertFails(
    botsCol.doc("focus_immut").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    botsCol.doc("focus_immut").update({
      userId: BOB_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Cross-user isolation: Bob cannot read, list, create, update, or delete Alice's bots", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).collection("bots").doc("focus").set(validBotPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceBots = bobDb.collection("users").doc(ALICE_UID).collection("bots");

  await assertFails(aliceBots.get());
  await assertFails(aliceBots.doc("focus").get());
  await assertFails(aliceBots.doc("focus").update({ active: false, updatedAt: new Date(Date.now() - 500) }));
  await assertFails(aliceBots.doc("focus").delete());
  await assertFails(aliceBots.doc("hijack").set(validBotPayload(BOB_UID)));
});

// --- CHAT HISTORY COLLECTION TESTS ---

test("Unauthenticated user: cannot read or write chatHistory", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const chatCol = unauthDb.collection("users").doc(ALICE_UID).collection("chatHistory");
  await assertFails(chatCol.get());
  await assertFails(chatCol.doc("msg_1").set(validChatPayload(ALICE_UID)));
});

test("Authenticated owner: valid Kotlin chatHistory create, ordered list query, get, and delete succeed; update is blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const chatCol = aliceDb.collection("users").doc(ALICE_UID).collection("chatHistory");

  // Exact Kotlin query on empty subcollection
  await assertSucceeds(chatCol.orderBy("createdAt", "asc").limitToLast(50).get());

  // Append USER message and ASSISTANT response
  await assertSucceeds(chatCol.doc("msg_1").set(validChatPayload(ALICE_UID)));
  await assertSucceeds(
    chatCol.doc("msg_2").set(
      validChatPayload(ALICE_UID, {
        role: "ASSISTANT",
        text: "Focus Mode — Screen Brightness, Do Not Disturb, Start 25m Timer",
        engineUsed: "CLOUD_FLASH",
      })
    )
  );

  // Ordered read & get
  await assertSucceeds(chatCol.doc("msg_1").get());
  await assertSucceeds(chatCol.orderBy("createdAt", "asc").limitToLast(50).get());

  // Update is forbidden (append-only)
  await assertFails(chatCol.doc("msg_1").update({ text: "Tampered" }));

  // Owner can clear/delete own messages
  await assertSucceeds(chatCol.doc("msg_1").delete());
  await assertSucceeds(chatCol.doc("msg_2").delete());
});

test("ChatHistory security bounds: reject ghost fields, @DocumentId id, invalid role/engine, future timestamp, oversized text, and spoofed userId", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const chatCol = aliceDb.collection("users").doc(ALICE_UID).collection("chatHistory");

  await assertFails(chatCol.doc("msg_id").set(validChatPayload(ALICE_UID, { id: "msg_id" })));
  await assertFails(chatCol.doc("msg_ghost").set(validChatPayload(ALICE_UID, { isAdmin: true })));
  await assertFails(chatCol.doc("msg_role").set(validChatPayload(ALICE_UID, { role: "SYSTEM" })));
  await assertFails(chatCol.doc("msg_engine").set(validChatPayload(ALICE_UID, { engineUsed: "UNKNOWN" })));
  await assertFails(chatCol.doc("msg_future").set(validChatPayload(ALICE_UID, { createdAt: new Date(Date.now() + 60000) })));
  await assertFails(chatCol.doc("msg_long").set(validChatPayload(ALICE_UID, { text: "X".repeat(2001) })));
  await assertFails(chatCol.doc("msg_empty").set(validChatPayload(ALICE_UID, { text: "" })));
  await assertFails(chatCol.doc("msg_spoof").set(validChatPayload(BOB_UID)));
});

test("Cross-user isolation: Bob cannot read, list, create, or delete Alice's chatHistory", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).collection("chatHistory").doc("msg_1").set(validChatPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceChat = bobDb.collection("users").doc(ALICE_UID).collection("chatHistory");

  await assertFails(aliceChat.orderBy("createdAt", "asc").limitToLast(50).get());
  await assertFails(aliceChat.doc("msg_1").get());
  await assertFails(aliceChat.doc("msg_1").delete());
  await assertFails(aliceChat.doc("msg_hijack").set(validChatPayload(BOB_UID)));
});
