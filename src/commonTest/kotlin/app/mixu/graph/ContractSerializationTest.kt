// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ContractSerializationTest {
    private val manifestJson =
        """
        {
          "graph_schema_version": 1,
          "work_id": "work_demo",
          "edition_id": "edition_demo_zh_1",
          "graph_version": "0.1.0",
          "reveal_anchor_map_version": 1,
          "checksum_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "package_format": "sqlite+zstd",
          "publication_policy": "PRIVATE_ONLY",
          "source_excerpt_policy": "LOCATOR_ONLY",
          "created_at": "2026-11-07T00:00:00Z",
          "provenance": {
            "validator_version": "0.1.0"
          },
          "copyright_basis": "SYNTHETIC_ORIGINAL",
          "counts": {
            "entities": 0,
            "assertions": 0,
            "events": 0,
            "evidence_anchors": 0
          }
        }
        """.trimIndent()

    private val entityJson =
        """
        {
          "entity_id": "e_ishida",
          "entity_type": "PERSON",
          "first_reveal": 1,
          "canonical_name_id": "n_ishida",
          "notes": "Primary suspect identified in the guesthouse registry."
        }
        """.trimIndent()

    private val entityNameJson =
        """
        {
          "name_id": "n_ishida",
          "entity_id": "e_ishida",
          "display_text": "Mr. Ishida",
          "first_reveal": 1,
          "name_kind": "CANONICAL",
          "evidence_anchor_id": "ev_register_entry"
        }
        """.trimIndent()

    private val assertionJson =
        """
        {
          "assertion_id": "a_grey_on_stair",
          "subject_id": "e_grey",
          "predicate": "was_present_at",
          "object_ref": {
            "entity_id": "loc_locked_stair"
          },
          "attribution": {
            "kind": "WITNESS_CLAIM",
            "speaker_id": "e_keeper"
          },
          "first_reveal": 2,
          "evidence_anchor_id": "ev_keeper_testimony",
          "epistemic_status": "CLAIMED",
          "extraction_confidence": 0.94
        }
        """.trimIndent()

    private val assertionTransitionJson =
        """
        {
          "transition_id": "tr_grey_on_stair_contradicted",
          "assertion_id": "a_grey_on_stair",
          "reveal_ordinal": 4,
          "status": "CONTRADICTED",
          "evidence_anchor_id": "ev_alibi_confirmed",
          "reason": "Keeper admitted confusion after broken lantern was examined."
        }
        """.trimIndent()

    private val revealAnchorJson =
        """
        {
          "anchor_id": "anc_ch01_p003",
          "edition_id": "ed_locked_stair_2026_en_epub",
          "ordinal": 3,
          "reveal_kind": "paragraph",
          "locator": {
            "href": "text/chapter01.xhtml",
            "type": "application/xhtml+xml",
            "title": "Chapter 1: The Arrival",
            "progression": 0.125,
            "total_progression": 0.042,
            "text_before": "The clock struck eight as",
            "text_after": "stepped across the rain-soaked sill."
          },
          "text_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }
        """.trimIndent()

    private val eventJson =
        """
        {
          "event_id": "ev_midnight_departure",
          "event_type": "DEPARTURE",
          "first_reveal": 4,
          "participant_ids": [
            "e_grey"
          ],
          "story_time": {
            "kind": "RELATIVE",
            "expression": "around midnight during the heavy storm",
            "certainty": "PROBABLE"
          },
          "evidence_anchor_ids": [
            "ev_porter_statement"
          ]
        }
        """.trimIndent()

    private val evidenceAnchorJson =
        """
        {
          "evidence_id": "ev_register_entry",
          "reveal_ordinal": 1,
          "locator": {
            "href": "text/chapter01.xhtml",
            "type": "application/xhtml+xml",
            "title": "Chapter 1: The Arrival",
            "progression": 0.05,
            "total_progression": 0.015,
            "text_before": "registered under the name",
            "text_after": "in black ink."
          },
          "source_hash": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
          "excerpt_policy": "LOCATOR_ONLY"
        }
        """.trimIndent()

    private val identityLinkJson =
        """
        {
          "link_id": "link_grey_is_ishida",
          "entity_ids": [
            "e_ishida",
            "e_grey"
          ],
          "kind": "CONFIRMED",
          "first_reveal": 5,
          "evidence_anchor_id": "ev_coat_recovered"
        }
        """.trimIndent()

    private val userOverlayJson =
        """
        {
          "overlay_id": "ov_suspect_color",
          "object_type": "ENTITY",
          "object_id": "e_ishida",
          "device_id": "dev_boox_palma_01",
          "created_at": "2026-11-08T07:45:00Z",
          "created_at_reveal": 2,
          "kind": "COLOR",
          "payload": {
            "color_token": "suspect_highlight_amber",
            "pinned": true
          },
          "visibility": "FROM_CREATION"
        }
        """.trimIndent()

    @Test
    fun roundTripManifestContractExample() {
        val model = GraphJson.decodeFromString<GraphPackManifest>(manifestJson)
        assertEquals(1, model.graphSchemaVersion)
        assertEquals(WorkId("work_demo"), model.workId)
        assertEquals(EditionId("edition_demo_zh_1"), model.editionId)
        assertEquals("0.1.0", model.graphVersion)
        assertEquals("sqlite+zstd", model.packageFormat)
        assertEquals("PRIVATE_ONLY", model.publicationPolicy)
        assertEquals("LOCATOR_ONLY", model.sourceExcerptPolicy)
        assertEquals("SYNTHETIC_ORIGINAL", model.copyrightBasis)
        assertNotNull(model.counts)
        assertEquals(0, model.counts.entities)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<GraphPackManifest>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(manifestJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripEntityContractExample() {
        val model = GraphJson.decodeFromString<Entity>(entityJson)
        assertEquals(EntityId("e_ishida"), model.entityId)
        assertEquals(EntityType.PERSON, model.entityType)
        assertEquals(1, model.firstReveal)
        assertEquals(NameId("n_ishida"), model.canonicalNameId)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<Entity>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(entityJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripEntityNameContractExample() {
        val model = GraphJson.decodeFromString<EntityName>(entityNameJson)
        assertEquals(NameId("n_ishida"), model.nameId)
        assertEquals(EntityId("e_ishida"), model.entityId)
        assertEquals("Mr. Ishida", model.displayText)
        assertEquals(1, model.firstReveal)
        assertEquals(NameKind.CANONICAL, model.nameKind)
        assertEquals(EvidenceAnchorId("ev_register_entry"), model.evidenceAnchorId)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<EntityName>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(entityNameJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripAssertionContractExample() {
        val model = GraphJson.decodeFromString<Assertion>(assertionJson)
        assertEquals(AssertionId("a_grey_on_stair"), model.assertionId)
        assertEquals(EntityId("e_grey"), model.subjectId)
        assertEquals("was_present_at", model.predicate)
        assertIs<ObjectRef.Entity>(model.objectRef)
        assertEquals(EntityId("loc_locked_stair"), model.objectRef.entityId)
        assertEquals(AttributionKind.WITNESS_CLAIM, model.attribution.kind)
        assertEquals(EntityId("e_keeper"), model.attribution.speakerId)
        assertEquals(2, model.firstReveal)
        assertEquals(EvidenceAnchorId("ev_keeper_testimony"), model.evidenceAnchorId)
        assertEquals(EpistemicStatus.CLAIMED, model.epistemicStatus)
        assertEquals(0.94, model.extractionConfidence)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<Assertion>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(assertionJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripAssertionTransitionContractExample() {
        val model = GraphJson.decodeFromString<AssertionTransition>(assertionTransitionJson)
        assertEquals(TransitionId("tr_grey_on_stair_contradicted"), model.transitionId)
        assertEquals(AssertionId("a_grey_on_stair"), model.assertionId)
        assertEquals(4, model.revealOrdinal)
        assertEquals(EpistemicStatus.CONTRADICTED, model.status)
        assertEquals(EvidenceAnchorId("ev_alibi_confirmed"), model.evidenceAnchorId)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<AssertionTransition>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(assertionTransitionJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripRevealAnchorContractExample() {
        val model = GraphJson.decodeFromString<RevealAnchor>(revealAnchorJson)
        assertEquals(AnchorId("anc_ch01_p003"), model.anchorId)
        assertEquals(EditionId("ed_locked_stair_2026_en_epub"), model.editionId)
        assertEquals(3, model.ordinal)
        assertEquals(RevealKind.PARAGRAPH, model.revealKind)
        assertEquals("text/chapter01.xhtml", model.locator.href)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<RevealAnchor>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(revealAnchorJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripEventContractExample() {
        val model = GraphJson.decodeFromString<Event>(eventJson)
        assertEquals(EventId("ev_midnight_departure"), model.eventId)
        assertEquals(EventType.DEPARTURE, model.eventType)
        assertEquals(4, model.firstReveal)
        assertEquals(listOf(EntityId("e_grey")), model.participantIds)
        assertNotNull(model.storyTime)
        assertEquals(StoryTimeKind.RELATIVE, model.storyTime.kind)
        assertEquals(listOf(EvidenceAnchorId("ev_porter_statement")), model.evidenceAnchorIds)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<Event>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(GraphJson.parseToJsonElement(eventJson), GraphJson.parseToJsonElement(encoded))
    }

    @Test
    fun roundTripEvidenceAnchorContractExample() {
        val model = GraphJson.decodeFromString<EvidenceAnchor>(evidenceAnchorJson)
        assertEquals(EvidenceAnchorId("ev_register_entry"), model.evidenceId)
        assertEquals(1, model.revealOrdinal)
        assertEquals(ExcerptPolicy.LOCATOR_ONLY, model.excerptPolicy)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<EvidenceAnchor>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(evidenceAnchorJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripIdentityLinkContractExample() {
        val model = GraphJson.decodeFromString<IdentityLink>(identityLinkJson)
        assertEquals(LinkId("link_grey_is_ishida"), model.linkId)
        assertEquals(listOf(EntityId("e_ishida"), EntityId("e_grey")), model.entityIds)
        assertEquals(IdentityLinkKind.CONFIRMED, model.kind)
        assertEquals(5, model.firstReveal)
        assertEquals(EvidenceAnchorId("ev_coat_recovered"), model.evidenceAnchorId)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<IdentityLink>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(identityLinkJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripUserOverlayContractExample() {
        val model = GraphJson.decodeFromString<UserOverlay>(userOverlayJson)
        assertEquals(OverlayId("ov_suspect_color"), model.overlayId)
        assertEquals(OverlayObjectType.ENTITY, model.objectType)
        assertEquals("e_ishida", model.objectId)
        assertEquals(DeviceId("dev_boox_palma_01"), model.deviceId)
        assertEquals(OverlayKind.COLOR, model.kind)
        assertEquals(OverlayVisibility.FROM_CREATION, model.visibility)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<UserOverlay>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(userOverlayJson),
            GraphJson.parseToJsonElement(encoded),
        )
    }

    @Test
    fun roundTripObjectRefLiteral() {
        val literalWithUnit =
            """
            {
              "assertion_id": "a_grey_height",
              "subject_id": "e_grey",
              "predicate": "has_height",
              "object_ref": {
                "literal": "180",
                "unit": "cm"
              },
              "attribution": {
                "kind": "OBSERVATION"
              },
              "first_reveal": 1,
              "evidence_anchor_id": "ev_description",
              "epistemic_status": "OBSERVED"
            }
            """.trimIndent()

        val model = GraphJson.decodeFromString<Assertion>(literalWithUnit)
        assertIs<ObjectRef.Literal>(model.objectRef)
        assertEquals("180", model.objectRef.value)
        assertEquals("cm", model.objectRef.unit)

        val encoded = GraphJson.encodeToString(model)
        val roundTripped = GraphJson.decodeFromString<Assertion>(encoded)
        assertEquals(model, roundTripped)
        assertEquals(
            GraphJson.parseToJsonElement(literalWithUnit),
            GraphJson.parseToJsonElement(encoded),
        )

        val literalWithoutUnit =
            """
            {
              "assertion_id": "a_grey_eyes",
              "subject_id": "e_grey",
              "predicate": "has_eye_color",
              "object_ref": {
                "literal": "dark brown"
              },
              "attribution": {
                "kind": "OBSERVATION"
              },
              "first_reveal": 1,
              "evidence_anchor_id": "ev_description",
              "epistemic_status": "OBSERVED"
            }
            """.trimIndent()

        val modelNoUnit = GraphJson.decodeFromString<Assertion>(literalWithoutUnit)
        assertIs<ObjectRef.Literal>(modelNoUnit.objectRef)
        assertEquals("dark brown", modelNoUnit.objectRef.value)
        assertEquals(null, modelNoUnit.objectRef.unit)

        val encodedNoUnit = GraphJson.encodeToString(modelNoUnit)
        val roundTrippedNoUnit = GraphJson.decodeFromString<Assertion>(encodedNoUnit)
        assertEquals(modelNoUnit, roundTrippedNoUnit)
        assertEquals(
            GraphJson.parseToJsonElement(literalWithoutUnit),
            GraphJson.parseToJsonElement(encodedNoUnit),
        )
    }

    @Test
    fun unknownFieldsAreIgnoredOnDecodeForForwardCompatibility() {
        val jsonWithUnknownFields =
            """
            {
              "entity_id": "e_ishida",
              "entity_type": "PERSON",
              "first_reveal": 1,
              "canonical_name_id": "n_ishida",
              "notes": "Suspect note",
              "future_rfc_field": 42,
              "another_unknown_object": { "nested": true }
            }
            """.trimIndent()

        val entity = GraphJson.decodeFromString<Entity>(jsonWithUnknownFields)
        assertEquals(EntityId("e_ishida"), entity.entityId)
        assertEquals(EntityType.PERSON, entity.entityType)
        assertEquals(1, entity.firstReveal)
    }

    @Test
    fun valueClassesSerializeAsRawStrings() {
        val encodedId = GraphJson.encodeToString(EntityId("e_test"))
        assertEquals("\"e_test\"", encodedId)
    }
}
