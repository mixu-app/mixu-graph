// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCoreModelTest {
    private val editionId = EditionId("urn:edition:holmes-scarlet-1887")
    private val workId = WorkId("urn:work:study-in-scarlet")
    private val defaultManifest =
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
            counts = ManifestCounts(entities = 2, assertions = 1),
        )

    private fun sampleAnchor(
        ordinal: Int,
        targetEdition: EditionId = editionId,
    ): RevealAnchor =
        RevealAnchor(
            anchorId = AnchorId("anchor:$ordinal"),
            editionId = targetEdition,
            ordinal = ordinal,
            locator = Locator(href = "ch01.xhtml", type = "application/xhtml+xml"),
            revealKind = RevealKind.PARAGRAPH,
        )

    @Test
    fun identifiersRejectEmptyValues() {
        assertFailsWith<IllegalArgumentException> { EntityId("") }
        assertFailsWith<IllegalArgumentException> { NameId("") }
        assertFailsWith<IllegalArgumentException> { AssertionId("") }
        assertFailsWith<IllegalArgumentException> { TransitionId("") }
        assertFailsWith<IllegalArgumentException> { AnchorId("") }
        assertFailsWith<IllegalArgumentException> { EvidenceAnchorId("") }
        assertFailsWith<IllegalArgumentException> { EditionId("") }
        assertFailsWith<IllegalArgumentException> { WorkId("") }
    }

    @Test
    fun validGraphPackConstructsSuccessfully() {
        val holmes = EntityId("entity:holmes")
        val watson = EntityId("entity:watson")
        val holmesName = NameId("name:holmes")
        val watsonName = NameId("name:watson")
        val assertionId = AssertionId("assertion:flatmates")

        val pack =
            GraphPack(
                manifest = defaultManifest,
                entities =
                    listOf(
                        Entity(
                            entityId = holmes,
                            entityType = EntityType.PERSON,
                            firstReveal = 0,
                            canonicalNameId = holmesName,
                        ),
                        Entity(
                            entityId = watson,
                            entityType = EntityType.PERSON,
                            firstReveal = 1,
                            canonicalNameId = watsonName,
                        ),
                    ),
                names =
                    listOf(
                        EntityName(
                            nameId = holmesName,
                            entityId = holmes,
                            displayText = "Sherlock Holmes",
                            firstReveal = 0,
                            nameKind = NameKind.CANONICAL,
                        ),
                        EntityName(
                            nameId = watsonName,
                            entityId = watson,
                            displayText = "Dr. John Watson",
                            firstReveal = 1,
                            nameKind = NameKind.CANONICAL,
                        ),
                    ),
                assertions =
                    listOf(
                        Assertion(
                            assertionId = assertionId,
                            subjectId = holmes,
                            predicate = "flatmate_of",
                            objectRef = ObjectRef.Entity(watson),
                            attribution = Attribution(kind = AttributionKind.NARRATOR),
                            firstReveal = 1,
                            evidenceAnchorId = EvidenceAnchorId("anchor:1"),
                            epistemicStatus = EpistemicStatus.OBSERVED,
                            extractionConfidence = 0.95,
                        ),
                    ),
                transitions =
                    listOf(
                        AssertionTransition(
                            transitionId = TransitionId("trans:1"),
                            assertionId = assertionId,
                            revealOrdinal = 2,
                            status = EpistemicStatus.CORROBORATED,
                        ),
                    ),
                anchors = listOf(sampleAnchor(0), sampleAnchor(1), sampleAnchor(2)),
            )

        assertEquals(2, pack.entities.size)
        assertEquals(3, pack.anchors.size)
    }

    @Test
    fun denseMonotonicOrdinalsAreEnforced() {
        // Gap in ordinals: 0, 2 (missing 1)
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities = emptyList(),
                names = emptyList(),
                assertions = emptyList(),
                anchors = listOf(sampleAnchor(0), sampleAnchor(2)),
            )
        }

        // Duplicate ordinals: 0, 0
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities = emptyList(),
                names = emptyList(),
                assertions = emptyList(),
                anchors =
                    listOf(
                        sampleAnchor(0),
                        RevealAnchor(
                            anchorId = AnchorId("anchor:dup"),
                            editionId = editionId,
                            ordinal = 0,
                            locator = Locator(href = "ch01.xhtml", type = "application/xhtml+xml"),
                            revealKind = RevealKind.PARAGRAPH,
                        ),
                    ),
            )
        }
    }

    @Test
    fun editionMixingIsForbidden() {
        val foreignEdition = EditionId("urn:edition:other-edition")
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities = emptyList(),
                names = emptyList(),
                assertions = emptyList(),
                anchors = listOf(sampleAnchor(0), sampleAnchor(1, foreignEdition)),
            )
        }
    }

    @Test
    fun entityAndNameIntegrityIsEnforced() {
        val unknownEntity = EntityId("entity:ghost")
        val nameId = NameId("name:1")

        // Name references unknown entity
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities = emptyList(),
                names =
                    listOf(
                        EntityName(
                            nameId = nameId,
                            entityId = unknownEntity,
                            displayText = "Ghost",
                            firstReveal = 0,
                            nameKind = NameKind.CANONICAL,
                        ),
                    ),
                assertions = emptyList(),
            )
        }

        // Entity references canonical name from another entity
        val e1 = EntityId("entity:1")
        val e2 = EntityId("entity:2")
        val n2 = NameId("name:2")
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities =
                    listOf(
                        Entity(
                            entityId = e1,
                            entityType = EntityType.PERSON,
                            firstReveal = 0,
                            canonicalNameId = n2,
                        ),
                        Entity(
                            entityId = e2,
                            entityType = EntityType.PERSON,
                            firstReveal = 0,
                            canonicalNameId = n2,
                        ),
                    ),
                names =
                    listOf(
                        EntityName(
                            nameId = n2,
                            entityId = e2,
                            displayText = "Two",
                            firstReveal = 0,
                            nameKind = NameKind.CANONICAL,
                        ),
                    ),
                assertions = emptyList(),
            )
        }
    }

    @Test
    fun assertionAndTransitionIntegrityIsEnforced() {
        val e1 = EntityId("entity:1")
        val a1 = AssertionId("assertion:1")

        // Transition references non-existent assertion
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities =
                    listOf(
                        Entity(entityId = e1, entityType = EntityType.PERSON, firstReveal = 0),
                    ),
                names = emptyList(),
                assertions = emptyList(),
                transitions =
                    listOf(
                        AssertionTransition(
                            transitionId = TransitionId("trans:ghost"),
                            assertionId = a1,
                            revealOrdinal = 1,
                            status = EpistemicStatus.CONTRADICTED,
                        ),
                    ),
            )
        }

        // Transition revealOrdinal earlier than assertion firstReveal
        assertFailsWith<IllegalArgumentException> {
            GraphPack(
                manifest = defaultManifest,
                entities =
                    listOf(
                        Entity(entityId = e1, entityType = EntityType.PERSON, firstReveal = 0),
                    ),
                names = emptyList(),
                assertions =
                    listOf(
                        Assertion(
                            assertionId = a1,
                            subjectId = e1,
                            predicate = "age",
                            objectRef = ObjectRef.Literal("35", "years"),
                            attribution = Attribution(kind = AttributionKind.NARRATOR),
                            firstReveal = 5,
                            evidenceAnchorId = EvidenceAnchorId("anchor:5"),
                            epistemicStatus = EpistemicStatus.OBSERVED,
                        ),
                    ),
                transitions =
                    listOf(
                        AssertionTransition(
                            transitionId = TransitionId("trans:early"),
                            assertionId = a1,
                            revealOrdinal = 3, // Earlier than 5!
                            status = EpistemicStatus.CONTRADICTED,
                        ),
                    ),
            )
        }
    }

    @Test
    fun revealedWrappersEnforceHorizonInvariants() {
        val e1 = EntityId("entity:1")
        val entity = Entity(entityId = e1, entityType = EntityType.PERSON, firstReveal = 3)
        val name =
            EntityName(
                nameId = NameId("name:1"),
                entityId = e1,
                displayText = "Alice",
                firstReveal = 4,
                nameKind = NameKind.CANONICAL,
            )

        // Valid revealed entity at horizon 4
        val revealed =
            RevealedEntity(
                entity = entity,
                canonicalName = name,
                allRevealedNames = listOf(name),
                viewOrdinal = 4,
            )
        assertEquals("Alice", revealed.canonicalName?.displayText)

        // View ordinal 2 is prior to entity firstReveal 3 -> fails
        assertFailsWith<IllegalArgumentException> {
            RevealedEntity(
                entity = entity,
                canonicalName = null,
                allRevealedNames = emptyList(),
                viewOrdinal = 2,
            )
        }

        // View ordinal 3 is prior to name firstReveal 4 -> fails
        assertFailsWith<IllegalArgumentException> {
            RevealedEntity(
                entity = entity,
                canonicalName = name,
                allRevealedNames = listOf(name),
                viewOrdinal = 3,
            )
        }
    }

    @Test
    fun revealBoundaryEvaluatesPermittedCorrectly() {
        val boundary = RevealBoundary(editionId = editionId, maxOrdinal = 10)
        assertTrue(boundary.isPermitted(0, editionId))
        assertTrue(boundary.isPermitted(10, editionId))
        assertFalse(boundary.isPermitted(11, editionId))
        assertFalse(boundary.isPermitted(5, EditionId("urn:edition:foreign")))
    }
}
