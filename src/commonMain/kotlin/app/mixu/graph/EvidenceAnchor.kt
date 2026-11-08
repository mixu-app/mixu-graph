// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Excerpt policy governing quotation of narrative source text.
 */
@Serializable
enum class ExcerptPolicy {
    LOCATOR_ONLY,
    BOUNDED_QUOTE,
    PUBLIC_DOMAIN_QUOTE,
}

/**
 * Source location backing a claim or narrative assertion.
 */
@Serializable
data class EvidenceAnchor(
    @SerialName("evidence_id")
    val evidenceId: EvidenceAnchorId,
    @SerialName("reveal_ordinal")
    val revealOrdinal: Int,
    @SerialName("locator")
    val locator: Locator,
    @SerialName("source_hash")
    val sourceHash: String? = null,
    @SerialName("excerpt_policy")
    val excerptPolicy: ExcerptPolicy,
    @SerialName("excerpt")
    val excerpt: String? = null,
) : Comparable<EvidenceAnchor> {
    init {
        require(revealOrdinal >= 0) { "revealOrdinal must be non-negative" }
        if (sourceHash != null) {
            require(sourceHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "sourceHash must be a 64-char lowercase hex string"
            }
        }
        if (excerpt != null) {
            require(excerpt.length <= 600) { "excerpt cannot exceed 600 characters" }
        }
    }

    override fun compareTo(other: EvidenceAnchor): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<EvidenceAnchor> =
            compareBy<EvidenceAnchor>({ it.revealOrdinal }, { it.evidenceId.value })
    }
}
