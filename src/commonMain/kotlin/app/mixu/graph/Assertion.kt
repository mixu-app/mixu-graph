// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kind of source attribution for an assertion.
 */
@Serializable
enum class AttributionKind {
    OBSERVATION,
    NARRATOR,
    WITNESS_CLAIM,
    READER_INFERENCE,
    LATER_CONFIRMATION,
}

/**
 * Source attribution class.
 */
@Serializable
data class Attribution(
    @SerialName("kind")
    val kind: AttributionKind,
    @SerialName("speaker_id")
    val speakerId: EntityId? = null,
    @SerialName("context_anchor_id")
    val contextAnchorId: AnchorId? = null,
)

/**
 * Epistemic status of a claim or observation in the narrative.
 */
@Serializable
enum class EpistemicStatus {
    OBSERVED,
    CLAIMED,
    INFERRED,
    CORROBORATED,
    CONTRADICTED,
    RETRACTED,
    UNRESOLVED,
}

/**
 * Object of an assertion: either a reference to an entity or a literal value.
 */
@Serializable(with = ObjectRefSerializer::class)
sealed interface ObjectRef {
    @Serializable
    data class Entity(
        @SerialName("entity_id")
        val entityId: EntityId,
    ) : ObjectRef

    @Serializable
    data class Literal(
        @SerialName("literal")
        val value: String,
        @SerialName("unit")
        val unit: String? = null,
    ) : ObjectRef {
        init {
            require(value.isNotBlank()) { "literal value cannot be blank" }
        }
    }
}

object ObjectRefSerializer : KSerializer<ObjectRef> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("app.mixu.graph.ObjectRef") {
            element<String>("entity_id", isOptional = true)
            element<String>("literal", isOptional = true)
            element<String>("unit", isOptional = true)
        }

    override fun serialize(
        encoder: Encoder,
        value: ObjectRef,
    ) {
        if (encoder is JsonEncoder) {
            val jsonObject =
                buildJsonObject {
                    when (value) {
                        is ObjectRef.Entity -> put("entity_id", JsonPrimitive(value.entityId.value))
                        is ObjectRef.Literal -> {
                            put("literal", JsonPrimitive(value.value))
                            value.unit?.let { put("unit", JsonPrimitive(it)) }
                        }
                    }
                }
            encoder.encodeJsonElement(jsonObject)
        } else {
            throw SerializationException("Only JSON serialization is supported for ObjectRef")
        }
    }

    override fun deserialize(decoder: Decoder): ObjectRef {
        if (decoder is JsonDecoder) {
            val jsonObject = decoder.decodeJsonElement().jsonObject
            val entityId = jsonObject["entity_id"]?.jsonPrimitive?.contentOrNull
            val literal = jsonObject["literal"]?.jsonPrimitive?.contentOrNull
            val unit = jsonObject["unit"]?.jsonPrimitive?.contentOrNull

            return when {
                entityId != null -> ObjectRef.Entity(EntityId(entityId))
                literal != null -> ObjectRef.Literal(value = literal, unit = unit)
                else -> throw SerializationException(
                    "ObjectRef must contain either 'entity_id' or 'literal'",
                )
            }
        } else {
            throw SerializationException("Only JSON deserialization is supported for ObjectRef")
        }
    }
}

/**
 * An assertion representing a typed proposition attributed to a source.
 */
@Serializable
data class Assertion(
    @SerialName("assertion_id")
    val assertionId: AssertionId,
    @SerialName("subject_id")
    val subjectId: EntityId,
    @SerialName("predicate")
    val predicate: String,
    @SerialName("object_ref")
    val objectRef: ObjectRef,
    @SerialName("qualifier")
    val qualifier: Map<String, String> = emptyMap(),
    @SerialName("attribution")
    val attribution: Attribution,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: EvidenceAnchorId,
    @SerialName("epistemic_status")
    val epistemicStatus: EpistemicStatus,
    @SerialName("extraction_confidence")
    val extractionConfidence: Double? = null,
) : Comparable<Assertion> {
    init {
        require(predicate.isNotBlank()) { "predicate cannot be blank" }
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
        if (extractionConfidence != null) {
            require(extractionConfidence in 0.0..1.0) {
                "extractionConfidence must be between 0.0 and 1.0"
            }
        }
    }

    override fun compareTo(other: Assertion): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<Assertion> =
            compareBy<Assertion>({ it.firstReveal }, { it.assertionId.value })
    }
}

/**
 * Append-only status change revealed at a later ordinal.
 */
@Serializable
data class AssertionTransition(
    @SerialName("transition_id")
    val transitionId: TransitionId,
    @SerialName("assertion_id")
    val assertionId: AssertionId,
    @SerialName("reveal_ordinal")
    val revealOrdinal: Int,
    @SerialName("status")
    val status: EpistemicStatus,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: EvidenceAnchorId? = null,
    @SerialName("reason")
    val reason: String? = null,
) : Comparable<AssertionTransition> {
    init {
        require(revealOrdinal >= 0) { "revealOrdinal must be non-negative" }
    }

    override fun compareTo(other: AssertionTransition): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<AssertionTransition> =
            compareBy<AssertionTransition>({ it.revealOrdinal }, { it.transitionId.value })
    }
}
