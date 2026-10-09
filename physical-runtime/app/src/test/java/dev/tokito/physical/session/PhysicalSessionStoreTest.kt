package dev.tokito.physical.session

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhysicalSessionStoreTest {
    @Test fun projectContextCannotReuseUnboundOrStaleGlobalBoardEvidence() {
        assertTrue(PhysicalSessionContext().admitsBoardReference("local-reviewed-v1"))
        assertFalse(PhysicalSessionContext("project").admitsBoardReference("local-reviewed-v1"))
        assertFalse(PhysicalSessionContext("project", "design").admitsBoardReference("local-reviewed-v1"))
        assertFalse(PhysicalSessionContext("project", "design", "project-v2").admitsBoardReference("project-v1"))
        assertTrue(PhysicalSessionContext("project", "design", "project-v2").admitsBoardReference("project-v2"))
    }

    private lateinit var context: Context
    private lateinit var store: PhysicalSessionStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("tokito-physical-sessions.db")
        store = PhysicalSessionStore.open(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("tokito-physical-sessions.db")
    }

    @Test
    fun projectlessAndProjectBoundSessionsShareOneRevisionedModel() {
        val local = store.startSession(PhysicalSessionContext(), "local", 100)
        assertEquals(0, local.revision)
        assertNull(local.context.projectId)

        val project = store.startSession(
            PhysicalSessionContext("project", "design", "rev-7"),
            "project-bound",
            101,
        )
        assertEquals("project", project.context.projectId)
        assertEquals("design", project.context.designId)
        assertEquals("rev-7", project.context.contextRevision)
    }

    @Test
    fun appendIsAtomicAndRejectsStaleRevision() {
        store.startSession(PhysicalSessionContext(), "session", 100)
        val next = store.appendObservation(
            "session",
            0,
            ObservationDraft(
                id = "observation",
                kind = "measurement",
                source = "engineer_voice",
                payloadJson = "{\"volts\":3.3}",
                capturedAtUnixMs = 120,
                evidenceAvailability = "unavailable",
            ),
        )
        assertEquals(1, next)
        assertEquals(1L, store.session("session")?.revision)
        val conflict = assertThrows(SessionRevisionConflict::class.java) {
            store.appendObservation(
                "session",
                0,
                ObservationDraft(
                    id = "stale",
                    kind = "measurement",
                    source = "engineer_voice",
                    payloadJson = "{}",
                    capturedAtUnixMs = 121,
                    evidenceAvailability = "unavailable",
                ),
            )
        }
        assertEquals(1L, conflict.currentRevision)
    }

    @Test
    fun nothingSyncsUntilEachStructuredRecordIsExplicitlyAuthorized() {
        store.startSession(PhysicalSessionContext(), "session", 100)
        store.appendObservation(
            "session",
            0,
            ObservationDraft(
                id = "observation",
                kind = "visual_inspection",
                source = "local_vision",
                payloadJson = "{\"state\":\"present\"}",
                capturedAtUnixMs = 120,
                evidenceAvailability = "available",
                evidence = listOf(
                    EvidenceDraft(
                        id = "evidence",
                        contentUri = "content://tokito/evidence/1",
                        sha256 = "a".repeat(64),
                        mimeType = "image/jpeg",
                        byteCount = 42,
                        retention = "user_managed",
                    ),
                ),
            ),
        )
        assertEquals(0, store.pendingSyncCount())
        store.authorizeObservationSync("observation")
        assertEquals(1, store.pendingSyncCount())
        store.authorizeEvidenceSync("evidence")
        assertEquals(2, store.pendingSyncCount())
    }

    @Test
    fun cloudHandoffIsProjectBoundStructuredAndDurableUntilAcknowledged() {
        store.startSession(PhysicalSessionContext(), "local-only", 90)
        store.appendObservation(
            "local-only",
            0,
            ObservationDraft(
                id = "local-observation",
                kind = "measurement",
                source = "engineer_voice",
                payloadJson = "{\"volts\":5}",
                capturedAtUnixMs = 100,
                evidenceAvailability = "unavailable",
            ),
        )
        store.authorizeObservationSync("local-observation")

        store.startSession(
            PhysicalSessionContext("project", "design", "design-rev-2"),
            "project-session",
            110,
        )
        store.appendObservation(
            "project-session",
            0,
            ObservationDraft(
                id = "project-observation",
                kind = "visual_inspection",
                source = "local_vision",
                payloadJson = "{\"state\":\"present\"}",
                capturedAtUnixMs = 120,
                evidenceAvailability = "available",
                evidence = listOf(
                    EvidenceDraft(
                        id = "project-evidence",
                        contentUri = "content://tokito/private/frame-1",
                        sha256 = "c".repeat(64),
                        mimeType = "image/jpeg",
                        byteCount = 512,
                        retention = "user_managed",
                    ),
                ),
            ),
        )
        store.authorizeObservationSync("project-observation")
        store.authorizeEvidenceSync("project-evidence")

        val snapshots = store.pendingSyncSnapshots()
        assertEquals(1, snapshots.size)
        val snapshot = snapshots.single()
        assertEquals("project-session", snapshot.sessionId)
        assertEquals("project", snapshot.projectId)
        assertEquals("design", snapshot.designId)
        assertTrue(snapshot.payloadJson.contains("project-observation"))
        assertTrue(snapshot.payloadJson.contains("${"c".repeat(64)}"))
        assertFalse(snapshot.payloadJson.contains("content://"))

        // The projectless record remains local and the project snapshot stays
        // retryable until the Rust outbox acknowledges durable ownership.
        assertEquals(3, store.pendingSyncCount())
        store.markSyncEnqueued("project-session")
        assertEquals(1, store.pendingSyncCount())
        assertTrue(store.pendingSyncSnapshots().isEmpty())
    }

    @Test
    fun expiryAndDeletionRemovePendingUploads() {
        store.startSession(PhysicalSessionContext(), "session", 100)
        store.appendObservation(
            "session",
            0,
            ObservationDraft(
                id = "observation",
                kind = "visual_inspection",
                source = "local_vision",
                payloadJson = "{}",
                capturedAtUnixMs = 120,
                evidenceAvailability = "available",
                evidence = listOf(
                    EvidenceDraft(
                        id = "evidence",
                        contentUri = "content://tokito/evidence/1",
                        sha256 = "b".repeat(64),
                        mimeType = "image/jpeg",
                        byteCount = 42,
                        retention = "until_expiry",
                        expiresAtUnixMs = 200,
                    ),
                ),
            ),
        )
        store.authorizeEvidenceSync("evidence")
        assertEquals(1, store.pruneExpiredEvidence(200))
        assertEquals(0, store.pendingSyncCount())
        store.deleteSession("session", 300)
        assertNull(store.session("session"))
    }
}
