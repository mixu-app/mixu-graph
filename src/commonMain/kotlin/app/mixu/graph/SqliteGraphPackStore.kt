// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Maximum `graph_schema_version` supported by this version of the Mixu graph engine.
 */
const val MAX_SUPPORTED_GRAPH_SCHEMA_VERSION: Int = 1

private const val SQLITE_HEADER_MIN_BYTES: Long = 100L
private const val SQLITE_PAGE_SIZE_OFFSET: Int = 16
private const val SQLITE_PAGE_COUNT_OFFSET: Int = 28
private const val SQLITE_MAX_PAGE_SIZE_MARKER: Int = 1
private const val SQLITE_MAX_PAGE_SIZE: Int = 65_536
private const val SQLITE_MIN_PAGE_SIZE: Int = 512
private const val BYTE_MASK: Int = 0xFF
private const val LONG_BYTE_MASK: Long = 0xFFL
private const val SHIFT_8: Int = 8
private const val SHIFT_16: Int = 16
private const val SHIFT_24: Int = 24
private const val QUERY_SPLIT_MARKER: String = "===MIXU_QUERY_SPLIT==="

private val SQLITE_MAGIC_BYTES: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

private val REQUIRED_PACK_TABLES: Set<String> =
    setOf(
        "pack_metadata",
        "reveal_anchors",
        "entities",
        "entity_names",
        "evidence_anchors",
        "events",
        "event_participants",
        "event_evidence",
        "assertions",
        "assertion_qualifiers",
        "assertion_transitions",
        "identity_links",
    )

/**
 * Typed failure modes when verifying or loading a SQLite narrative graph pack.
 */
sealed interface GraphPackLoadError {
    data class FileNotFound(
        val path: String,
    ) : GraphPackLoadError

    data class TruncatedOrEmptyPack(
        val byteSize: Long,
        val reason: String,
    ) : GraphPackLoadError

    data class CorruptSqliteFormat(
        val reason: String,
    ) : GraphPackLoadError

    data class ChecksumMismatch(
        val expectedSha256: String,
        val actualSha256: String,
    ) : GraphPackLoadError

    data class UnsupportedSchemaVersion(
        val schemaVersion: Int,
        val maxSupportedVersion: Int,
    ) : GraphPackLoadError

    data class ReadOnlyViolation(
        val reason: String,
    ) : GraphPackLoadError

    data class InvalidPackContent(
        val reason: String,
    ) : GraphPackLoadError
}

/**
 * Result of verifying and loading a SQLite graph pack into memory.
 */
sealed interface GraphPackLoadResult {
    data class Success(
        val pack: GraphPack,
        val events: List<Event> = emptyList(),
        val evidenceAnchors: List<EvidenceAnchor> = emptyList(),
        val identityLinks: List<IdentityLink> = emptyList(),
        val verifiedSha256: String,
    ) : GraphPackLoadResult

    data class Failure(
        val error: GraphPackLoadError,
    ) : GraphPackLoadResult
}

/**
 * Result of opening a verified read-only SQLite graph pack database handle.
 */
sealed interface GraphPackDatabaseOpenResult {
    data class Success(
        val database: ReadOnlyGraphPackDatabase,
    ) : GraphPackDatabaseOpenResult

    data class Failure(
        val error: GraphPackLoadError,
    ) : GraphPackDatabaseOpenResult
}

/**
 * Metadata returned when writing a graph pack to a SQLite container file.
 */
data class WrittenGraphPack(
    val sqlitePath: Path,
    val manifestPath: Path,
    val sha256: String,
    val manifest: GraphPackManifest,
)

/**
 * Read-only query session over a checksum-verified SQLite graph pack file.
 *
 * Every query is executed in SQLite `-readonly` mode with `PRAGMA query_only = ON`.
 * Any mutating statement is rejected with [GraphPackLoadError.ReadOnlyViolation].
 */
