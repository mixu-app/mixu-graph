// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

/**
 * Kind of source attribution for an assertion.
 */
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
data class Attribution(
    val kind: AttributionKind,
    val speakerId: EntityId? = null,
    val contextAnchorId: AnchorId? = null,
)

/**
 * Epistemic status of a claim or observation in the narrative.
 */
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
sealed interface ObjectRef {
    data class Entity(
        val entityId: EntityId,
    ) : ObjectRef

    data class Literal(
        val value: String,
        val unit: String? = null,
    ) : ObjectRef {
        init {
            require(value.isNotBlank()) { "literal value cannot be blank" }
        }
    }
}

/**
 * An assertion representing a typed proposition attributed to a source.
 */
data class Assertion(
    val assertionId: AssertionId,
    val subjectId: EntityId,
    val predicate: String,
    val objectRef: ObjectRef,
    val qualifier: Map<String, String> = emptyMap(),
    val attribution: Attribution,
    val firstReveal: Int,
    val evidenceAnchorId: EvidenceAnchorId,
    val epistemicStatus: EpistemicStatus,
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
data class AssertionTransition(
    val transitionId: TransitionId,
    val assertionId: AssertionId,
    val revealOrdinal: Int,
    val status: EpistemicStatus,
    val evidenceAnchorId: EvidenceAnchorId? = null,
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
