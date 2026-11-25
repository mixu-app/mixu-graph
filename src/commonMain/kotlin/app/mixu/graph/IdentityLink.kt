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
    @SerialName("reveal_char_end")
    val revealCharEnd: Int? = null,
    @SerialName("epistemic_status")
    val epistemicStatus: EpistemicStatus? = null,
    @SerialName("frame_id")
    val frameId: String? = null,
) : Comparable<IdentityLink> {
    init {
        require(entityIds.size == 2) { "entityIds must contain exactly two entities" }
        require(entityIds[0] != entityIds[1]) { "entityIds must contain distinct entities" }
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
        if (revealCharEnd != null) {
            require(revealCharEnd >= 0) { "revealCharEnd must be non-negative" }
        }
        if (frameId != null) {
            require(frameId.isNotBlank()) { "frameId cannot be blank when provided" }
        }
    }

    /**
     * Returns true when this link is eligible to collapse DSU entity clusters (INV-3):
     * unframed and OBSERVED (or omitted epistemic_status for v1 backward compatibility).
     */
    fun isCollapsible(): Boolean =
        frameId == null &&
            (epistemicStatus == null || epistemicStatus == EpistemicStatus.OBSERVED)

    override fun compareTo(other: IdentityLink): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<IdentityLink> =
            compareBy<IdentityLink>({ it.firstReveal }, { it.linkId.value })
    }
}