class ReadOnlyGraphPackDatabase internal constructor(
    val sqlitePath: Path,
    val verifiedSha256: String,
) {
    /**
     * Loads and validates the complete [GraphPack] along with events, evidence anchors, and identity links.
     */
    fun loadGraphPack(): GraphPackLoadResult =
        GraphPackSqliteContainer.loadFromVerifiedDatabase(
            sqlitePath = sqlitePath,
            verifiedSha256 = verifiedSha256,
        )

    /**
     * Returns the total number of entities stored in the SQLite pack.
     */
    fun entityCount(): Int {
        ensureUnmodified()
        val rows =
            GraphPackSqliteContainer.querySingleJsonArray<CountRow>(
                sqlitePath = sqlitePath,
                sql = "SELECT COUNT(*) AS entity_count FROM entities;",
            )
        return rows.firstOrNull()?.entityCount ?: 0
    }

    /**
     * Queries entities disclosed at or before [maxOrdinal] directly via SQL.
     */
    fun queryEntitiesAtReveal(maxOrdinal: Int): List<Entity> {
        require(maxOrdinal >= 0) { "maxOrdinal must be non-negative" }
        ensureUnmodified()
        val rows =
            GraphPackSqliteContainer.querySingleJsonArray<EntityRow>(
                sqlitePath = sqlitePath,
                sql =
                    """
                    SELECT entity_id, entity_type, first_reveal, canonical_name_id, notes
                    FROM entities
                    WHERE first_reveal <= $maxOrdinal
                    ORDER BY first_reveal ASC, entity_id ASC;
                    """.trimIndent(),
            )
        return rows.map { it.toDomain() }.sortedWith(Entity.STABLE_COMPARATOR)
    }

    /**
     * Queries entity names disclosed at or before [maxOrdinal] directly via SQL.
     */
    fun queryEntityNamesAtReveal(maxOrdinal: Int): List<EntityName> {
        require(maxOrdinal >= 0) { "maxOrdinal must be non-negative" }
        ensureUnmodified()
        val rows =
            GraphPackSqliteContainer.querySingleJsonArray<EntityNameRow>(
                sqlitePath = sqlitePath,
                sql =
                    """
                    SELECT name_id, entity_id, display_text, first_reveal, name_kind, evidence_anchor_id
                    FROM entity_names
                    WHERE first_reveal <= $maxOrdinal
                    ORDER BY first_reveal ASC, name_id ASC;
                    """.trimIndent(),
            )
        return rows.map { it.toDomain() }.sortedWith(EntityName.STABLE_COMPARATOR)
    }

    /**
     * Queries assertions disclosed at or before [maxOrdinal] whose subject and entity-object endpoints
     * are also disclosed at or before [maxOrdinal].
     */
    fun queryAssertionsAtReveal(maxOrdinal: Int): List<Assertion> {
        require(maxOrdinal >= 0) { "maxOrdinal must be non-negative" }
        ensureUnmodified()
        val assertionSql =
            """
            SELECT a.*
            FROM assertions a
            JOIN entities subj ON subj.entity_id = a.subject_id
            LEFT JOIN entities obj ON a.object_kind = 'ENTITY' AND obj.entity_id = a.object_entity_id
            WHERE a.first_reveal <= $maxOrdinal
              AND subj.first_reveal <= $maxOrdinal
              AND (a.object_kind != 'ENTITY' OR obj.first_reveal <= $maxOrdinal)
            ORDER BY a.first_reveal ASC, a.assertion_id ASC;
            """.trimIndent()
        val qualifierSql =
            """
            SELECT q.assertion_id, q.qualifier_key, q.qualifier_value
            FROM assertion_qualifiers q
            JOIN assertions a ON a.assertion_id = q.assertion_id
            WHERE a.first_reveal <= $maxOrdinal
            ORDER BY q.assertion_id ASC, q.qualifier_key ASC;
            """.trimIndent()
        val segments =
            GraphPackSqliteContainer.executeReadOnlyBatchQueries(
                sqlitePath = sqlitePath,
                queries = listOf(assertionSql, qualifierSql),
            )
        val assertionRows = GraphPackSqliteContainer.decodeRows<AssertionRow>(segments[0])
        val qualifierRows = GraphPackSqliteContainer.decodeRows<AssertionQualifierRow>(segments[1])
        val qualifiersByAssertion =
            qualifierRows
                .groupBy { it.assertionId }
                .mapValues { (_, list) -> list.associate { it.qualifierKey to it.qualifierValue } }
        return assertionRows
            .map { row -> row.toDomain(qualifiersByAssertion[row.assertionId] ?: emptyMap()) }
            .sortedWith(Assertion.STABLE_COMPARATOR)
    }

    /**
     * Queries assertion transitions disclosed at or before [maxOrdinal] directly via SQL.
     */
    fun queryTransitionsAtReveal(maxOrdinal: Int): List<AssertionTransition> {
        require(maxOrdinal >= 0) { "maxOrdinal must be non-negative" }
        ensureUnmodified()
        val rows =
            GraphPackSqliteContainer.querySingleJsonArray<AssertionTransitionRow>(
                sqlitePath = sqlitePath,
                sql =
                    """
                    SELECT transition_id, assertion_id, reveal_ordinal, status, evidence_anchor_id, reason
                    FROM assertion_transitions
                    WHERE reveal_ordinal <= $maxOrdinal
                    ORDER BY reveal_ordinal ASC, transition_id ASC;
                    """.trimIndent(),
            )
        return rows.map { it.toDomain() }.sortedWith(AssertionTransition.STABLE_COMPARATOR)
    }

    /**
     * Queries identity links disclosed at or before [maxOrdinal] directly via SQL.
     */
    fun queryIdentityLinksAtReveal(maxOrdinal: Int): List<IdentityLink> {
        require(maxOrdinal >= 0) { "maxOrdinal must be non-negative" }
        ensureUnmodified()
        val rows =
            GraphPackSqliteContainer.querySingleJsonArray<IdentityLinkRow>(
                sqlitePath = sqlitePath,
                sql =
                    """
                    SELECT link_id, left_entity_id, right_entity_id, kind, first_reveal, evidence_anchor_id
                    FROM identity_links
                    WHERE first_reveal <= $maxOrdinal
                    ORDER BY first_reveal ASC, link_id ASC;
                    """.trimIndent(),
            )
        return rows.map { it.toDomain() }.sortedWith(IdentityLink.STABLE_COMPARATOR)
    }

    /**
     * Executes raw SQL against the read-only SQLite connection.
     * Any write or schema mutation attempt is rejected and returned as [GraphPackLoadError.ReadOnlyViolation].
     */
    fun executeSql(sql: String): GraphPackLoadError? {
        val trimmed = sql.trim()
        if (isWriteStatement(trimmed)) {
            // Also run against sqlite3 -readonly to confirm engine-level enforcement.
            val outcome =
                GraphPackSqliteContainer.runSqliteProcess(
                    sqlitePath = sqlitePath,
                    readOnly = true,
                    jsonMode = false,
                    sqlInput = "PRAGMA query_only = ON;\n$trimmed;\n",
                )
            val detail =
                outcome.stderr.ifBlank {
                    "Mutating SQL statements are forbidden on a read-only graph pack"
                }
            return GraphPackLoadError.ReadOnlyViolation(detail.trim())
        }

        val outcome =
            GraphPackSqliteContainer.runSqliteProcess(
                sqlitePath = sqlitePath,
                readOnly = true,
                jsonMode = false,
                sqlInput = "PRAGMA query_only = ON;\n$trimmed;\n",
            )
        if (outcome.exitCode != 0) {
            val message = outcome.stderr.ifBlank { outcome.stdout }.trim()
            return if (message.contains("readonly", ignoreCase = true) ||
                message.contains("read-only", ignoreCase = true) ||
                message.contains("attempt to write", ignoreCase = true)
            ) {
                GraphPackLoadError.ReadOnlyViolation(message)
            } else {
                GraphPackLoadError.CorruptSqliteFormat(message)
            }
        }
        return null
    }

    private fun ensureUnmodified() {
        val currentSha = GraphPackSqliteContainer.computeSha256(sqlitePath)
        check(currentSha.equals(verifiedSha256, ignoreCase = true)) {
            "SQLite graph pack was modified after read-only verification"
        }
    }

    private fun isWriteStatement(sql: String): Boolean {
        val normalized = sql.trimStart().uppercase()
        val forbiddenPrefixes =
            listOf(
                "INSERT ",
                "UPDATE ",
                "DELETE ",
                "DROP ",
                "CREATE ",
                "ALTER ",
                "REPLACE ",
                "VACUUM",
                "ATTACH ",
                "DETACH ",
                "REINDEX",
            )
        return forbiddenPrefixes.any { normalized.startsWith(it) }
    }
}

/**
 * Read-only loader entry point for SQLite graph packs.
 */
object GraphPackSqliteLoader {
    fun load(
        sqlitePath: Path,
        expectedSha256: String? = null,
    ): GraphPackLoadResult = GraphPackSqliteContainer.load(sqlitePath, expectedSha256)

    fun openReadOnly(
        sqlitePath: Path,
        expectedSha256: String? = null,
    ): GraphPackDatabaseOpenResult =
        GraphPackSqliteContainer.openReadOnly(sqlitePath, expectedSha256)
}

/**
 * Writer and read-only verifier for SQLite narrative graph packs.
 */
object GraphPackSqliteContainer {
    /**
     * Computes the lowercase 64-character hex SHA-256 digest of a file.
     */
    fun computeSha256(path: Path): String = computeSha256(Files.readAllBytes(path))

