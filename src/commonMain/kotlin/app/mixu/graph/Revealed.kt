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
    val viewCharOffset: Int? = null,
    val resolvedEntityId: EntityId = entity.entityId,
) : Comparable<RevealedEntity> {
    init {
        require(
            isDisclosedAt(entity.firstReveal, entity.revealCharEnd, viewOrdinal, viewCharOffset),
        ) {
            "Entity ${entity.entityId.value} (${entity.firstReveal}, ${entity.revealCharEnd}) exceeds ($viewOrdinal, $viewCharOffset)"
        }
        for (name in allRevealedNames) {
            require(
                isDisclosedAt(name.firstReveal, name.revealCharEnd, viewOrdinal, viewCharOffset),
            ) {
                "RevealedName ${name.nameId.value} (${name.firstReveal}, ${name.revealCharEnd}) exceeds ($viewOrdinal, $viewCharOffset)"
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
    val viewCharOffset: Int? = null,
) : Comparable<RevealedAssertion> {
    init {
        require(
            isDisclosedAt(
                assertion.firstReveal,
                assertion.revealCharEnd,
                viewOrdinal,
                viewCharOffset,
            ),
        ) {
            "Assertion ${assertion.assertionId.value} (${assertion.firstReveal}, ${assertion.revealCharEnd}) exceeds ($viewOrdinal, $viewCharOffset)"
        }
        if (activeTransition != null) {
            require(
                isDisclosedAt(
                    activeTransition.revealOrdinal,
                    activeTransition.revealCharEnd,
                    viewOrdinal,
                    viewCharOffset,
                ),
            ) {
                "Active transition ${activeTransition.transitionId.value} (${activeTransition.revealOrdinal}, ${activeTransition.revealCharEnd}) exceeds ($viewOrdinal, $viewCharOffset)"
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
    val viewCharOffset: Int? = null,
    val identityLinks: List<IdentityLink> = emptyList(),
    val collapsibleIdentityLinks: List<IdentityLink> = emptyList(),
    val hypothesizedIdentityLinks: List<IdentityLink> = emptyList(),
    val dsuClusters: Map<EntityId, List<EntityId>> = emptyMap(),
) {
    init {
        require(viewOrdinal >= 0) { "viewOrdinal must be non-negative" }
        if (viewCharOffset != null) {
            require(viewCharOffset >= 0) { "viewCharOffset must be non-negative" }
        }
    }
}
