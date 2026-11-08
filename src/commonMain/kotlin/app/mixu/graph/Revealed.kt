// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

/**
 * An entity viewed under a specific reveal boundary.
 */
data class RevealedEntity(
    val entity: Entity,
    val canonicalName: EntityName?,
    val allRevealedNames: List<EntityName>,
    val viewOrdinal: Int,
) : Comparable<RevealedEntity> {
    init {
        require(viewOrdinal >= entity.firstReveal) {
            "Entity ${entity.entityId.value} firstReveal (${entity.firstReveal}) cannot exceed viewOrdinal ($viewOrdinal)"
        }
        for (name in allRevealedNames) {
            require(viewOrdinal >= name.firstReveal) {
                "RevealedName ${name.nameId.value} firstReveal (${name.firstReveal}) cannot exceed viewOrdinal ($viewOrdinal)"
            }
        }
    }

    override fun compareTo(other: RevealedEntity): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<RevealedEntity> =
            compareBy<RevealedEntity>({ it.entity.firstReveal }, { it.entity.entityId.value })
    }
}

/**
 * An assertion viewed under a specific reveal boundary with its active epistemic status.
 */
data class RevealedAssertion(
    val assertion: Assertion,
    val currentStatus: EpistemicStatus,
    val activeTransition: AssertionTransition? = null,
    val viewOrdinal: Int,
) : Comparable<RevealedAssertion> {
    init {
        require(viewOrdinal >= assertion.firstReveal) {
            "Assertion ${assertion.assertionId.value} firstReveal (${assertion.firstReveal}) cannot exceed viewOrdinal ($viewOrdinal)"
        }
        if (activeTransition != null) {
            require(viewOrdinal >= activeTransition.revealOrdinal) {
                "Active transition ${activeTransition.transitionId.value} revealOrdinal (${activeTransition.revealOrdinal}) cannot exceed viewOrdinal ($viewOrdinal)"
            }
        }
    }

    override fun compareTo(other: RevealedAssertion): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<RevealedAssertion> =
            compareBy<RevealedAssertion>(
                { it.assertion.firstReveal },
                { it.assertion.assertionId.value },
            )
    }
}

/**
 * Complete immutable snapshot of the graph visible at a specific knowledge horizon.
 */
data class RevealedGraphView(
    val editionId: EditionId,
    val viewOrdinal: Int,
    val entities: List<RevealedEntity>,
    val assertions: List<RevealedAssertion>,
) {
    init {
        require(viewOrdinal >= 0) { "viewOrdinal must be non-negative" }
    }
}