    /**
     * Computes the lowercase 64-character hex SHA-256 digest of a [ByteArray].
     */
    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                val unsigned = b.toInt() and BYTE_MASK
                append(unsigned.toString(SHIFT_16).padStart(2, '0'))
            }
        }
    }

    /**
     * Serializes a [GraphPack] (plus optional events, evidence anchors, and identity links)
     * into a deterministic SQLite 3 database file and writes a companion `<file>.manifest.json`.
     */
    fun writePack(
        sqlitePath: Path,
        pack: GraphPack,
        events: List<Event> = emptyList(),
        evidenceAnchors: List<EvidenceAnchor> = emptyList(),
        identityLinks: List<IdentityLink> = emptyList(),
        schemaVersionOverride: Int? = null,
    ): WrittenGraphPack {
        val canonicalPack = pack.canonical()
        val sortedEvents = events.sortedWith(Event.STABLE_COMPARATOR)
        val sortedEvidence = evidenceAnchors.sortedWith(EvidenceAnchor.STABLE_COMPARATOR)
        val sortedLinks = identityLinks.sortedWith(IdentityLink.STABLE_COMPARATOR)

        sqlitePath.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(sqlitePath)

        val effectiveSchemaVersion =
            schemaVersionOverride ?: canonicalPack.manifest.graphSchemaVersion
        val sqlScript =
            buildWriteSqlScript(
                pack = canonicalPack,
                events = sortedEvents,
                evidenceAnchors = sortedEvidence,
                identityLinks = sortedLinks,
                effectiveSchemaVersion = effectiveSchemaVersion,
            )

        val outcome =
            runSqliteProcess(
                sqlitePath = sqlitePath,
                readOnly = false,
                jsonMode = false,
                sqlInput = sqlScript,
            )
        check(outcome.exitCode == 0) {
            "Failed to build SQLite graph pack at $sqlitePath: ${outcome.stderr.ifBlank {
                outcome.stdout
            }.trim()}"
        }

        val sha256 = computeSha256(sqlitePath)
        val finalizedManifest = canonicalPack.manifest.copy(checksumSha256 = sha256)
        val manifestPath = companionManifestPath(sqlitePath)
        Files.writeString(manifestPath, GraphJson.encodeToString(finalizedManifest))

        return WrittenGraphPack(
            sqlitePath = sqlitePath,
            manifestPath = manifestPath,
            sha256 = sha256,
            manifest = finalizedManifest,
        )
    }

    /**
     * Verifies a `.sqlite` graph pack file and opens a [ReadOnlyGraphPackDatabase].
     *
     * Verification order (enforced before any table data is read):
     * 1. File existence and minimum 100-byte SQLite header check.
     * 2. 16-byte `SQLite format 3\u0000` magic header check.
     * 3. Full-file SHA-256 verification against [expectedSha256] (or companion manifest).
     * 4. Header page-size / page-count truncation check and `PRAGMA integrity_check` in `-readonly` mode.
     * 5. `graph_schema_version` compatibility check (`<= MAX_SUPPORTED_GRAPH_SCHEMA_VERSION`).
     */
    fun openReadOnly(
        sqlitePath: Path,
        expectedSha256: String? = null,
    ): GraphPackDatabaseOpenResult {
        if (!Files.exists(sqlitePath) || !Files.isRegularFile(sqlitePath)) {
            return GraphPackDatabaseOpenResult.Failure(
                GraphPackLoadError.FileNotFound(sqlitePath.toString()),
            )
        }

        val bytes =
            try {
                Files.readAllBytes(sqlitePath)
            } catch (e: Exception) {
                return GraphPackDatabaseOpenResult.Failure(
                    GraphPackLoadError.FileNotFound("$sqlitePath: ${e.message}"),
                )
            }

        // 1. Minimum SQLite header size check (100 bytes)
        if (bytes.size.toLong() < SQLITE_HEADER_MIN_BYTES) {
            return GraphPackDatabaseOpenResult.Failure(
                GraphPackLoadError.TruncatedOrEmptyPack(
                    byteSize = bytes.size.toLong(),
                    reason =
                        "File is smaller than the 100-byte SQLite header (${bytes.size} bytes)",
                ),
            )
        }

        // 2. Magic header check ("SQLite format 3\u0000")
        for (i in SQLITE_MAGIC_BYTES.indices) {
            if (bytes[i] != SQLITE_MAGIC_BYTES[i]) {
                return GraphPackDatabaseOpenResult.Failure(
                    GraphPackLoadError.CorruptSqliteFormat(
                        reason = "Invalid SQLite file header magic at byte offset $i",
                    ),
                )
            }
        }

        // 3. Full SHA-256 verification BEFORE any SQLite engine call or query execution
        val actualSha256 = computeSha256(bytes)
        val resolvedExpectedSha = expectedSha256 ?: readCompanionManifestSha256(sqlitePath)
        if (resolvedExpectedSha != null &&
            !actualSha256.equals(resolvedExpectedSha, ignoreCase = true)
        ) {
            return GraphPackDatabaseOpenResult.Failure(
                GraphPackLoadError.ChecksumMismatch(
                    expectedSha256 = resolvedExpectedSha.lowercase(),
                    actualSha256 = actualSha256,
                ),
            )
        }

        // 4. SQLite header page geometry and read-only integrity check
        val headerFailure = verifySqliteHeaderGeometry(bytes)
        if (headerFailure != null) {
            return GraphPackDatabaseOpenResult.Failure(headerFailure)
        }

        val integrityFailure = verifyReadOnlyIntegrityAndSchema(sqlitePath, bytes.size.toLong())
        if (integrityFailure != null) {
            return GraphPackDatabaseOpenResult.Failure(integrityFailure)
        }

        return GraphPackDatabaseOpenResult.Success(
            ReadOnlyGraphPackDatabase(
                sqlitePath = sqlitePath,
                verifiedSha256 = actualSha256,
            ),
        )
    }

    /**
     * Verifies a `.sqlite` graph pack file and loads its full contents in read-only mode.
     */
    fun load(
        sqlitePath: Path,
        expectedSha256: String? = null,
    ): GraphPackLoadResult =
        when (val opened = openReadOnly(sqlitePath, expectedSha256)) {
            is GraphPackDatabaseOpenResult.Failure -> GraphPackLoadResult.Failure(opened.error)
            is GraphPackDatabaseOpenResult.Success -> opened.database.loadGraphPack()
        }

    internal fun loadFromVerifiedDatabase(
        sqlitePath: Path,
        verifiedSha256: String,
    ): GraphPackLoadResult {
        val queries =
            listOf(
                "SELECT * FROM pack_metadata WHERE id = 1;",
                "SELECT * FROM reveal_anchors ORDER BY ordinal ASC;",
                "SELECT * FROM entities ORDER BY first_reveal ASC, entity_id ASC;",
                "SELECT * FROM entity_names ORDER BY first_reveal ASC, name_id ASC;",
                "SELECT * FROM evidence_anchors ORDER BY reveal_ordinal ASC, evidence_id ASC;",
                "SELECT * FROM events ORDER BY first_reveal ASC, event_id ASC;",
                "SELECT * FROM event_participants ORDER BY event_id ASC, position ASC;",
                "SELECT * FROM event_evidence ORDER BY event_id ASC, position ASC;",
                "SELECT * FROM assertions ORDER BY first_reveal ASC, assertion_id ASC;",
                "SELECT * FROM assertion_qualifiers ORDER BY assertion_id ASC, qualifier_key ASC;",
                "SELECT * FROM assertion_transitions " +
                    "ORDER BY reveal_ordinal ASC, transition_id ASC;",
                "SELECT * FROM identity_links ORDER BY first_reveal ASC, link_id ASC;",
            )

        val segments =
            try {
                executeReadOnlyBatchQueries(sqlitePath, queries)
            } catch (e: SqliteExecutionException) {
                return GraphPackLoadResult.Failure(e.toLoadError())
            }

        return try {
            val metadataRows = decodeRows<PackMetadataRow>(segments[0])
            val metadataRow =
                metadataRows.firstOrNull()
                    ?: return GraphPackLoadResult.Failure(
                        GraphPackLoadError.InvalidPackContent("pack_metadata table is empty"),
                    )

            if (metadataRow.graphSchemaVersion < 1) {
                return GraphPackLoadResult.Failure(
                    GraphPackLoadError.InvalidPackContent(
                        "Invalid graph_schema_version: ${metadataRow.graphSchemaVersion}",
                    ),
                )
            }
            if (metadataRow.graphSchemaVersion > MAX_SUPPORTED_GRAPH_SCHEMA_VERSION) {
                return GraphPackLoadResult.Failure(
                    GraphPackLoadError.UnsupportedSchemaVersion(
                        schemaVersion = metadataRow.graphSchemaVersion,
                        maxSupportedVersion = MAX_SUPPORTED_GRAPH_SCHEMA_VERSION,
                    ),
                )
            }

            val anchorRows = decodeRows<RevealAnchorRow>(segments[1])
            val entityRows = decodeRows<EntityRow>(segments[2])
            val nameRows = decodeRows<EntityNameRow>(segments[3])
            val evidenceRows = decodeRows<EvidenceAnchorRow>(segments[4])
            val eventRows = decodeRows<EventRow>(segments[5])
            val participantRows = decodeRows<EventParticipantRow>(segments[6])
            val eventEvidenceRows = decodeRows<EventEvidenceRow>(segments[7])
            val assertionRows = decodeRows<AssertionRow>(segments[8])
            val qualifierRows = decodeRows<AssertionQualifierRow>(segments[9])
            val transitionRows = decodeRows<AssertionTransitionRow>(segments[10])
            val identityLinkRows = decodeRows<IdentityLinkRow>(segments[11])

            val participantsByEvent =
                participantRows
                    .groupBy { it.eventId }
                    .mapValues { (_, list) ->
                        list.sortedBy { it.position }.map { EntityId(it.entityId) }
                    }
            val evidenceByEvent =
                eventEvidenceRows
                    .groupBy { it.eventId }
                    .mapValues { (_, list) ->
                        list.sortedBy { it.position }.map { EvidenceAnchorId(it.evidenceAnchorId) }
                    }
            val qualifiersByAssertion =
                qualifierRows
                    .groupBy { it.assertionId }
                    .mapValues { (_, list) ->
                        list.associate { it.qualifierKey to it.qualifierValue }
                    }

            val manifest = metadataRow.toDomain(verifiedSha256)
            val anchors = anchorRows.map { it.toDomain() }
            val entities = entityRows.map { it.toDomain() }
            val names = nameRows.map { it.toDomain() }
            val evidenceAnchors =
                evidenceRows
                    .map { it.toDomain() }
                    .sortedWith(EvidenceAnchor.STABLE_COMPARATOR)
            val events =
                eventRows
                    .map { row ->
                        row.toDomain(
                            participants = participantsByEvent[row.eventId] ?: emptyList(),
                            evidenceAnchors = evidenceByEvent[row.eventId] ?: emptyList(),
                        )
                    }.sortedWith(Event.STABLE_COMPARATOR)
            val assertions =
                assertionRows.map { row ->
                    row.toDomain(qualifiersByAssertion[row.assertionId] ?: emptyMap())
                }
            val transitions = transitionRows.map { it.toDomain() }
            val identityLinks =
                identityLinkRows
                    .map { it.toDomain() }
                    .sortedWith(IdentityLink.STABLE_COMPARATOR)

            val pack =
                GraphPack.canonical(
                    manifest = manifest,
                    entities = entities,
                    names = names,
                    assertions = assertions,
                    transitions = transitions,
                    anchors = anchors,
                )

            GraphPackLoadResult.Success(
                pack = pack,
                events = events,
                evidenceAnchors = evidenceAnchors,
                identityLinks = identityLinks,
                verifiedSha256 = verifiedSha256,
            )
        } catch (e: IllegalArgumentException) {
            GraphPackLoadResult.Failure(
                GraphPackLoadError.InvalidPackContent(
                    e.message ?: "Graph pack failed domain validation",
                ),
            )
        } catch (e: Exception) {
            GraphPackLoadResult.Failure(
                GraphPackLoadError.CorruptSqliteFormat(
                    e.message ?: "Failed to decode SQLite rows",
                ),
            )
        }
    }

    private fun verifySqliteHeaderGeometry(bytes: ByteArray): GraphPackLoadError? {
        val rawPageSize =
            ((bytes[SQLITE_PAGE_SIZE_OFFSET].toInt() and BYTE_MASK) shl SHIFT_8) or
                (bytes[SQLITE_PAGE_SIZE_OFFSET + 1].toInt() and BYTE_MASK)
        val pageSize =
            if (rawPageSize == SQLITE_MAX_PAGE_SIZE_MARKER) {
                SQLITE_MAX_PAGE_SIZE
            } else {
                rawPageSize
            }
        val isPowerOfTwo =
            pageSize >= SQLITE_MIN_PAGE_SIZE &&
                pageSize <= SQLITE_MAX_PAGE_SIZE &&
                (pageSize and (pageSize - 1)) == 0
        if (!isPowerOfTwo) {
            return GraphPackLoadError.CorruptSqliteFormat(
                reason = "Invalid SQLite page size in header: $rawPageSize",
            )
        }

        val pageCount =
            ((bytes[SQLITE_PAGE_COUNT_OFFSET].toLong() and LONG_BYTE_MASK) shl SHIFT_24) or
                ((bytes[SQLITE_PAGE_COUNT_OFFSET + 1].toLong() and LONG_BYTE_MASK) shl SHIFT_16) or
                ((bytes[SQLITE_PAGE_COUNT_OFFSET + 2].toLong() and LONG_BYTE_MASK) shl SHIFT_8) or
                (bytes[SQLITE_PAGE_COUNT_OFFSET + 3].toLong() and LONG_BYTE_MASK)

        if (pageCount <= 0L) {
            return GraphPackLoadError.TruncatedOrEmptyPack(
                byteSize = bytes.size.toLong(),
                reason = "SQLite header declares zero database pages",
            )
        }

        val expectedSize = pageCount * pageSize.toLong()
        if (bytes.size.toLong() < expectedSize || bytes.size.toLong() % pageSize.toLong() != 0L) {
            return GraphPackLoadError.TruncatedOrEmptyPack(
                byteSize = bytes.size.toLong(),
                reason =
                    "SQLite file truncated: expected $expectedSize bytes " +
                        "($pageCount pages of $pageSize bytes), found ${bytes.size} bytes",
            )
        }

        return null
    }

    private fun verifyReadOnlyIntegrityAndSchema(
        sqlitePath: Path,
        byteSize: Long,
    ): GraphPackLoadError? {
        val checkSql =
            """
            PRAGMA query_only = ON;
            PRAGMA integrity_check;
            .print $QUERY_SPLIT_MARKER
            SELECT name FROM sqlite_master WHERE type = 'table';
            .print $QUERY_SPLIT_MARKER
            SELECT graph_schema_version FROM pack_metadata WHERE id = 1;
            """.trimIndent() + "\n"

        val outcome =
            runSqliteProcess(
                sqlitePath = sqlitePath,
                readOnly = true,
                jsonMode = true,
                sqlInput = checkSql,
            )

        if (outcome.exitCode != 0) {
            val detail = outcome.stderr.ifBlank { outcome.stdout }.trim()
            return if (detail.contains("truncated", ignoreCase = true) ||
                detail.contains("short read", ignoreCase = true)
            ) {
                GraphPackLoadError.TruncatedOrEmptyPack(byteSize = byteSize, reason = detail)
            } else if (detail.contains("no such table: pack_metadata", ignoreCase = true)) {
                GraphPackLoadError.InvalidPackContent("Missing pack_metadata table: $detail")
            } else {
                GraphPackLoadError.CorruptSqliteFormat("SQLite check failed: $detail")
            }
        }

        val parts = outcome.stdout.split(QUERY_SPLIT_MARKER)
        if (parts.size < 3) {
            return GraphPackLoadError.CorruptSqliteFormat("Incomplete SQLite verification output")
        }

        val integrityRows =
            try {
                decodeRows<IntegrityCheckRow>(parts[0])
            } catch (e: Exception) {
                return GraphPackLoadError.CorruptSqliteFormat(
                    "Corrupt integrity_check output: ${e.message}",
                )
            }

        if (integrityRows.isEmpty() || integrityRows.any { it.integrityCheck != "ok" }) {
            val message = integrityRows.joinToString("; ") { it.integrityCheck }
            return GraphPackLoadError.CorruptSqliteFormat("SQLite integrity_check failed: $message")
        }

        val tableNames =
            try {
                decodeRows<TableNameRow>(parts[1]).map { it.name }.toSet()
            } catch (e: Exception) {
                return GraphPackLoadError.CorruptSqliteFormat(
                    "Failed to inspect sqlite_master: ${e.message}",
                )
            }

        val missingTables = REQUIRED_PACK_TABLES - tableNames
        if (missingTables.isNotEmpty()) {
            return GraphPackLoadError.InvalidPackContent(
                "Missing required graph pack tables: ${missingTables.sorted().joinToString(", ")}",
            )
        }

        val versionRows =
            try {
                decodeRows<SchemaVersionRow>(parts[2])
            } catch (e: Exception) {
                return GraphPackLoadError.InvalidPackContent(
                    "Invalid pack_metadata schema version row: ${e.message}",
                )
            }

        val versionRow =
            versionRows.firstOrNull()
                ?: return GraphPackLoadError.InvalidPackContent("pack_metadata table is empty")

        if (versionRow.graphSchemaVersion < 1) {
            return GraphPackLoadError.InvalidPackContent(
                "Invalid graph_schema_version: ${versionRow.graphSchemaVersion}",
            )
        }
        if (versionRow.graphSchemaVersion > MAX_SUPPORTED_GRAPH_SCHEMA_VERSION) {
            return GraphPackLoadError.UnsupportedSchemaVersion(
                schemaVersion = versionRow.graphSchemaVersion,
                maxSupportedVersion = MAX_SUPPORTED_GRAPH_SCHEMA_VERSION,
            )
        }

        return null
    }

    internal inline fun <reified T> querySingleJsonArray(
        sqlitePath: Path,
        sql: String,
    ): List<T> {
        val segments = executeReadOnlyBatchQueries(sqlitePath, listOf(sql))
        return decodeRows(segments.first())
    }

    internal inline fun <reified T> decodeRows(jsonSegment: String): List<T> {
        val trimmed = jsonSegment.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        return GraphJson.decodeFromString(trimmed)
    }

    internal fun executeReadOnlyBatchQueries(
        sqlitePath: Path,
        queries: List<String>,
    ): List<String> {
        val script =
            buildString {
                appendLine("PRAGMA query_only = ON;")
                appendLine("PRAGMA foreign_keys = ON;")
                queries.forEachIndexed { index, query ->
                    if (index > 0) {
                        appendLine(".print $QUERY_SPLIT_MARKER")
                    }
                    appendLine(query)
                }
            }
        val outcome =
            runSqliteProcess(
                sqlitePath = sqlitePath,
                readOnly = true,
                jsonMode = true,
                sqlInput = script,
            )
        if (outcome.exitCode != 0) {
            throw SqliteExecutionException(outcome.stderr.ifBlank { outcome.stdout }.trim())
        }
        val parts = outcome.stdout.split(QUERY_SPLIT_MARKER)
        return List(queries.size) { idx -> parts.getOrNull(idx)?.trim() ?: "" }
    }

    internal fun runSqliteProcess(
        sqlitePath: Path,
        readOnly: Boolean,
        jsonMode: Boolean,
        sqlInput: String,
    ): ProcessOutcome {
        val cmd = mutableListOf("sqlite3", "-bail", "-batch", "-noinit")
        if (readOnly) {
            cmd.add("-readonly")
        }
        if (jsonMode) {
            cmd.add("-json")
        }
        cmd.add(sqlitePath.toAbsolutePath().toString())

        val process =
            ProcessBuilder(cmd)
                .start()

        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(sqlInput)
        }

        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = process.waitFor()
        return ProcessOutcome(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    private fun companionManifestPath(sqlitePath: Path): Path =
        sqlitePath.resolveSibling("${sqlitePath.fileName}.manifest.json")

    private fun readCompanionManifestSha256(sqlitePath: Path): String? {
        val candidate = companionManifestPath(sqlitePath)
        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            return null
        }
        return try {
            val content = Files.readString(candidate)
            GraphJson.decodeFromString<GraphPackManifest>(content).checksumSha256
        } catch (_: Exception) {
            null
        }
    }

    private fun buildWriteSqlScript(
        pack: GraphPack,
        events: List<Event>,
        evidenceAnchors: List<EvidenceAnchor>,
        identityLinks: List<IdentityLink>,
        effectiveSchemaVersion: Int,
    ): String =
        buildString {
            appendLine("PRAGMA page_size = 4096;")
            appendLine("PRAGMA journal_mode = DELETE;")
            appendLine("PRAGMA synchronous = FULL;")
            appendLine("PRAGMA foreign_keys = ON;")
            appendLine("BEGIN TRANSACTION;")
            appendLine(GRAPH_PACK_DDL)

            val m = pack.manifest
            val p = m.provenance
            val c = m.counts
            appendLine(
                """
                INSERT INTO pack_metadata (
                    id, graph_schema_version, work_id, edition_id, graph_version,
                    reveal_anchor_map_version, package_format, publication_policy,
                    source_excerpt_policy, created_at, copyright_basis,
                    validator_version, extraction_version, model_id, prompt_version, quality_report_id,
                    has_counts, count_entities, count_assertions, count_events, count_evidence_anchors
                ) VALUES (
                    1,
                    $effectiveSchemaVersion,
                    ${sqlString(m.workId.value)},
                    ${sqlString(m.editionId.value)},
                    ${sqlString(m.graphVersion)},
                    ${m.revealAnchorMapVersion},
                    ${sqlString(m.packageFormat)},
                    ${sqlString(m.publicationPolicy)},
                    ${sqlString(m.sourceExcerptPolicy)},
                    ${sqlString(m.createdAt)},
                    ${sqlString(m.copyrightBasis)},
                    ${sqlNullableString(p?.validatorVersion)},
                    ${sqlNullableString(p?.extractionVersion)},
                    ${sqlNullableString(p?.modelId)},
                    ${sqlNullableString(p?.promptVersion)},
                    ${sqlNullableString(p?.qualityReportId)},
                    ${if (c != null) 1 else 0},
                    ${c?.entities ?: "NULL"},
                    ${c?.assertions ?: "NULL"},
                    ${c?.events ?: "NULL"},
                    ${c?.evidenceAnchors ?: "NULL"}
                );
                """.trimIndent(),
            )

            for (anchor in pack.anchors) {
                val loc = anchor.locator
                appendLine(
                    """
                    INSERT INTO reveal_anchors (
                        anchor_id, edition_id, ordinal,
                        locator_href, locator_type, locator_title,
                        locator_progression, locator_total_progression,
                        locator_text_before, locator_text_after,
                        reveal_kind, text_hash
                    ) VALUES (
                        ${sqlString(anchor.anchorId.value)},
                        ${sqlString(anchor.editionId.value)},
                        ${anchor.ordinal},
                        ${sqlString(loc.href)},
                        ${sqlString(loc.type)},
                        ${sqlNullableString(loc.title)},
                        ${loc.progression ?: "NULL"},
                        ${loc.totalProgression ?: "NULL"},
                        ${sqlNullableString(loc.textBefore)},
                        ${sqlNullableString(loc.textAfter)},
                        ${sqlString(anchor.revealKind.name)},
                        ${sqlNullableString(anchor.textHash)}
                    );
                    """.trimIndent(),
                )
            }

            for (entity in pack.entities) {
                appendLine(
                    """
                    INSERT INTO entities (
                        entity_id, entity_type, first_reveal, canonical_name_id, notes
                    ) VALUES (
                        ${sqlString(entity.entityId.value)},
                        ${sqlString(entity.entityType.name)},
                        ${entity.firstReveal},
                        ${sqlNullableString(entity.canonicalNameId?.value)},
                        ${sqlNullableString(entity.notes)}
                    );
                    """.trimIndent(),
                )
            }

            for (name in pack.names) {
                appendLine(
                    """
                    INSERT INTO entity_names (
                        name_id, entity_id, display_text, first_reveal, name_kind, evidence_anchor_id
                    ) VALUES (
                        ${sqlString(name.nameId.value)},
                        ${sqlString(name.entityId.value)},
                        ${sqlString(name.displayText)},
                        ${name.firstReveal},
                        ${sqlString(name.nameKind.name)},
                        ${sqlNullableString(name.evidenceAnchorId?.value)}
                    );
                    """.trimIndent(),
                )
            }

            for (ev in evidenceAnchors) {
                val loc = ev.locator
                appendLine(
                    """
                    INSERT INTO evidence_anchors (
                        evidence_id, reveal_ordinal,
                        locator_href, locator_type, locator_title,
                        locator_progression, locator_total_progression,
                        locator_text_before, locator_text_after,
                        source_hash, excerpt_policy, excerpt
                    ) VALUES (
                        ${sqlString(ev.evidenceId.value)},
                        ${ev.revealOrdinal},
                        ${sqlString(loc.href)},
                        ${sqlString(loc.type)},
                        ${sqlNullableString(loc.title)},
                        ${loc.progression ?: "NULL"},
                        ${loc.totalProgression ?: "NULL"},
                        ${sqlNullableString(loc.textBefore)},
                        ${sqlNullableString(loc.textAfter)},
                        ${sqlNullableString(ev.sourceHash)},
                        ${sqlString(ev.excerptPolicy.name)},
                        ${sqlNullableString(ev.excerpt)}
                    );
                    """.trimIndent(),
                )
            }

            for (event in events) {
                val st = event.storyTime
                appendLine(
                    """
                    INSERT INTO events (
                        event_id, event_type, first_reveal,
                        story_time_kind, story_time_earliest, story_time_latest,
                        story_time_expression, story_time_certainty
                    ) VALUES (
                        ${sqlString(event.eventId.value)},
                        ${sqlString(event.eventType.name)},
                        ${event.firstReveal},
                        ${sqlNullableString(st?.kind?.name)},
                        ${sqlNullableString(st?.earliest)},
                        ${sqlNullableString(st?.latest)},
                        ${sqlNullableString(st?.expression)},
                        ${sqlNullableString(st?.certainty?.name)}
                    );
                    """.trimIndent(),
                )
                event.participantIds.forEachIndexed { idx, participantId ->
                    appendLine(
                        """
                        INSERT INTO event_participants (event_id, position, entity_id)
                        VALUES (${sqlString(
                            event.eventId.value,
                        )}, $idx, ${sqlString(participantId.value)});
                        """.trimIndent(),
                    )
                }
                event.evidenceAnchorIds.forEachIndexed { idx, evidenceId ->
                    appendLine(
                        """
                        INSERT INTO event_evidence (event_id, position, evidence_anchor_id)
                        VALUES (${sqlString(
                            event.eventId.value,
                        )}, $idx, ${sqlString(evidenceId.value)});
                        """.trimIndent(),
                    )
                }
            }

            for (assertion in pack.assertions) {
                val (objectKind, objectEntityId, literalValue, literalUnit) =
                    when (val obj = assertion.objectRef) {
                        is ObjectRef.Entity ->
                            ObjectRefColumns(
                                kind = "ENTITY",
                                entityId = obj.entityId.value,
                                literalValue = null,
                                literalUnit = null,
                            )
                        is ObjectRef.Literal ->
                            ObjectRefColumns(
                                kind = "LITERAL",
                                entityId = null,
                                literalValue = obj.value,
                                literalUnit = obj.unit,
                            )
                    }
                appendLine(
                    """
                    INSERT INTO assertions (
                        assertion_id, subject_id, predicate,
                        object_kind, object_entity_id, object_literal_value, object_literal_unit,
                        attribution_kind, attribution_speaker_id, attribution_context_anchor_id,
                        first_reveal, evidence_anchor_id, epistemic_status, extraction_confidence
                    ) VALUES (
                        ${sqlString(assertion.assertionId.value)},
                        ${sqlString(assertion.subjectId.value)},
                        ${sqlString(assertion.predicate)},
                        ${sqlString(objectKind)},
                        ${sqlNullableString(objectEntityId)},
                        ${sqlNullableString(literalValue)},
                        ${sqlNullableString(literalUnit)},
                        ${sqlString(assertion.attribution.kind.name)},
                        ${sqlNullableString(assertion.attribution.speakerId?.value)},
                        ${sqlNullableString(assertion.attribution.contextAnchorId?.value)},
                        ${assertion.firstReveal},
                        ${sqlString(assertion.evidenceAnchorId.value)},
                        ${sqlString(assertion.epistemicStatus.name)},
                        ${assertion.extractionConfidence ?: "NULL"}
                    );
                    """.trimIndent(),
                )
                for ((key, value) in assertion.qualifier.entries.sortedBy { it.key }) {
                    appendLine(
                        """
                        INSERT INTO assertion_qualifiers (assertion_id, qualifier_key, qualifier_value)
                        VALUES (${sqlString(
                            assertion.assertionId.value,
                        )}, ${sqlString(key)}, ${sqlString(value)});
                        """.trimIndent(),
                    )
                }
            }

            for (transition in pack.transitions) {
                appendLine(
                    """
                    INSERT INTO assertion_transitions (
                        transition_id, assertion_id, reveal_ordinal, status, evidence_anchor_id, reason
                    ) VALUES (
                        ${sqlString(transition.transitionId.value)},
                        ${sqlString(transition.assertionId.value)},
                        ${transition.revealOrdinal},
                        ${sqlString(transition.status.name)},
                        ${sqlNullableString(transition.evidenceAnchorId?.value)},
                        ${sqlNullableString(transition.reason)}
                    );
                    """.trimIndent(),
                )
            }

            for (link in identityLinks) {
                appendLine(
                    """
                    INSERT INTO identity_links (
                        link_id, left_entity_id, right_entity_id, kind, first_reveal, evidence_anchor_id
                    ) VALUES (
                        ${sqlString(link.linkId.value)},
                        ${sqlString(link.entityIds[0].value)},
                        ${sqlString(link.entityIds[1].value)},
                        ${sqlString(link.kind.name)},
                        ${link.firstReveal},
                        ${sqlNullableString(link.evidenceAnchorId?.value)}
                    );
                    """.trimIndent(),
                )
            }

            appendLine("COMMIT;")
            appendLine("VACUUM;")
        }

    private fun sqlString(value: String): String = "'${value.replace("'", "''")}'"

    private fun sqlNullableString(value: String?): String =
        if (value ==
            null
        ) {
            "NULL"
        } else {
            sqlString(value)
        }
}

