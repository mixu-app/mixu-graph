// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kind of identity resolution between entities.
 */
@Serializable
enum class IdentityLinkKind {
    CANDIDATE,
    SUPPORTED,
    CONFIRMED,
}

/**
 * Reveal-scoped link asserting identity resolution between two entities.
 */
@Serializable
data class IdentityLink(
    @SerialName("link_id")
    val linkId: LinkId,
    @SerialName("entity_ids")
    val entityIds: List<EntityId>,
    @SerialName("kind")
    val kind: IdentityLinkKind,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("evidence_anchor_id")
    val evidenceAnchorId: EvidenceAnchorId? = null,
) : Comparable<IdentityLink> {
    init {
        require(entityIds.size == 2) { "entityIds must contain exactly two entities" }
        require(entityIds[0] != entityIds[1]) { "entityIds must contain distinct entities" }
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
    }

    override fun compareTo(other: IdentityLink): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<IdentityLink> =
            compareBy<IdentityLink>({ it.firstReveal }, { it.linkId.value })
    }
}
