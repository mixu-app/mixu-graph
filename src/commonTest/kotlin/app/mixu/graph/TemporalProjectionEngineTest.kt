// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalProjectionEngineTest {
    private val editionId = EditionId("urn:edition:test-holmes")
    private val workId = WorkId("urn:work:test-study")

    private val manifest =
        GraphPackManifest(
            graphSchemaVersion = 1,
            workId = workId,
            editionId = editionId,
            graphVersion = "1.0.0",
            revealAnchorMapVersion = 1,
            checksumSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            packageFormat = "sqlite+zstd",
            publicationPolicy = "AUTHORIZED_PUBLIC",
            sourceExcerptPolicy = "LOCATOR_ONLY",
            createdAt = "2026-11-08T00:00:00Z",
            copyrightBasis = "PUBLIC_DOMAIN",
            provenance = Provenance(validatorVersion = "1.0.0"),
        )

    private val e1 = EntityId("entity:holmes")
    private val e2 = EntityId("entity:watson")
    private val e3 = EntityId("entity:moriarty")

    private val n1Canonical = NameId("name:holmes:canonical")
    private val n1Alias = NameId("name:holmes:alias")
    private val n2Canonical = NameId("name:watson:canonical")
    private val n3Canonical = NameId("name:moriarty:canonical")

    private val a1 = AssertionId("assertion:flatmates")
    private val a2 = AssertionId("assertion:nemesis")

    private fun samplePack(): GraphPack =
        GraphPack(
            manifest = manifest,
            entities =
                listOf(
                    Entity(
                        entityId = e1,
                        entityType = EntityType.PERSON,
                        firstReveal = 0,
                        canonicalNameId = n1Canonical,
                    ),
                    Entity(
                        entityId = e2,
                        entityType = EntityType.PERSON,
                        firstReveal = 2,
                        canonicalNameId = n2Canonical,
                    ),
                    Entity(
                        entityId = e3,
                        entityType = EntityType.PERSON,
                        firstReveal = 8,
                        canonicalNameId = n3Canonical,
                    ),
                ),
            names =
                listOf(
                    EntityName(
                        nameId = n1Alias,
                        entityId = e1,
                        displayText = "Chemist",
                        firstReveal = 0,
                        nameKind = NameKind.DESCRIPTION,
                    ),
                    EntityName(
                        nameId = n1Canonical,
                        entityId = e1,
                        displayText = "Sherlock Holmes",
                        firstReveal = 3,
                        nameKind = NameKind.CANONICAL,
                    ),
                    EntityName(
                        nameId = n2Canonical,
                        entityId = e2,
                        displayText = "Dr. Watson",
                        firstReveal = 2,
                        nameKind = NameKind.CANONICAL,
                    ),
                    EntityName(
                        nameId = n3Canonical,
                        entityId = e3,
                        displayText = "Professor Moriarty",
                        firstReveal = 8,
                        nameKind = NameKind.CANONICAL,
                    ),
                ),
            assertions =
                listOf(
                    Assertion(
                        assertionId = a1,
                        subjectId = e1,
                        predicate = "meets",
                        objectRef = ObjectRef.Entity(e2),
                        attribution = Attribution(kind = AttributionKind.NARRATOR),
                        firstReveal = 2,
                        evidenceAnchorId = EvidenceAnchorId("anchor:2"),
                        epistemicStatus = EpistemicStatus.OBSERVED,
                    ),
                    Assertion(
                        assertionId = a2,
                        subjectId = e3,
                        predicate = "nemesis_of",
                        objectRef = ObjectRef.Entity(e1),
                        attribution = Attribution(kind = AttributionKind.NARRATOR),
                        firstReveal = 8,
                        evidenceAnchorId = EvidenceAnchorId("anchor:8"),
                        epistemicStatus = EpistemicStatus.CLAIMED,
                    ),
                ),
            transitions =
                listOf(
                    AssertionTransition(
                        transitionId = TransitionId("trans:1"),
                        assertionId = a1,
                        revealOrdinal = 5,
                        status = EpistemicStatus.CORROBORATED,
                    ),
                ),
            anchors =
                (0..10).map { ordinal ->
                    RevealAnchor(
                        anchorId = AnchorId("anchor:$ordinal"),
                        editionId = editionId,
                        ordinal = ordinal,
                        locator =
                            Locator(
                                href = "ch$ordinal.xhtml",
                                type = "application/xhtml+xml",
                            ),
                        revealKind = RevealKind.PARAGRAPH,
                    )
                },
        )

    @Test
    fun pSpoiler01_noLabelsOrAliasesBeyondHorizon() {
        val engine = TemporalProjectionEngine(samplePack())

        // At ordinal 1: Holmes is revealed (0), but canonical name is revealed at 3.
        val view1 = engine.project(1)
        val holmes1 = view1.entities.find { it.entity.entityId == e1 }
        assertNotNull(holmes1)
        assertEquals(n1Alias, holmes1.canonicalName?.nameId)
        assertEquals(listOf(n1Alias), holmes1.allRevealedNames.map { it.nameId })

        // At ordinal 3: canonical name is now revealed.
        val view3 = engine.project(3)
        val holmes3 = view3.entities.find { it.entity.entityId == e1 }
        assertNotNull(holmes3)
        assertEquals(n1Canonical, holmes3.canonicalName?.nameId)
    }

    @Test
    fun pSpoiler02_assertionMaintainsHistoricalStatusUnderTransition() {
        val engine = TemporalProjectionEngine(samplePack())

        // At ordinal 3: assertion a1 is revealed (firstReveal=2), but transition at 5 is in the future.
        val view3 = engine.project(3)
        val assertion3 = view3.assertions.find { it.assertion.assertionId == a1 }
        assertNotNull(assertion3)
        assertEquals(EpistemicStatus.OBSERVED, assertion3.currentStatus)
        assertNull(assertion3.activeTransition)

        // At ordinal 5: transition takes effect -> CORROBORATED
        val view5 = engine.project(5)
        val assertion5 = view5.assertions.find { it.assertion.assertionId == a1 }
        assertNotNull(assertion5)
        assertEquals(EpistemicStatus.CORROBORATED, assertion5.currentStatus)
        assertNotNull(assertion5.activeTransition)
    }

    @Test
    fun pSpoiler03_endpointHiddenSuppressesAssertion() {
        val engine = TemporalProjectionEngine(samplePack())

        // At ordinal 1: Holmes (0) is visible, but Watson (2) is hidden. Assertion a1 must be suppressed.
        val view1 = engine.project(1)
        assertTrue(view1.assertions.isEmpty())

        // At ordinal 2: Watson (2) is revealed -> Assertion a1 becomes visible.
        val view2 = engine.project(2)
        assertEquals(1, view2.assertions.size)
        assertEquals(
            a1,
            view2.assertions
                .first()
                .assertion.assertionId,
        )
    }

    @Test
    fun pSpoiler04_searchFiltersUnrevealedContent() {
        val engine = TemporalProjectionEngine(samplePack())

        // Search for "Sherlock" at ordinal 1 -> must NOT return results (canonical name revealed at 3)
        val results1 = engine.search("Sherlock", 1)
        assertTrue(results1.isEmpty())

        // Search for "Chemist" at ordinal 1 -> returns Holmes
        val resultsChemist = engine.search("Chemist", 1)
        assertEquals(1, resultsChemist.size)
        assertEquals(
            e1,
            resultsChemist
                .first()
                .entity.entity.entityId,
        )

        // Search for "Sherlock" at ordinal 3 -> returns Holmes
        val results3 = engine.search("Sherlock", 3)
        assertEquals(1, results3.size)
    }

    @Test
    fun pSpoiler05_boundedNeighbourhoodRespectsHorizon() {
        val engine = TemporalProjectionEngine(samplePack())

        // At ordinal 2: neighbourhood of Holmes (depth 1) includes Watson
        val hood2 = engine.boundedNeighbourhood(e1, 2, depth = 1)
        assertNotNull(hood2)
        assertEquals(listOf(e2), hood2.neighbours.map { it.entity.entityId })
        assertEquals(1, hood2.assertions.size)

        // At ordinal 1: Watson is unrevealed -> neighbourhood of Holmes is empty
        val hood1 = engine.boundedNeighbourhood(e1, 1, depth = 1)
        assertNotNull(hood1)
        assertTrue(hood1.neighbours.isEmpty())
        assertTrue(hood1.assertions.isEmpty())
    }

    @Test
    fun scaleTest_tenThousandEntitiesProjectionUnderThreshold() {
        val count = 10_000
        val entities =
            (0 until count).map { i ->
                Entity(
                    entityId = EntityId("entity:$i"),
                    entityType = EntityType.PERSON,
                    firstReveal = i % 500,
                )
            }
        val names =
            (0 until count).map { i ->
                EntityName(
                    nameId = NameId("name:$i"),
                    entityId = EntityId("entity:$i"),
                    displayText = "Person $i",
                    firstReveal = i % 500,
                    nameKind = NameKind.CANONICAL,
                )
            }

        val largePack =
            GraphPack(
                manifest = manifest,
                entities = entities,
                names = names,
                assertions = emptyList(),
                anchors =
                    (0..500).map { i ->
                        RevealAnchor(
                            anchorId = AnchorId("anchor:$i"),
                            editionId = editionId,
                            ordinal = i,
                            locator = Locator(href = "ch$i.xhtml", type = "application/xhtml+xml"),
                            revealKind = RevealKind.PARAGRAPH,
                        )
                    },
            )

        val engine = TemporalProjectionEngine(largePack)

        val startTime = System.currentTimeMillis()
        val view = engine.project(250)
        val elapsed = System.currentTimeMillis() - startTime

        // Expect around 5,000 entities visible at ordinal 250
        assertTrue(view.entities.isNotEmpty())
        assertTrue(
            elapsed < 1000,
            "10k-entity projection took ${elapsed}ms, which exceeds 1000ms threshold",
        )
    }
}