internal data class ProcessOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

private data class ObjectRefColumns(
    val kind: String,
    val entityId: String?,
    val literalValue: String?,
    val literalUnit: String?,
)

private class SqliteExecutionException(
    override val message: String,
) : RuntimeException(message) {
    fun toLoadError(): GraphPackLoadError =
        if (message.contains("readonly", ignoreCase = true) ||
            message.contains("read-only", ignoreCase = true)
        ) {
            GraphPackLoadError.ReadOnlyViolation(message)
        } else {
            GraphPackLoadError.CorruptSqliteFormat(message)
        }
}

@Serializable
internal data class IntegrityCheckRow(
    @SerialName("integrity_check")
    val integrityCheck: String,
)

@Serializable
internal data class TableNameRow(
    @SerialName("name")
    val name: String,
)

@Serializable
internal data class SchemaVersionRow(
    @SerialName("graph_schema_version")
    val graphSchemaVersion: Int,
)

@Serializable
internal data class CountRow(
    @SerialName("entity_count")
    val entityCount: Int,
)

@Serializable
internal data class PackMetadataRow(
    @SerialName("id")
    val id: Int,
    @SerialName("graph_schema_version")
    val graphSchemaVersion: Int,
    @SerialName("work_id")
    val workId: String,
    @SerialName("edition_id")
    val editionId: String,
    @SerialName("graph_version")
    val graphVersion: String,
    @SerialName("reveal_anchor_map_version")
    val revealAnchorMapVersion: Int,
    @SerialName("package_format")
    val packageFormat: String,
    @SerialName("publication_policy")
    val publicationPolicy: String,
    @SerialName("source_excerpt_policy")
    val sourceExcerptPolicy: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("copyright_basis")
    val copyrightBasis: String,
    @SerialName("validator_version")
    val validatorVersion: String? = null,
    @SerialName("extraction_version")
    val extractionVersion: String? = null,
    @SerialName("model_id")
    val modelId: String? = null,
    @SerialName("prompt_version")
    val promptVersion: String? = null,
    @SerialName("quality_report_id")
    val qualityReportId: String? = null,
    @SerialName("has_counts")
    val hasCounts: Int = 0,
    @SerialName("count_entities")
    val countEntities: Int? = null,
    @SerialName("count_assertions")
    val countAssertions: Int? = null,
    @SerialName("count_events")
    val countEvents: Int? = null,
    @SerialName("count_evidence_anchors")
    val countEvidenceAnchors: Int? = null,
) {
    fun toDomain(verifiedSha256: String): GraphPackManifest {
        val provenance =
            validatorVersion?.let {
                Provenance(
                    validatorVersion = it,
                    extractionVersion = extractionVersion,
                    modelId = modelId,
                    promptVersion = promptVersion,
                    qualityReportId = qualityReportId,
                )
            }
        val counts =
            if (hasCounts != 0) {
                ManifestCounts(
                    entities = countEntities ?: 0,
                    assertions = countAssertions ?: 0,
                    events = countEvents ?: 0,
                    evidenceAnchors = countEvidenceAnchors ?: 0,
                )
            } else {
                null
            }
        return GraphPackManifest(
            graphSchemaVersion = graphSchemaVersion,
            workId = WorkId(workId),
            editionId = EditionId(editionId),
            graphVersion = graphVersion,
            revealAnchorMapVersion = revealAnchorMapVersion,
            checksumSha256 = verifiedSha256,
            packageFormat = packageFormat,
            publicationPolicy = publicationPolicy,
            sourceExcerptPolicy = sourceExcerptPolicy,
            createdAt = createdAt,
            copyrightBasis = copyrightBasis,
            provenance = provenance,
            counts = counts,
        )
    }
}

