package dev.tokito.physical.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PhysicalSessionContext(
    val projectId: String? = null,
    val designId: String? = null,
    val contextRevision: String? = null,
) {
    init {
        require(projectId?.isNotBlank() != false) { "projectId cannot be blank" }
        require(designId?.isNotBlank() != false) { "designId cannot be blank" }
        require(contextRevision?.isNotBlank() != false) { "contextRevision cannot be blank" }
        require(designId == null || projectId != null) { "designId requires projectId" }
        require(contextRevision == null || designId != null) { "contextRevision requires designId" }
    }
}

data class PhysicalSessionRecord(
    val id: String,
    val context: PhysicalSessionContext,
    val revision: Long,
    val status: String,
    val startedAtUnixMs: Long,
    val endedAtUnixMs: Long?,
)

data class PhysicalSyncSnapshot(
    val sessionId: String,
    val projectId: String,
    val designId: String?,
    val revision: Long,
    val payloadJson: String,
)

data class EvidenceDraft(
    val id: String = UUID.randomUUID().toString(),
    val contentUri: String,
    val sha256: String,
    val mimeType: String,
    val byteCount: Long,
    val retention: String,
    val expiresAtUnixMs: Long? = null,
) {
    init {
        require(id.isNotBlank() && contentUri.isNotBlank() && mimeType.isNotBlank())
        require(SHA256.matches(sha256)) { "sha256 must be lowercase hexadecimal" }
        require(byteCount >= 0) { "byteCount cannot be negative" }
        require(retention in setOf("session", "until_expiry", "user_managed"))
        require(retention == "until_expiry" == (expiresAtUnixMs != null)) {
            "until_expiry evidence requires an expiry and other retention modes forbid one"
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class ObservationDraft(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val source: String,
    val payloadJson: String,
    val capturedAtUnixMs: Long,
    val evidenceAvailability: String,
    val evidence: List<EvidenceDraft> = emptyList(),
) {
    init {
        require(id.isNotBlank() && kind.isNotBlank() && source.isNotBlank())
        require(capturedAtUnixMs >= 0)
        require(evidenceAvailability in setOf("available", "redacted", "unavailable"))
        require(payloadJson.toByteArray().size <= MAX_PAYLOAD_BYTES) { "observation payload is too large" }
        JSONObject(payloadJson)
        require((evidenceAvailability == "available") == evidence.isNotEmpty()) {
            "available evidence must be present; unavailable/redacted evidence must not retain media"
        }
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
    }
}

class SessionRevisionConflict(val currentRevision: Long) :
    IllegalStateException("session changed (current revision $currentRevision)")

/**
 * Durable local source of truth for physical sessions and structured evidence.
 *
 * Raw camera/audio streams are never accepted. Synchronization is opt-in per
 * structured observation or evidence record and creates an auditable queue row.
 */
class PhysicalSessionStore private constructor(context: Context) : AutoCloseable {
    private val database = Database(context.applicationContext)

    @Synchronized
    fun startSession(
        context: PhysicalSessionContext,
        sessionId: String = UUID.randomUUID().toString(),
        nowUnixMs: Long = System.currentTimeMillis(),
    ): PhysicalSessionRecord {
        require(sessionId.isNotBlank())
        val values = ContentValues().apply {
            put("id", sessionId)
            putNullable("project_id", context.projectId)
            putNullable("design_id", context.designId)
            putNullable("context_revision", context.contextRevision)
            put("revision", 0L)
            put("status", "active")
            put("started_at_unix_ms", nowUnixMs)
        }
        database.writableDatabase.insertOrThrow("physical_sessions", null, values)
        return requireSession(sessionId)
    }

    @Synchronized
    fun session(sessionId: String): PhysicalSessionRecord? = readSession(sessionId)

    @Synchronized
    fun appendObservation(
        sessionId: String,
        expectedRevision: Long,
        draft: ObservationDraft,
    ): Long = transaction { db ->
        val session = readSession(sessionId, db) ?: error("session not found")
        if (session.revision != expectedRevision) throw SessionRevisionConflict(session.revision)
        check(session.status == "active") { "session is not active" }

        db.insertOrThrow("physical_observations", null, ContentValues().apply {
            put("id", draft.id)
            put("session_id", sessionId)
            put("session_revision", expectedRevision + 1)
            put("kind", draft.kind)
            put("source", draft.source)
            put("payload_json", draft.payloadJson)
            put("captured_at_unix_ms", draft.capturedAtUnixMs)
            put("evidence_availability", draft.evidenceAvailability)
            put("sync_authorized", 0)
        })
        draft.evidence.forEach { evidence ->
            db.insertOrThrow("physical_evidence", null, ContentValues().apply {
                put("id", evidence.id)
                put("observation_id", draft.id)
                put("content_uri", evidence.contentUri)
                put("sha256", evidence.sha256)
                put("mime_type", evidence.mimeType)
                put("byte_count", evidence.byteCount)
                put("retention", evidence.retention)
                putNullable("expires_at_unix_ms", evidence.expiresAtUnixMs)
                put("sync_authorized", 0)
            })
        }
        advanceRevision(db, sessionId, expectedRevision, expectedRevision + 1)
        expectedRevision + 1
    }

    @Synchronized
    fun finishSession(
        sessionId: String,
        expectedRevision: Long,
        nowUnixMs: Long = System.currentTimeMillis(),
    ): Long = transaction { db ->
        val session = readSession(sessionId, db) ?: error("session not found")
        if (session.revision != expectedRevision) throw SessionRevisionConflict(session.revision)
        check(session.status == "active") { "session is not active" }
        val next = expectedRevision + 1
        val updated = db.update(
            "physical_sessions",
            ContentValues().apply {
                put("status", "finished")
                put("ended_at_unix_ms", nowUnixMs)
                put("revision", next)
            },
            "id = ? AND revision = ?",
            arrayOf(sessionId, expectedRevision.toString()),
        )
        if (updated != 1) throw SessionRevisionConflict(requireSession(sessionId, db).revision)
        next
    }

    @Synchronized
    fun authorizeObservationSync(observationId: String) = transaction { db ->
        authorize(db, "physical_observations", observationId, "observation")
    }

    @Synchronized
    fun authorizeEvidenceSync(evidenceId: String) = transaction { db ->
        authorize(db, "physical_evidence", evidenceId, "evidence")
    }

    @Synchronized
    fun pendingSyncCount(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM physical_sync_queue WHERE state = 'pending'",
        emptyArray(),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    /**
     * Returns complete structured snapshots for explicitly authorized records.
     * Projectless sessions intentionally remain local because Cloud resources
     * require a project owner. Evidence contains metadata only, never bytes.
     */
    @Synchronized
    fun pendingSyncSnapshots(): List<PhysicalSyncSnapshot> {
        val db = database.readableDatabase
        val sessions = mutableListOf<PhysicalSyncSnapshot>()
        db.rawQuery(
            """
            SELECT DISTINCT s.id, s.project_id, s.design_id, s.context_revision,
                s.revision, s.status, s.started_at_unix_ms, s.ended_at_unix_ms
            FROM physical_sessions s
            JOIN physical_sync_queue q ON q.session_id = s.id
            WHERE q.state = 'pending' AND s.project_id IS NOT NULL
                AND s.deleted_at_unix_ms IS NULL
            ORDER BY s.started_at_unix_ms, s.id
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sessionId = cursor.getString(0)
                val observations = JSONArray()
                db.rawQuery(
                    """
                    SELECT id, session_revision, kind, source, payload_json,
                        captured_at_unix_ms, evidence_availability
                    FROM physical_observations
                    WHERE session_id = ? AND sync_authorized = 1
                        AND deleted_at_unix_ms IS NULL
                    ORDER BY session_revision, id
                    """.trimIndent(),
                    arrayOf(sessionId),
                ).use { observationCursor ->
                    while (observationCursor.moveToNext()) {
                        val observationId = observationCursor.getString(0)
                        val evidence = JSONArray()
                        db.rawQuery(
                            """
                            SELECT id, sha256, mime_type, byte_count, retention,
                                expires_at_unix_ms
                            FROM physical_evidence
                            WHERE observation_id = ? AND sync_authorized = 1
                                AND deleted_at_unix_ms IS NULL
                            ORDER BY id
                            """.trimIndent(),
                            arrayOf(observationId),
                        ).use { evidenceCursor ->
                            while (evidenceCursor.moveToNext()) {
                                evidence.put(JSONObject().apply {
                                    put("id", evidenceCursor.getString(0))
                                    put("sha256", evidenceCursor.getString(1))
                                    put("mime_type", evidenceCursor.getString(2))
                                    put("byte_count", evidenceCursor.getLong(3))
                                    put("retention", evidenceCursor.getString(4))
                                    putNullableJson("expires_at_unix_ms", evidenceCursor.nullableLong(5))
                                })
                            }
                        }
                        observations.put(JSONObject().apply {
                            put("id", observationId)
                            put("session_revision", observationCursor.getLong(1))
                            put("kind", observationCursor.getString(2))
                            put("source", observationCursor.getString(3))
                            put("payload", JSONObject(observationCursor.getString(4)))
                            put("captured_at_unix_ms", observationCursor.getLong(5))
                            put("evidence_availability", observationCursor.getString(6))
                            put("evidence", evidence)
                        })
                    }
                }
                val payload = JSONObject().apply {
                    put("schema_version", 1)
                    put("id", sessionId)
                    putNullableJson("context_revision", cursor.nullableString(3))
                    put("revision", cursor.getLong(4))
                    put("status", cursor.getString(5))
                    put("started_at_unix_ms", cursor.getLong(6))
                    putNullableJson("ended_at_unix_ms", cursor.nullableLong(7))
                    put("observations", observations)
                }
                sessions += PhysicalSyncSnapshot(
                    sessionId = sessionId,
                    projectId = cursor.getString(1),
                    designId = cursor.nullableString(2),
                    revision = cursor.getLong(4),
                    payloadJson = payload.toString(),
                )
            }
        }
        return sessions
    }

    /** Removes only the native handoff row after Rust has durably journaled it. */
    @Synchronized
    fun markSyncEnqueued(sessionId: String) = transaction { db ->
        db.delete(
            "physical_sync_queue",
            "session_id = ? AND state = 'pending'",
            arrayOf(sessionId),
        )
    }

    @Synchronized
    fun deleteSession(sessionId: String, nowUnixMs: Long = System.currentTimeMillis()) = transaction { db ->
        val session = readSession(sessionId, db) ?: return@transaction
        db.execSQL(
            "UPDATE physical_evidence SET deleted_at_unix_ms = ? WHERE observation_id IN " +
                "(SELECT id FROM physical_observations WHERE session_id = ?)",
            arrayOf<Any>(nowUnixMs, sessionId),
        )
        db.execSQL(
            "UPDATE physical_observations SET deleted_at_unix_ms = ? WHERE session_id = ?",
            arrayOf<Any>(nowUnixMs, sessionId),
        )
        db.execSQL(
            "UPDATE physical_sessions SET status = 'deleted', deleted_at_unix_ms = ?, revision = ? WHERE id = ?",
            arrayOf<Any>(nowUnixMs, session.revision + 1, sessionId),
        )
        db.delete("physical_sync_queue", "session_id = ?", arrayOf(sessionId))
    }

    @Synchronized
    fun pruneExpiredEvidence(nowUnixMs: Long = System.currentTimeMillis()): Int = transaction { db ->
        val ids = mutableListOf<String>()
        db.query(
            "physical_evidence",
            arrayOf("id"),
            "retention = 'until_expiry' AND expires_at_unix_ms <= ? AND deleted_at_unix_ms IS NULL",
            arrayOf(nowUnixMs.toString()),
            null,
            null,
            null,
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        ids.forEach { id ->
            db.update(
                "physical_evidence",
                ContentValues().apply { put("deleted_at_unix_ms", nowUnixMs) },
                "id = ?",
                arrayOf(id),
            )
            db.delete("physical_sync_queue", "record_kind = 'evidence' AND record_id = ?", arrayOf(id))
        }
        ids.size
    }

    override fun close() = database.close()

    private fun authorize(db: SQLiteDatabase, table: String, id: String, kind: String) {
        require(id.isNotBlank())
        val sessionId = db.rawQuery(
            if (kind == "observation") {
                "SELECT session_id FROM physical_observations WHERE id = ? AND deleted_at_unix_ms IS NULL"
            } else {
                "SELECT o.session_id FROM physical_evidence e JOIN physical_observations o ON o.id = e.observation_id " +
                    "WHERE e.id = ? AND e.deleted_at_unix_ms IS NULL AND o.deleted_at_unix_ms IS NULL"
            },
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else error("record not found") }
        db.update(table, ContentValues().apply { put("sync_authorized", 1) }, "id = ?", arrayOf(id))
        db.insertWithOnConflict(
            "physical_sync_queue",
            null,
            ContentValues().apply {
                put("record_kind", kind)
                put("record_id", id)
                put("session_id", sessionId)
                put("state", "pending")
                put("created_at_unix_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun advanceRevision(db: SQLiteDatabase, id: String, expected: Long, next: Long) {
        if (db.update(
                "physical_sessions",
                ContentValues().apply { put("revision", next) },
                "id = ? AND revision = ?",
                arrayOf(id, expected.toString()),
            ) != 1
        ) throw SessionRevisionConflict(requireSession(id, db).revision)
    }

    private fun requireSession(id: String, db: SQLiteDatabase = database.readableDatabase) =
        readSession(id, db) ?: error("session not found")

    private fun readSession(id: String, db: SQLiteDatabase = database.readableDatabase): PhysicalSessionRecord? =
        db.query(
            "physical_sessions",
            arrayOf(
                "id", "project_id", "design_id", "context_revision", "revision", "status",
                "started_at_unix_ms", "ended_at_unix_ms",
            ),
            "id = ? AND deleted_at_unix_ms IS NULL",
            arrayOf(id),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            PhysicalSessionRecord(
                id = cursor.getString(0),
                context = PhysicalSessionContext(
                    projectId = cursor.nullableString(1),
                    designId = cursor.nullableString(2),
                    contextRevision = cursor.nullableString(3),
                ),
                revision = cursor.getLong(4),
                status = cursor.getString(5),
                startedAtUnixMs = cursor.getLong(6),
                endedAtUnixMs = cursor.nullableLong(7),
            )
        }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE physical_sessions (
                    id TEXT PRIMARY KEY NOT NULL,
                    project_id TEXT,
                    design_id TEXT,
                    context_revision TEXT,
                    revision INTEGER NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('active','finished','deleted')),
                    started_at_unix_ms INTEGER NOT NULL,
                    ended_at_unix_ms INTEGER,
                    deleted_at_unix_ms INTEGER,
                    CHECK(design_id IS NULL OR project_id IS NOT NULL),
                    CHECK(context_revision IS NULL OR design_id IS NOT NULL)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE physical_observations (
                    id TEXT PRIMARY KEY NOT NULL,
                    session_id TEXT NOT NULL REFERENCES physical_sessions(id) ON DELETE CASCADE,
                    session_revision INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    source TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    captured_at_unix_ms INTEGER NOT NULL,
                    evidence_availability TEXT NOT NULL CHECK(evidence_availability IN ('available','redacted','unavailable')),
                    sync_authorized INTEGER NOT NULL DEFAULT 0 CHECK(sync_authorized IN (0,1)),
                    deleted_at_unix_ms INTEGER
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE physical_evidence (
                    id TEXT PRIMARY KEY NOT NULL,
                    observation_id TEXT NOT NULL REFERENCES physical_observations(id) ON DELETE CASCADE,
                    content_uri TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    byte_count INTEGER NOT NULL CHECK(byte_count >= 0),
                    retention TEXT NOT NULL CHECK(retention IN ('session','until_expiry','user_managed')),
                    expires_at_unix_ms INTEGER,
                    sync_authorized INTEGER NOT NULL DEFAULT 0 CHECK(sync_authorized IN (0,1)),
                    deleted_at_unix_ms INTEGER,
                    CHECK((retention = 'until_expiry') = (expires_at_unix_ms IS NOT NULL))
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE physical_sync_queue (
                    record_kind TEXT NOT NULL CHECK(record_kind IN ('observation','evidence')),
                    record_id TEXT NOT NULL,
                    session_id TEXT NOT NULL REFERENCES physical_sessions(id) ON DELETE CASCADE,
                    state TEXT NOT NULL CHECK(state IN ('pending','uploading','conflict')),
                    created_at_unix_ms INTEGER NOT NULL,
                    PRIMARY KEY(record_kind, record_id)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX physical_observations_session_idx ON physical_observations(session_id, session_revision)")
            db.execSQL("CREATE INDEX physical_evidence_observation_idx ON physical_evidence(observation_id)")
            db.execSQL("CREATE INDEX physical_sync_queue_session_idx ON physical_sync_queue(session_id, state)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No destructive migration exists for physical session storage ($oldVersion -> $newVersion)")
        }
    }

    companion object {
        private const val DATABASE_NAME = "tokito-physical-sessions.db"
        private const val VERSION = 1

        fun open(context: Context): PhysicalSessionStore = PhysicalSessionStore(context)
    }
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun android.database.Cursor.nullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun android.database.Cursor.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun JSONObject.putNullableJson(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}
