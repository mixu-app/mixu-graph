// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeterministicOrderingGoldenTest {
    private val editionId = EditionId("urn:edition:golden-sherlock")
    private val workId = WorkId("urn:work:golden-canon")

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

    // Entities
    private val eHolmes = EntityId("entity:holmes")
    private val eWatson = EntityId("entity:watson")
    private val eHudson = EntityId("entity:hudson")
    private val eLestrade = EntityId("entity:lestrade")
    private val eIrene = EntityId("entity:irene")
    private val eMoriarty = EntityId("entity:moriarty")

    // Names
    private val nHolmesCanon = NameId("name:holmes:01_canonical")
    private val nHolmesAlias = NameId("name:holmes:02_alias")
    private val nHolmesTitle = NameId("name:holmes:03_title")
    private val nHolmesChemist = NameId("name:holmes:00_chemist")

    private val nWatsonCanon = NameId("name:watson:01_canonical")
    private val nWatsonDoctor = NameId("name:watson:02_doctor")

    private val nHudsonCanon = NameId("name:hudson:01_canonical")
    private val nHudsonLandlady = NameId("name:hudson:02_landlady")

    private val nLestradeCanon = NameId("name:lestrade:01_canonical")
    private val nLestradeDetective = NameId("name:lestrade:02_detective")

    private val nIreneCanon = NameId("name:irene:01_canonical")
    private val nIreneWoman = NameId("name:irene:02_woman")

    private val nMoriartyCanon = NameId("name:moriarty:01_canonical")
    private val nMoriartyNapoleon = NameId("name:moriarty:02_napoleon")

    // Assertions
    private val a01Meets = AssertionId("assertion:01_meets")
    private val a02Flatmates = AssertionId("assertion:02_flatmates")
    private val a03Landlady = AssertionId("assertion:03_landlady")
    private val a04Consults = AssertionId("assertion:04_consults")
    private val a05Admires = AssertionId("assertion:05_admires")
    private val a06Nemesis = AssertionId("assertion:06_nemesis")

    // Transitions
    private val t01Flatmates = TransitionId("trans:01_flatmates")
    private val t02Landlady = TransitionId("trans:02_landlady")
    private val t03NemesisCorrob = TransitionId("trans:03_nemesis_corrob")
    private val t04NemesisRetract = TransitionId("trans:04_nemesis_retract")

    private fun rawEntities(): List<Entity> =
        listOf(
            Entity(eMoriarty, EntityType.PERSON, 5, nMoriartyCanon),
            Entity(eIrene, EntityType.PERSON, 5, nIreneCanon),
            Entity(eLestrade, EntityType.PERSON, 2, nLestradeCanon),
            Entity(eHudson, EntityType.PERSON, 2, nHudsonCanon),
            Entity(eWatson, EntityType.PERSON, 1, nWatsonCanon),
            Entity(eHolmes, EntityType.PERSON, 0, nHolmesCanon),
        )

    private fun rawNames(): List<EntityName> =
        listOf(
            EntityName(nMoriartyNapoleon, eMoriarty, "Napoleon of Crime", 5, NameKind.PSEUDONYM),
            EntityName(nMoriartyCanon, eMoriarty, "Prof. Moriarty", 5, NameKind.CANONICAL),
            EntityName(nIreneWoman, eIrene, "The Woman", 5, NameKind.HONORIFIC),
            EntityName(nIreneCanon, eIrene, "Irene Adler", 5, NameKind.CANONICAL),
            EntityName(nLestradeDetective, eLestrade, "Scotland Yard Detective", 2, NameKind.TITLE),
            EntityName(nLestradeCanon, eLestrade, "Inspector Lestrade", 2, NameKind.CANONICAL),
            EntityName(nHudsonLandlady, eHudson, "Landlady", 2, NameKind.DESCRIPTION),
            EntityName(nHudsonCanon, eHudson, "Mrs. Hudson", 2, NameKind.CANONICAL),
            EntityName(nWatsonDoctor, eWatson, "Doctor", 1, NameKind.TITLE),
            EntityName(nWatsonCanon, eWatson, "Dr. John Watson", 1, NameKind.CANONICAL),
            EntityName(nHolmesTitle, eHolmes, "Consulting Detective", 1, NameKind.TITLE),
            EntityName(nHolmesAlias, eHolmes, "Holmes", 1, NameKind.ALIAS),
            EntityName(nHolmesCanon, eHolmes, "Sherlock Holmes", 1, NameKind.CANONICAL),
            EntityName(nHolmesChemist, eHolmes, "Chemist", 0, NameKind.DESCRIPTION),
        )

    private fun rawAssertions(): List<Assertion> =
        listOf(
            Assertion(
                assertionId = a06Nemesis,
                subjectId = eMoriarty,
                predicate = "nemesis_of",
                objectRef = ObjectRef.Entity(eHolmes),
                attribution = Attribution(AttributionKind.NARRATOR),
                firstReveal = 5,
                evidenceAnchorId = EvidenceAnchorId("anchor:5"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
            Assertion(
                assertionId = a05Admires,
                subjectId = eHolmes,
                predicate = "admires",
                objectRef = ObjectRef.Entity(eIrene),
                attribution = Attribution(AttributionKind.READER_INFERENCE),
                firstReveal = 5,
                evidenceAnchorId = EvidenceAnchorId("anchor:5"),
                epistemicStatus = EpistemicStatus.INFERRED,
            ),
            Assertion(
                assertionId = a04Consults,
                subjectId = eLestrade,
                predicate = "consults",
                objectRef = ObjectRef.Entity(eHolmes),
                attribution = Attribution(AttributionKind.WITNESS_CLAIM),
                firstReveal = 3,
                evidenceAnchorId = EvidenceAnchorId("anchor:3"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = a03Landlady,
                subjectId = eHudson,
                predicate = "landlady_of",
                objectRef = ObjectRef.Entity(eHolmes),
                attribution = Attribution(AttributionKind.OBSERVATION),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:2"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = a02Flatmates,
                subjectId = eHolmes,
                predicate = "flatmate_of",
                objectRef = ObjectRef.Entity(eWatson),
                attribution = Attribution(AttributionKind.NARRATOR),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:2"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = a01Meets,
                subjectId = eHolmes,
                predicate = "meets",
                objectRef = ObjectRef.Entity(eWatson),
                attribution = Attribution(AttributionKind.NARRATOR),
                firstReveal = 1,
                evidenceAnchorId = EvidenceAnchorId("anchor:1"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
        )

    private fun rawTransitions(): List<AssertionTransition> =
        listOf(
            AssertionTransition(
                transitionId = t04NemesisRetract,
                assertionId = a06Nemesis,
                revealOrdinal = 8,
                status = EpistemicStatus.RETRACTED,
            ),
            AssertionTransition(
                transitionId = t03NemesisCorrob,
                assertionId = a06Nemesis,
                revealOrdinal = 6,
                status = EpistemicStatus.CORROBORATED,
            ),
            AssertionTransition(
                transitionId = t02Landlady,
                assertionId = a03Landlady,
                revealOrdinal = 4,
                status = EpistemicStatus.CORROBORATED,
            ),
            AssertionTransition(
                transitionId = t01Flatmates,
                assertionId = a02Flatmates,
                revealOrdinal = 3,
                status = EpistemicStatus.CORROBORATED,
            ),
        )

    private fun sampleAnchors(): List<RevealAnchor> =
        (10 downTo 0).map { i ->
            RevealAnchor(
                anchorId = AnchorId("anchor:$i"),
                editionId = editionId,
                ordinal = i,
                locator = Locator(href = "ch$i.xhtml", type = "application/xhtml+xml"),
                revealKind = RevealKind.PARAGRAPH,
            )
        }

    private fun createRawPack(): GraphPack =
        GraphPack(
            manifest = manifest,
            entities = rawEntities(),
            names = rawNames(),
            assertions = rawAssertions(),
            transitions = rawTransitions(),
            anchors = sampleAnchors(),
        )

    private fun renderGolden(view: RevealedGraphView): String =
        buildString {
            appendLine("Ordinal: ${view.viewOrdinal}")
            appendLine("Entities:")
            for (re in view.entities) {
                val canon = re.canonicalName?.displayText ?: "<none>"
                val names = re.allRevealedNames.joinToString(", ") { it.displayText }
                appendLine(
                    "  ${re.entity.entityId.value} [${re.entity.firstReveal}] ($canon) {$names}",
                )
            }
            appendLine("Assertions:")
            for (ra in view.assertions) {
                val obj =
                    when (val o = ra.assertion.objectRef) {
                        is ObjectRef.Entity -> o.entityId.value
                        is ObjectRef.Literal -> o.value
                    }
                val trans = ra.activeTransition?.transitionId?.value ?: "<base>"
                appendLine(
                    "  ${ra.assertion.assertionId.value} [${ra.assertion.firstReveal}] " +
                        "${ra.assertion.subjectId.value} -> $obj = ${ra.currentStatus} ($trans)",
                )
            }
        }.trimEnd()

    @Test
    fun testGraphPackCanonicalNormalization() {
        val rawPack = createRawPack()
        assertFalse(rawPack.isCanonical())

        val canon = rawPack.canonical()
        assertTrue(canon.isCanonical())

        // Entities: firstReveal ASC, then entityId.value ASC
        assertEquals(
            listOf(eHolmes, eWatson, eHudson, eLestrade, eIrene, eMoriarty),
            canon.entities.map { it.entityId },
        )

        // Names: firstReveal ASC, then nameKind.ordinal ASC, then nameId.value ASC
        val expectedNames =
            listOf(
                // firstReveal = 0
                nHolmesChemist, // DESCRIPTION
                // firstReveal = 1
                nHolmesCanon, // CANONICAL
                nWatsonCanon, // CANONICAL
                nHolmesAlias, // ALIAS
                nHolmesTitle, // TITLE
                nWatsonDoctor, // TITLE
                // firstReveal = 2
                nHudsonCanon, // CANONICAL
                nLestradeCanon, // CANONICAL
                nHudsonLandlady, // DESCRIPTION
                nLestradeDetective, // TITLE
                // firstReveal = 5
                nIreneCanon, // CANONICAL
                nMoriartyCanon, // CANONICAL
                nIreneWoman, // HONORIFIC
                nMoriartyNapoleon, // PSEUDONYM
            )
        assertEquals(expectedNames, canon.names.map { it.nameId })

        // Assertions: firstReveal ASC, then assertionId.value ASC
        assertEquals(
            listOf(a01Meets, a02Flatmates, a03Landlady, a04Consults, a05Admires, a06Nemesis),
            canon.assertions.map { it.assertionId },
        )

        // Transitions: revealOrdinal ASC, then transitionId.value ASC
        assertEquals(
            listOf(t01Flatmates, t02Landlady, t03NemesisCorrob, t04NemesisRetract),
            canon.transitions.map { it.transitionId },
        )

        // Anchors: ordinal ASC
        assertEquals(
            (0..10).toList(),
            canon.anchors.map { it.ordinal },
        )
    }

    @Test
    fun testShuffledPacksProduceIdenticalCanonicalPacks() {
        val rawPack = createRawPack()
        val canon = rawPack.canonical()

        for (seed in 1..20) {
            val rng = Random(seed)
            val shuffled =
                GraphPack(
                    manifest = manifest,
                    entities = rawPack.entities.shuffled(rng),
                    names = rawPack.names.shuffled(rng),
                    assertions = rawPack.assertions.shuffled(rng),
                    transitions = rawPack.transitions.shuffled(rng),
                    anchors = rawPack.anchors.shuffled(rng),
                )
            assertEquals(canon, shuffled.canonical())
        }
    }

    @Test
    fun testProjectionsAcrossRevealOrdinalsMatchGolden() {
        val rawPack = createRawPack()
        val canon = rawPack.canonical()

        val engineRaw = TemporalProjectionEngine(rawPack)
        val engineCanon = TemporalProjectionEngine(canon)

        val ordinals = listOf(0, 1, 2, 3, 4, 5, 6, 8, 10)
        for (ord in ordinals) {
            val viewRaw = engineRaw.project(ord)
            val viewCanon = engineCanon.project(ord)
            assertEquals(viewCanon, viewRaw)
        }

        // Golden view at ordinal 0
        val goldenOrd0 =
            """
            Ordinal: 0
            Entities:
              entity:holmes [0] (Chemist) {Chemist}
            Assertions:
            """.trimIndent()
        assertEquals(goldenOrd0, renderGolden(engineRaw.project(0)))

        // Golden view at ordinal 2:
        // Holmes, Watson, Hudson, Lestrade visible.
        // Meets, Flatmates, Landlady assertions visible.
        val goldenOrd2 =
            """
            Ordinal: 2
            Entities:
              entity:holmes [0] (Sherlock Holmes) {Chemist, Sherlock Holmes, Holmes, Consulting Detective}
              entity:watson [1] (Dr. John Watson) {Dr. John Watson, Doctor}
              entity:hudson [2] (Mrs. Hudson) {Mrs. Hudson, Landlady}
              entity:lestrade [2] (Inspector Lestrade) {Inspector Lestrade, Scotland Yard Detective}
            Assertions:
              assertion:01_meets [1] entity:holmes -> entity:watson = OBSERVED (<base>)
              assertion:02_flatmates [2] entity:holmes -> entity:watson = OBSERVED (<base>)
              assertion:03_landlady [2] entity:hudson -> entity:holmes = OBSERVED (<base>)
            """.trimIndent()
        assertEquals(goldenOrd2, renderGolden(engineRaw.project(2)))

        // Golden view at ordinal 5:
        // All 6 entities visible.
        // Flatmates has transitioned to CORROBORATED at 3.
        // Landlady has transitioned to CORROBORATED at 4.
        val goldenOrd5 =
            """
            Ordinal: 5
            Entities:
              entity:holmes [0] (Sherlock Holmes) {Chemist, Sherlock Holmes, Holmes, Consulting Detective}
              entity:watson [1] (Dr. John Watson) {Dr. John Watson, Doctor}
              entity:hudson [2] (Mrs. Hudson) {Mrs. Hudson, Landlady}
              entity:lestrade [2] (Inspector Lestrade) {Inspector Lestrade, Scotland Yard Detective}
              entity:irene [5] (Irene Adler) {Irene Adler, The Woman}
              entity:moriarty [5] (Prof. Moriarty) {Prof. Moriarty, Napoleon of Crime}
            Assertions:
              assertion:01_meets [1] entity:holmes -> entity:watson = OBSERVED (<base>)
              assertion:02_flatmates [2] entity:holmes -> entity:watson = CORROBORATED (trans:01_flatmates)
              assertion:03_landlady [2] entity:hudson -> entity:holmes = CORROBORATED (trans:02_landlady)
              assertion:04_consults [3] entity:lestrade -> entity:holmes = OBSERVED (<base>)
              assertion:05_admires [5] entity:holmes -> entity:irene = INFERRED (<base>)
              assertion:06_nemesis [5] entity:moriarty -> entity:holmes = CLAIMED (<base>)
            """.trimIndent()
        assertEquals(goldenOrd5, renderGolden(engineRaw.project(5)))

        // Golden view at ordinal 8:
        // Nemesis status transitioned to RETRACTED at 8.
        val view8 = engineRaw.project(8)
        val nemesisRa = view8.assertions.find { it.assertion.assertionId == a06Nemesis }
        assertNotNull(nemesisRa)
        assertEquals(EpistemicStatus.RETRACTED, nemesisRa.currentStatus)
        assertEquals(t04NemesisRetract, nemesisRa.activeTransition?.transitionId)
    }

    @Test
    fun testBoundedNeighbourhoodDeterministicOrdering() {
        val rawPack = createRawPack()
        val engine = TemporalProjectionEngine(rawPack)

        val hood2 = engine.boundedNeighbourhood(eHolmes, 2, depth = 1)
        assertNotNull(hood2)
        assertEquals(eHolmes, hood2.focusEntity.entity.entityId)
        // Neighbours ordered by firstReveal ASC, entityId.value ASC
        assertEquals(
            listOf(eWatson, eHudson),
            hood2.neighbours.map { it.entity.entityId },
        )
        // Assertions ordered by firstReveal ASC, assertionId.value ASC
        assertEquals(
            listOf(a01Meets, a02Flatmates, a03Landlady),
            hood2.assertions.map { it.assertion.assertionId },
        )

        val hood5 = engine.boundedNeighbourhood(eHolmes, 5, depth = 1)
        assertNotNull(hood5)
        assertEquals(
            listOf(eWatson, eHudson, eLestrade, eIrene, eMoriarty),
            hood5.neighbours.map { it.entity.entityId },
        )
        assertEquals(
            listOf(a01Meets, a02Flatmates, a03Landlady, a04Consults, a05Admires, a06Nemesis),
            hood5.assertions.map { it.assertion.assertionId },
        )
    }

    @Test
    fun testSearchDeterministicOrdering() {
        val rawPack = createRawPack()
        val engine = TemporalProjectionEngine(rawPack)

        // Exact match "Holmes" matches alias of entity:holmes exactly.
        val hitsHolmes = engine.search("Holmes", 2)
        assertEquals(1, hitsHolmes.size)
        assertEquals(eHolmes, hitsHolmes[0].entity.entity.entityId)
        assertEquals("Holmes", hitsHolmes[0].matchedText)

        // "Detective" matches entity:holmes ("Consulting Detective", firstReveal 0)
        // and entity:lestrade ("Scotland Yard Detective", firstReveal 2).
        // Since neither is an exact match, sorted by firstReveal ASC, entityId ASC:
        // entity:holmes comes first!
        val hitsDetective = engine.search("Detective", 3)
        assertEquals(2, hitsDetective.size)
        assertEquals(eHolmes, hitsDetective[0].entity.entity.entityId)
        assertEquals(eLestrade, hitsDetective[1].entity.entity.entityId)

        // Multiple repeated runs must produce identical hit orders
        for (i in 1..20) {
            val repeatHits = engine.search("Detective", 3)
            assertEquals(
                hitsDetective.map {
                    it.entity.entity.entityId
                },
                repeatHits.map { it.entity.entity.entityId },
            )
        }
    }
}