@Serializable
internal data class RevealAnchorRow(
    @SerialName("anchor_id")
    val anchorId: String,
    @SerialName("edition_id")
    val editionId: String,
    @SerialName("ordinal")
    val ordinal: Int,
    @SerialName("locator_href")
    val locatorHref: String,
    @SerialName("locator_type")
    val locatorType: String,
    @SerialName("locator_title")
    val locatorTitle: String? = null,
    @SerialName("locator_progression")
    val locatorProgression: Double? = null,
    @SerialName("locator_total_progression")
    val locatorTotalProgression: Double? = null,
    @SerialName("locator_text_before")
    val locatorTextBefore: String? = null,
    @SerialName("locator_text_after")
    val locatorTextAfter: String? = null,
    @SerialName("reveal_kind")
    val revealKind: String,
    @SerialName("text_hash")
    val textHash: String? = null,
) {
    fun toDomain(): RevealAnchor =
        RevealAnchor(
            anchorId = AnchorId(anchorId),
            editionId = EditionId(editionId),
            ordinal = ordinal,
            locator =
                Locator(
                    href = locatorHref,
                    type = locatorType,
                    title = locatorTitle,
                    progression = locatorProgression,
                    totalProgression = locatorTotalProgression,
                    textBefore = locatorTextBefore,
                    textAfter = locatorTextAfter,
                ),
            revealKind = RevealKind.valueOf(revealKind),
            textHash = textHash,
        )
}

