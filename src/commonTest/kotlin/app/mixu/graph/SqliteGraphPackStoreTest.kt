// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqliteGraphPackStoreTest {
    @Serializable
    private data class SyntheticFixture(
        @SerialName("fixture_id")
        val fixtureId: String,
        @SerialName("fixture_version")
        val fixtureVersion: String,
        @SerialName("title")
        val title: String,
        @SerialName("language")
        val language: String,
        @SerialName("content_basis")
        val contentBasis: String,
        @SerialName("graph_schema_version")
        val graphSchemaVersion: Int,
        @SerialName("scenario")
        val scenario: String,
        @SerialName("entities")
        val entities: List<FixtureEntity>,
        @SerialName("names")
        val names: List<FixtureName>,
        @SerialName("assertions")
        val assertions: List<FixtureAssertion>,
        @SerialName("identity_links")
        val identityLinks: List<FixtureIdentityLink> = emptyList(),
        @SerialName("status_changes")
        val statusChanges: List<FixtureStatusChange> = emptyList(),
        @SerialName("horizons")
        val horizons: List<FixtureHorizon> = emptyList(),
    )

    @Serializable
    private data class FixtureEntity(
        @SerialName("id")
        val id: String,
        @SerialName("type")
        val type: String,
        @SerialName("first_reveal")
        val firstReveal: Int,
        @SerialName("label")
        val label: String,
    )

    @Serializable
    private data class FixtureName(
        @SerialName("id")
        val id: String,
        @SerialName("entity_id")
        val entityId: String,
        @SerialName("display_text")
        val displayText: String,
        @SerialName("first_reveal")
        val firstReveal: Int,
    )

    @Serializable
    private data class FixtureAssertion(
        @SerialName("id")
        val id: String,
        @SerialName("subject_id")
        val subjectId: String,
        @SerialName("predicate")
        val predicate: String,
        @SerialName("object_ref")
        val objectRef: String,
        @SerialName("attribution")
        val attribution: String,
        @SerialName("first_reveal")
        val firstReveal: Int,
        @SerialName("status")
        val status: String,
    )

    @Serializable
    private data class FixtureIdentityLink(
        @SerialName("id")
        val id: String,
        @SerialName("entity_ids")
        val entityIds: List<String>,
        @SerialName("kind")
        val kind: String,
        @SerialName("first_reveal")
        val firstReveal: Int,
    )

    @Serializable
    private data class FixtureStatusChange(
        @SerialName("id")
        val id: String,
        @SerialName("assertion_id")
        val assertionId: String,
        @SerialName("reveal_ordinal")
        val revealOrdinal: Int,
        @SerialName("status")
        val status: String,
    )

    @Serializable
    private data class FixtureHorizon(
        @SerialName("ordinal")
        val ordinal: Int,
        @SerialName("expected_present")
        val expectedPresent: List<String>,
        @SerialName("forbidden")
        val forbidden: List<String>,
        @SerialName("note")
        val note: String,
    )

    private data class ConvertedFixtureBundle(
        val fixture: SyntheticFixture,
        val pack: GraphPack,
        val events: List<Event>,
        val evidenceAnchors: List<EvidenceAnchor>,
        val identityLinks: List<IdentityLink>,
    )

    private fun resolveSyntheticFixturesDir(): Path {
        val candidates =
            listOf(
                Paths.get("..", "mixu-fixtures", "fixtures", "synthetic"),
                Paths.get("mixu-fixtures", "fixtures", "synthetic"),
            )
        val found = candidates.firstOrNull { Files.isDirectory(it) }
        check(found != null) { "Could not locate mixu-fixtures/fixtures/synthetic directory" }
        return found.toAbsolutePath().normalize()
    }

    private fun loadSyntheticFixture(fixtureName: String): ConvertedFixtureBundle {
        val fixturePath = resolveSyntheticFixturesDir().resolve(fixtureName).resolve("fixture.json")
        check(Files.isRegularFile(fixturePath)) { "Missing fixture file: $fixturePath" }
        val fixture = GraphJson.decodeFromString<SyntheticFixture>(Files.readString(fixturePath))

        val editionId = EditionId("ed_${fixture.fixtureId}_${fixture.language}")
        val workId = WorkId("work_${fixture.fixtureId}")

        val namesByEntity = fixture.names.groupBy { it.entityId }
        val entities =
            fixture.entities.map { e ->
                val primaryNameId = namesByEntity[e.id]?.minByOrNull { it.firstReveal }?.id
                Entity(
                    entityId = EntityId(e.id),
                    entityType = EntityType.valueOf(e.type),
                    firstReveal = e.firstReveal,
                    canonicalNameId = primaryNameId?.let { NameId(it) },
                    notes = e.label,
                )
            }

        val names =
            fixture.names.map { n ->
                val primaryId = namesByEntity[n.entityId]?.minByOrNull { it.firstReveal }?.id
                EntityName(
                    nameId = NameId(n.id),
                    entityId = EntityId(n.entityId),
                    displayText = n.displayText,
                    firstReveal = n.firstReveal,
                    nameKind = if (n.id == primaryId) NameKind.CANONICAL else NameKind.ALIAS,
                    evidenceAnchorId = EvidenceAnchorId("ev_name_${n.id}"),
                )
            }

        val entityIdSet = entities.map { it.entityId.value }.toSet()
        val assertions =
            fixture.assertions.map { a ->
                val obj =
                    if (entityIdSet.contains(a.objectRef)) {
                        ObjectRef.Entity(EntityId(a.objectRef))
                    } else {
                        ObjectRef.Literal(value = a.objectRef, unit = "text")
                    }
                Assertion(
                    assertionId = AssertionId(a.id),
                    subjectId = EntityId(a.subjectId),
                    predicate = a.predicate,
                    objectRef = obj,
                    qualifier = mapOf("scenario" to fixture.scenario),
                    attribution = Attribution(kind = AttributionKind.valueOf(a.attribution)),
                    firstReveal = a.firstReveal,
                    evidenceAnchorId = EvidenceAnchorId("ev_${a.id}"),
                    epistemicStatus = EpistemicStatus.valueOf(a.status),
                    extractionConfidence = 0.95,
                )
            }

        val transitions =
            fixture.statusChanges.map { t ->
                AssertionTransition(
                    transitionId = TransitionId(t.id),
                    assertionId = AssertionId(t.assertionId),
                    revealOrdinal = t.revealOrdinal,
                    status = EpistemicStatus.valueOf(t.status),
                    evidenceAnchorId = EvidenceAnchorId("ev_${t.id}"),
                    reason = "Status transition at ordinal ${t.revealOrdinal}",
                )
            }

        val identityLinks =
            fixture.identityLinks.map { l ->
                IdentityLink(
                    linkId = LinkId(l.id),
                    entityIds = l.entityIds.map { EntityId(it) },
                    kind = IdentityLinkKind.valueOf(l.kind),
                    firstReveal = l.firstReveal,
                    evidenceAnchorId = EvidenceAnchorId("ev_${l.id}"),
                )
            }

        val maxOrdinal =
            maxOf(
                entities.maxOfOrNull { it.firstReveal } ?: 0,
                names.maxOfOrNull { it.firstReveal } ?: 0,
                assertions.maxOfOrNull { it.firstReveal } ?: 0,
                transitions.maxOfOrNull { it.revealOrdinal } ?: 0,
                identityLinks.maxOfOrNull { it.firstReveal } ?: 0,
            )

        val anchors =
            (0..maxOrdinal).map { ordinal ->
                RevealAnchor(
                    anchorId = AnchorId("anc_${fixture.fixtureId}_$ordinal"),
                    editionId = editionId,
                    ordinal = ordinal,
                    locator =
                        Locator(
                            href = "story.xhtml#s$ordinal",
                            type = "application/xhtml+xml",
                            title = "${fixture.title} - Scene $ordinal",
                            progression =
                                ordinal.toDouble() / (maxOrdinal.coerceAtLeast(1)).toDouble(),
                        ),
                    revealKind =
                        if (ordinal ==
                            0
                        ) {
                            RevealKind.CHAPTER_START
                        } else {
                            RevealKind.PARAGRAPH
                        },
                )
            }

        val evidenceAnchors =
            assertions.map { a ->
                EvidenceAnchor(
                    evidenceId = a.evidenceAnchorId,
                    revealOrdinal = a.firstReveal,
                    locator =
                        Locator(
                            href = "story.xhtml#s${a.firstReveal}",
                            type = "application/xhtml+xml",
                        ),
                    excerptPolicy = ExcerptPolicy.LOCATOR_ONLY,
                )
            }

        val events =
            assertions.mapIndexed { idx, a ->
                Event(
                    eventId = EventId("evt_${fixture.fixtureId}_$idx"),
                    eventType = EventType.DISCOVERY,
                    firstReveal = a.firstReveal,
                    participantIds = listOf(a.subjectId),
                    storyTime =
                        StoryTime(
                            kind = StoryTimeKind.RELATIVE,
                            expression = "scene ${a.firstReveal}",
                            certainty = StoryTimeCertainty.CERTAIN,
                        ),
                    evidenceAnchorIds = listOf(a.evidenceAnchorId),
                )
            }

        val placeholderSha = "0".repeat(64)
        val manifest =
            GraphPackManifest(
                graphSchemaVersion = fixture.graphSchemaVersion,
                workId = workId,
                editionId = editionId,
                graphVersion = fixture.fixtureVersion,
                revealAnchorMapVersion = 1,
                checksumSha256 = placeholderSha,
                packageFormat = "sqlite+zstd",
                publicationPolicy = "AUTHORIZED_PUBLIC",
                sourceExcerptPolicy = "LOCATOR_ONLY",
                createdAt = "2026-11-08T00:00:00Z",
                copyrightBasis = fixture.contentBasis,
                provenance =
                    Provenance(
                        validatorVersion = "0.1.0",
                        extractionVersion = "0.1.0",
                    ),
                counts =
                    ManifestCounts(
                        entities = entities.size,
                        assertions = assertions.size,
                        events = events.size,
                        evidenceAnchors = evidenceAnchors.size,
                    ),
            )

        val pack =
            GraphPack.canonical(
                manifest = manifest,
                entities = entities,
                names = names,
                assertions = assertions,
                transitions = transitions,
                anchors = anchors,
            )

        return ConvertedFixtureBundle(
            fixture = fixture,
            pack = pack,
            events = events.sortedWith(Event.STABLE_COMPARATOR),
            evidenceAnchors = evidenceAnchors.sortedWith(EvidenceAnchor.STABLE_COMPARATOR),
            identityLinks = identityLinks.sortedWith(IdentityLink.STABLE_COMPARATOR),
        )
    }

    @Test
    fun loadRealFixturePacks_verifiesChecksumAndEntityCountsAndHorizons() {
        val tempDir = Files.createTempDirectory("mixu-graph-sqlite-fixtures-")
        try {
            val fixtureNames = listOf("locked-stair", "silent-courier", "false-witness")
            for (fixtureName in fixtureNames) {
                val bundle = loadSyntheticFixture(fixtureName)
                val sqlitePath = tempDir.resolve("$fixtureName.sqlite")

                val written =
                    GraphPackSqliteContainer.writePack(
                        sqlitePath = sqlitePath,
                        pack = bundle.pack,
                        events = bundle.events,
                        evidenceAnchors = bundle.evidenceAnchors,
                        identityLinks = bundle.identityLinks,
                    )

                assertTrue(Files.isRegularFile(written.sqlitePath))
                assertTrue(Files.size(written.sqlitePath) >= 4096L)
                assertEquals(64, written.sha256.length)

                val openResult = GraphPackSqliteLoader.openReadOnly(sqlitePath, written.sha256)
                val db = assertIs<GraphPackDatabaseOpenResult.Success>(openResult).database
                assertEquals(bundle.fixture.entities.size, db.entityCount())

                val loadResult = db.loadGraphPack()
                val loaded = assertIs<GraphPackLoadResult.Success>(loadResult)
                assertEquals(written.sha256, loaded.verifiedSha256)
                assertEquals(bundle.fixture.entities.size, loaded.pack.entities.size)
                assertEquals(bundle.fixture.names.size, loaded.pack.names.size)
                assertEquals(bundle.fixture.assertions.size, loaded.pack.assertions.size)
                assertEquals(bundle.fixture.statusChanges.size, loaded.pack.transitions.size)
                assertEquals(bundle.events, loaded.events)
                assertEquals(bundle.evidenceAnchors, loaded.evidenceAnchors)
                assertEquals(bundle.identityLinks, loaded.identityLinks)
                assertEquals(bundle.pack.copy(manifest = written.manifest), loaded.pack)

                // Verify reveal-scoped SQL queries against every fixture horizon
                for (horizon in bundle.fixture.horizons) {
                    val revealedIds =
                        buildSet {
                            db
                                .queryEntitiesAtReveal(
                                    horizon.ordinal,
                                ).forEach { add(it.entityId.value) }
                            db
                                .queryEntityNamesAtReveal(
                                    horizon.ordinal,
                                ).forEach { add(it.nameId.value) }
                            db
                                .queryAssertionsAtReveal(
                                    horizon.ordinal,
                                ).forEach { add(it.assertionId.value) }
                            db
                                .queryTransitionsAtReveal(
                                    horizon.ordinal,
                                ).forEach { add(it.transitionId.value) }
                            db
                                .queryIdentityLinksAtReveal(
                                    horizon.ordinal,
                                ).forEach { add(it.linkId.value) }
                        }

                    for (expectedId in horizon.expectedPresent) {
                        assertTrue(
                            revealedIds.contains(expectedId),
                            "Fixture $fixtureName at ordinal ${horizon.ordinal}: " +
                                "expected $expectedId to be present",
                        )
                    }
                    for (forbiddenId in horizon.forbidden) {
                        assertFalse(
                            revealedIds.contains(forbiddenId),
                            "Fixture $fixtureName at ordinal ${horizon.ordinal}: " +
                                "forbidden $forbiddenId leaked",
                        )
                    }
                }
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun tamperedPack_isRejectedWithChecksumMismatchBeforeServingQueries() {
        val tempDir = Files.createTempDirectory("mixu-graph-sqlite-tamper-")
        try {
            val bundle = loadSyntheticFixture("locked-stair")
            val sqlitePath = tempDir.resolve("locked-stair.sqlite")
            val written =
                GraphPackSqliteContainer.writePack(
                    sqlitePath = sqlitePath,
                    pack = bundle.pack,
                    events = bundle.events,
                    evidenceAnchors = bundle.evidenceAnchors,
                    identityLinks = bundle.identityLinks,
                )

            // 1. Flip a single byte inside the SQLite file after the 100-byte header
            val originalBytes = Files.readAllBytes(sqlitePath)
            val tamperedBytes = originalBytes.copyOf()
            val targetIndex = tamperedBytes.size - 32
            tamperedBytes[targetIndex] = (tamperedBytes[targetIndex].toInt() xor 0x01).toByte()
            Files.write(sqlitePath, tamperedBytes)

            val result1 = GraphPackSqliteLoader.load(sqlitePath, expectedSha256 = written.sha256)
            val failure1 = assertIs<GraphPackLoadResult.Failure>(result1)
            val mismatch1 = assertIs<GraphPackLoadError.ChecksumMismatch>(failure1.error)
            assertEquals(written.sha256, mismatch1.expectedSha256)
            assertEquals(
                GraphPackSqliteContainer.computeSha256(tamperedBytes),
                mismatch1.actualSha256,
            )

            // 2. Restore original bytes, then tamper via SQL UPDATE without updating manifest
            Files.write(sqlitePath, originalBytes)
            val updateOutcome =
                GraphPackSqliteContainer.runSqliteProcess(
                    sqlitePath = sqlitePath,
                    readOnly = false,
                    jsonMode = false,
                    sqlInput = "UPDATE entities SET first_reveal = 0 WHERE entity_id = 'e_grey';\n",
                )
            assertEquals(0, updateOutcome.exitCode)

            // Even without passing expectedSha256, companion manifest catches the SQL tampering!
            val result2 = GraphPackSqliteLoader.load(sqlitePath)
            val failure2 = assertIs<GraphPackLoadResult.Failure>(result2)
            val mismatch2 = assertIs<GraphPackLoadError.ChecksumMismatch>(failure2.error)
            assertEquals(written.sha256, mismatch2.expectedSha256)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptAndTruncatedPacks_failWithTypedErrorsNotCrashes() {
        val tempDir = Files.createTempDirectory("mixu-graph-sqlite-corrupt-")
        try {
            // 1. Missing file -> FileNotFound
            val missingPath = tempDir.resolve("does-not-exist.sqlite")
            val missingResult = GraphPackSqliteLoader.load(missingPath)
            val missingFailure = assertIs<GraphPackLoadResult.Failure>(missingResult)
            assertIs<GraphPackLoadError.FileNotFound>(missingFailure.error)

            // 2. 0-byte empty file -> TruncatedOrEmptyPack
            val emptyPath = tempDir.resolve("empty.sqlite")
            Files.write(emptyPath, ByteArray(0))
            val emptyResult = GraphPackSqliteLoader.load(emptyPath)
            val emptyFailure = assertIs<GraphPackLoadResult.Failure>(emptyResult)
            val emptyErr = assertIs<GraphPackLoadError.TruncatedOrEmptyPack>(emptyFailure.error)
            assertEquals(0L, emptyErr.byteSize)

            // 3. 32-byte truncated header -> TruncatedOrEmptyPack
            val shortHeaderPath = tempDir.resolve("short-header.sqlite")
            Files.write(
                shortHeaderPath,
                "SQLite format 3\u00001234567890123456".encodeToByteArray(),
            )
            val shortResult = GraphPackSqliteLoader.load(shortHeaderPath)
            val shortFailure = assertIs<GraphPackLoadResult.Failure>(shortResult)
            val shortErr = assertIs<GraphPackLoadError.TruncatedOrEmptyPack>(shortFailure.error)
            assertEquals(32L, shortErr.byteSize)

            // 4. Non-SQLite random bytes (>= 100 bytes) -> CorruptSqliteFormat
            val garbagePath = tempDir.resolve("garbage.sqlite")
            Files.write(garbagePath, ByteArray(256) { idx -> (idx + 1).toByte() })
            val garbageResult = GraphPackSqliteLoader.load(garbagePath)
            val garbageFailure = assertIs<GraphPackLoadResult.Failure>(garbageResult)
            assertIs<GraphPackLoadError.CorruptSqliteFormat>(garbageFailure.error)

            // 5. Truncated half-written .sqlite file (with matching SHA-256 to test integrity)
            val bundle = loadSyntheticFixture("locked-stair")
            val validPath = tempDir.resolve("valid.sqlite")
            GraphPackSqliteContainer.writePack(
                sqlitePath = validPath,
                pack = bundle.pack,
                events = bundle.events,
                evidenceAnchors = bundle.evidenceAnchors,
                identityLinks = bundle.identityLinks,
            )
            val validBytes = Files.readAllBytes(validPath)
            val halfBytes = validBytes.copyOfRange(0, validBytes.size / 2)
            val halfPath = tempDir.resolve("half-truncated.sqlite")
            Files.write(halfPath, halfBytes)
            val halfSha = GraphPackSqliteContainer.computeSha256(halfBytes)

            val halfResult = GraphPackSqliteLoader.load(halfPath, expectedSha256 = halfSha)
            val halfFailure = assertIs<GraphPackLoadResult.Failure>(halfResult)
            val halfErr = assertIs<GraphPackLoadError.TruncatedOrEmptyPack>(halfFailure.error)
            assertEquals(halfBytes.size.toLong(), halfErr.byteSize)

            // 6. Corrupt SQLite page structure (valid size & magic, invalid page size in header)
            val badPageSizeBytes = validBytes.copyOf()
            badPageSizeBytes[16] = 0x00
            badPageSizeBytes[17] = 0x07 // 7 is not a valid power-of-two SQLite page size
            val badPageSizePath = tempDir.resolve("bad-page-size.sqlite")
            Files.write(badPageSizePath, badPageSizeBytes)
            val badPageSizeSha = GraphPackSqliteContainer.computeSha256(badPageSizeBytes)

            val badPageSizeResult =
                GraphPackSqliteLoader.load(
                    badPageSizePath,
                    expectedSha256 = badPageSizeSha,
                )
            val badPageSizeFailure = assertIs<GraphPackLoadResult.Failure>(badPageSizeResult)
            assertIs<GraphPackLoadError.CorruptSqliteFormat>(badPageSizeFailure.error)

            // 7. Valid SQLite database missing graph pack tables -> InvalidPackContent
            val bareDbPath = tempDir.resolve("bare.sqlite")
            GraphPackSqliteContainer.runSqliteProcess(
                sqlitePath = bareDbPath,
                readOnly = false,
                jsonMode = false,
                sqlInput =
                    "CREATE TABLE unrelated (id TEXT PRIMARY KEY);\n" +
                        "INSERT INTO unrelated VALUES ('x');\n",
            )
            val bareSha = GraphPackSqliteContainer.computeSha256(bareDbPath)
            val bareResult = GraphPackSqliteLoader.load(bareDbPath, expectedSha256 = bareSha)
            val bareFailure = assertIs<GraphPackLoadResult.Failure>(bareResult)
            assertIs<GraphPackLoadError.InvalidPackContent>(bareFailure.error)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsupportedSchemaVersion_isRejectedWithTypedError() {
        val tempDir = Files.createTempDirectory("mixu-graph-sqlite-schema-version-")
        try {
            val bundle = loadSyntheticFixture("locked-stair")
            val futureSqlitePath = tempDir.resolve("future-v2.sqlite")
            val written =
                GraphPackSqliteContainer.writePack(
                    sqlitePath = futureSqlitePath,
                    pack = bundle.pack,
                    events = bundle.events,
                    evidenceAnchors = bundle.evidenceAnchors,
                    identityLinks = bundle.identityLinks,
                    schemaVersionOverride = 2,
                )

            val loadResult =
                GraphPackSqliteLoader.load(
                    futureSqlitePath,
                    expectedSha256 = written.sha256,
                )
            val failure = assertIs<GraphPackLoadResult.Failure>(loadResult)
            val versionError = assertIs<GraphPackLoadError.UnsupportedSchemaVersion>(failure.error)
            assertEquals(2, versionError.schemaVersion)
            assertEquals(MAX_SUPPORTED_GRAPH_SCHEMA_VERSION, versionError.maxSupportedVersion)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun readOnlyDatabase_rejectsMutationsAndPreservesFileBytes() {
        val tempDir = Files.createTempDirectory("mixu-graph-sqlite-readonly-")
        try {
            val bundle = loadSyntheticFixture("locked-stair")
            val sqlitePath = tempDir.resolve("readonly.sqlite")
            val written =
                GraphPackSqliteContainer.writePack(
                    sqlitePath = sqlitePath,
                    pack = bundle.pack,
                    events = bundle.events,
                    evidenceAnchors = bundle.evidenceAnchors,
                    identityLinks = bundle.identityLinks,
                )

            val openResult = GraphPackSqliteLoader.openReadOnly(sqlitePath, written.sha256)
            val db = assertIs<GraphPackDatabaseOpenResult.Success>(openResult).database

            val insertErr =
                db.executeSql(
                    "INSERT INTO entities (entity_id, entity_type, first_reveal) " +
                        "VALUES ('e_hacker', 'PERSON', 0)",
                )
            assertIs<GraphPackLoadError.ReadOnlyViolation>(insertErr)

            val updateErr = db.executeSql("UPDATE entities SET first_reveal = 0")
            assertIs<GraphPackLoadError.ReadOnlyViolation>(updateErr)

            val deleteErr = db.executeSql("DELETE FROM entities")
            assertIs<GraphPackLoadError.ReadOnlyViolation>(deleteErr)

            val dropErr = db.executeSql("DROP TABLE entities")
            assertIs<GraphPackLoadError.ReadOnlyViolation>(dropErr)

            // File must remain byte-for-byte identical and entity count unchanged
            assertEquals(written.sha256, GraphPackSqliteContainer.computeSha256(sqlitePath))
            assertEquals(bundle.fixture.entities.size, db.entityCount())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