@Serializable
internal data class EntityRow(
    @SerialName("entity_id")
    val entityId: String,
    @SerialName("entity_type")
    val entityType: String,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("canonical_name_id")
    val canonicalNameId: String? = null,
    @SerialName("notes")
    val notes: String? = null,
) {
    fun toDomain(): Entity =
        Entity(
            entityId = EntityId(entityId),
            entityType = EntityType.valueOf(entityType),
            firstReveal = firstReveal,
            canonicalNameId = canonicalNameId?.let { NameId(it) },
            notes = notes,
        )
}

@Serializable
internal data class EntityNameRow(
    @SerialName("name_id")
    val nameId: String,
    @SerialName("entity_id")
    val entityId: String,
    @SerialName("display_text")
    val displayText: String,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("name_kind")
    val nameKind: String,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: String? = null,
) {
    fun toDomain(): EntityName =
        EntityName(
            nameId = NameId(nameId),
            entityId = EntityId(entityId),
            displayText = displayText,
            firstReveal = firstReveal,
            nameKind = NameKind.valueOf(nameKind),
            evidenceAnchorId = evidenceAnchorId?.let { EvidenceAnchorId(it) },
        )
}

@Serializable
internal data class EvidenceAnchorRow(
    @SerialName("evidence_id")
    val evidenceId: String,
    @SerialName("reveal_ordinal")
    val revealOrdinal: Int,
    @SerialName("locator_href")
    val locatorHref: String,
    @SerialName("locator_type")
    val locatorType: String,
    @SerialName("locator_title")
    val locatorTitle: String? = null,
    @SerialName("locator_progression")
    val locatorProgression: Double? = null,
    @SerialName("locator_total_progression")
    val locatorTotalProgression: Double? = null,
    @SerialName("locator_text_before")
    val locatorTextBefore: String? = null,
    @SerialName("locator_text_after")
    val locatorTextAfter: String? = null,
    @SerialName("source_hash")
    val sourceHash: String? = null,
    @SerialName("excerpt_policy")
    val excerptPolicy: String,
    @SerialName("excerpt")
    val excerpt: String? = null,
) {
    fun toDomain(): EvidenceAnchor =
        EvidenceAnchor(
            evidenceId = EvidenceAnchorId(evidenceId),
            revealOrdinal = revealOrdinal,
            locator =
                Locator(
                    href = locatorHref,
                    type = locatorType,
                    title = locatorTitle,
                    progression = locatorProgression,
                    totalProgression = locatorTotalProgression,
                    textBefore = locatorTextBefore,
                    textAfter = locatorTextAfter,
                ),
            sourceHash = sourceHash,
            excerptPolicy = ExcerptPolicy.valueOf(excerptPolicy),
            excerpt = excerpt,
        )
}

@Serializable
internal data class EventRow(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("story_time_kind")
    val storyTimeKind: String? = null,
    @SerialName("story_time_earliest")
    val storyTimeEarliest: String? = null,
    @SerialName("story_time_latest")
    val storyTimeLatest: String? = null,
    @SerialName("story_time_expression")
    val storyTimeExpression: String? = null,
    @SerialName("story_time_certainty")
    val storyTimeCertainty: String? = null,
) {
    fun toDomain(
        participants: List<EntityId>,
        evidenceAnchors: List<EvidenceAnchorId>,
    ): Event {
        val storyTime =
            storyTimeKind?.let {
                StoryTime(
                    kind = StoryTimeKind.valueOf(it),
                    earliest = storyTimeEarliest,
                    latest = storyTimeLatest,
                    expression = storyTimeExpression,
                    certainty = storyTimeCertainty?.let { c -> StoryTimeCertainty.valueOf(c) },
                )
            }
        return Event(
            eventId = EventId(eventId),
            eventType = EventType.valueOf(eventType),
            firstReveal = firstReveal,
            participantIds = participants,
            storyTime = storyTime,
            evidenceAnchorIds = evidenceAnchors,
        )
    }
}

@Serializable
internal data class EventParticipantRow(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("position")
    val position: Int,
    @SerialName("entity_id")
    val entityId: String,
)

@Serializable
internal data class EventEvidenceRow(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("position")
    val position: Int,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: String,
)

@Serializable
internal data class AssertionRow(
    @SerialName("assertion_id")
    val assertionId: String,
    @SerialName("subject_id")
    val subjectId: String,
    @SerialName("predicate")
    val predicate: String,
    @SerialName("object_kind")
    val objectKind: String,
    @SerialName("object_entity_id")
    val objectEntityId: String? = null,
    @SerialName("object_literal_value")
    val objectLiteralValue: String? = null,
    @SerialName("object_literal_unit")
    val objectLiteralUnit: String? = null,
    @SerialName("attribution_kind")
    val attributionKind: String,
    @SerialName("attribution_speaker_id")
    val attributionSpeakerId: String? = null,
    @SerialName("attribution_context_anchor_id")
    val attributionContextAnchorId: String? = null,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: String,
    @SerialName("epistemic_status")
    val epistemicStatus: String,
    @SerialName("extraction_confidence")
    val extractionConfidence: Double? = null,
) {
    fun toDomain(qualifiers: Map<String, String>): Assertion {
        val objectRef =
            when (objectKind) {
                "ENTITY" -> {
                    val target =
                        requireNotNull(objectEntityId) {
                            "Assertion $assertionId has object_kind ENTITY " +
                                "but null object_entity_id"
                        }
                    ObjectRef.Entity(EntityId(target))
                }
                "LITERAL" -> {
                    val lit =
                        requireNotNull(objectLiteralValue) {
                            "Assertion $assertionId has object_kind LITERAL " +
                                "but null object_literal_value"
                        }
                    ObjectRef.Literal(value = lit, unit = objectLiteralUnit)
                }
                else -> throw IllegalArgumentException(
                    "Unknown object_kind '$objectKind' for assertion $assertionId",
                )
            }

        return Assertion(
            assertionId = AssertionId(assertionId),
            subjectId = EntityId(subjectId),
            predicate = predicate,
            objectRef = objectRef,
            qualifier = qualifiers,
            attribution =
                Attribution(
                    kind = AttributionKind.valueOf(attributionKind),
                    speakerId = attributionSpeakerId?.let { EntityId(it) },
                    contextAnchorId = attributionContextAnchorId?.let { AnchorId(it) },
                ),
            firstReveal = firstReveal,
            evidenceAnchorId = EvidenceAnchorId(evidenceAnchorId),
            epistemicStatus = EpistemicStatus.valueOf(epistemicStatus),
            extractionConfidence = extractionConfidence,
        )
    }
}

@Serializable
internal data class AssertionQualifierRow(
    @SerialName("assertion_id")
    val assertionId: String,
    @SerialName("qualifier_key")
    val qualifierKey: String,
    @SerialName("qualifier_value")
    val qualifierValue: String,
)

@Serializable
internal data class AssertionTransitionRow(
    @SerialName("transition_id")
    val transitionId: String,
    @SerialName("assertion_id")
    val assertionId: String,
    @SerialName("reveal_ordinal")
    val revealOrdinal: Int,
    @SerialName("status")
    val status: String,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: String? = null,
    @SerialName("reason")
    val reason: String? = null,
) {
    fun toDomain(): AssertionTransition =
        AssertionTransition(
            transitionId = TransitionId(transitionId),
            assertionId = AssertionId(assertionId),
            revealOrdinal = revealOrdinal,
            status = EpistemicStatus.valueOf(status),
            evidenceAnchorId = evidenceAnchorId?.let { EvidenceAnchorId(it) },
            reason = reason,
        )
}

@Serializable
internal data class IdentityLinkRow(
    @SerialName("link_id")
    val linkId: String,
    @SerialName("left_entity_id")
    val leftEntityId: String,
    @SerialName("right_entity_id")
    val rightEntityId: String,
    @SerialName("kind")
    val kind: String,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: String? = null,
) {
    fun toDomain(): IdentityLink =
        IdentityLink(
            linkId = LinkId(linkId),
            entityIds = listOf(EntityId(leftEntityId), EntityId(rightEntityId)),
            kind = IdentityLinkKind.valueOf(kind),
            firstReveal = firstReveal,
            evidenceAnchorId = evidenceAnchorId?.let { EvidenceAnchorId(it) },
        )
}

private const val GRAPH_PACK_DDL: String =
    """
    CREATE TABLE pack_metadata (
        id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
        graph_schema_version INTEGER NOT NULL,
        work_id TEXT NOT NULL,
        edition_id TEXT NOT NULL,
        graph_version TEXT NOT NULL,
        reveal_anchor_map_version INTEGER NOT NULL,
        package_format TEXT NOT NULL,
        publication_policy TEXT NOT NULL,
        source_excerpt_policy TEXT NOT NULL,
        created_at TEXT NOT NULL,
        copyright_basis TEXT NOT NULL,
        validator_version TEXT,
        extraction_version TEXT,
        model_id TEXT,
        prompt_version TEXT,
        quality_report_id TEXT,
        has_counts INTEGER NOT NULL DEFAULT 0,
        count_entities INTEGER,
        count_assertions INTEGER,
        count_events INTEGER,
        count_evidence_anchors INTEGER
    );
    CREATE TABLE reveal_anchors (
        anchor_id TEXT NOT NULL PRIMARY KEY,
        edition_id TEXT NOT NULL,
        ordinal INTEGER NOT NULL UNIQUE,
        locator_href TEXT NOT NULL,
        locator_type TEXT NOT NULL,
        locator_title TEXT,
        locator_progression REAL,
        locator_total_progression REAL,
        locator_text_before TEXT,
        locator_text_after TEXT,
        reveal_kind TEXT NOT NULL,
        text_hash TEXT
    );
    CREATE INDEX idx_reveal_anchors_ordinal ON reveal_anchors(ordinal);
    CREATE TABLE entities (
        entity_id TEXT NOT NULL PRIMARY KEY,
        entity_type TEXT NOT NULL,
        first_reveal INTEGER NOT NULL,
        canonical_name_id TEXT,
        notes TEXT
    );
    CREATE INDEX idx_entities_first_reveal ON entities(first_reveal);
    CREATE TABLE entity_names (
        name_id TEXT NOT NULL PRIMARY KEY,
        entity_id TEXT NOT NULL REFERENCES entities(entity_id),
        display_text TEXT NOT NULL,
        first_reveal INTEGER NOT NULL,
        name_kind TEXT NOT NULL,
        evidence_anchor_id TEXT
    );
    CREATE INDEX idx_entity_names_first_reveal ON entity_names(entity_id, first_reveal);
    CREATE TABLE evidence_anchors (
        evidence_id TEXT NOT NULL PRIMARY KEY,
        reveal_ordinal INTEGER NOT NULL,
        locator_href TEXT NOT NULL,
        locator_type TEXT NOT NULL,
        locator_title TEXT,
        locator_progression REAL,
        locator_total_progression REAL,
        locator_text_before TEXT,
        locator_text_after TEXT,
        source_hash TEXT,
        excerpt_policy TEXT NOT NULL,
        excerpt TEXT
    );
    CREATE INDEX idx_evidence_anchors_reveal_ordinal ON evidence_anchors(reveal_ordinal);
    CREATE TABLE events (
        event_id TEXT NOT NULL PRIMARY KEY,
        event_type TEXT NOT NULL,
        first_reveal INTEGER NOT NULL,
        story_time_kind TEXT,
        story_time_earliest TEXT,
        story_time_latest TEXT,
        story_time_expression TEXT,
        story_time_certainty TEXT
    );
    CREATE INDEX idx_events_first_reveal ON events(first_reveal);
    CREATE TABLE event_participants (
        event_id TEXT NOT NULL REFERENCES events(event_id),
        position INTEGER NOT NULL,
        entity_id TEXT NOT NULL REFERENCES entities(entity_id),
        PRIMARY KEY (event_id, position)
    );
    CREATE TABLE event_evidence (
        event_id TEXT NOT NULL REFERENCES events(event_id),
        position INTEGER NOT NULL,
        evidence_anchor_id TEXT NOT NULL,
        PRIMARY KEY (event_id, position)
    );
    CREATE TABLE assertions (
        assertion_id TEXT NOT NULL PRIMARY KEY,
        subject_id TEXT NOT NULL REFERENCES entities(entity_id),
        predicate TEXT NOT NULL,
        object_kind TEXT NOT NULL,
        object_entity_id TEXT,
        object_literal_value TEXT,
        object_literal_unit TEXT,
        attribution_kind TEXT NOT NULL,
        attribution_speaker_id TEXT,
        attribution_context_anchor_id TEXT,
        first_reveal INTEGER NOT NULL,
        evidence_anchor_id TEXT NOT NULL,
        epistemic_status TEXT NOT NULL,
        extraction_confidence REAL
    );
    CREATE INDEX idx_assertions_first_reveal ON assertions(first_reveal);
    CREATE TABLE assertion_qualifiers (
        assertion_id TEXT NOT NULL REFERENCES assertions(assertion_id),
        qualifier_key TEXT NOT NULL,
        qualifier_value TEXT NOT NULL,
        PRIMARY KEY (assertion_id, qualifier_key)
    );
    CREATE TABLE assertion_transitions (
        transition_id TEXT NOT NULL PRIMARY KEY,
        assertion_id TEXT NOT NULL REFERENCES assertions(assertion_id),
        reveal_ordinal INTEGER NOT NULL,
        status TEXT NOT NULL,
        evidence_anchor_id TEXT,
        reason TEXT
    );
    CREATE INDEX idx_assertion_transitions_reveal_ordinal
        ON assertion_transitions(assertion_id, reveal_ordinal);
    CREATE TABLE identity_links (
        link_id TEXT NOT NULL PRIMARY KEY,
        left_entity_id TEXT NOT NULL REFERENCES entities(entity_id),
        right_entity_id TEXT NOT NULL REFERENCES entities(entity_id),
        kind TEXT NOT NULL,
        first_reveal INTEGER NOT NULL,
        evidence_anchor_id TEXT
    );
    CREATE INDEX idx_identity_links_first_reveal ON identity_links(first_reveal);
    """
