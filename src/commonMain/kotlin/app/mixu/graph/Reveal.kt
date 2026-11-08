// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Coarse classification used for chapter-level rewind and UI grouping.
 */
@Serializable
enum class RevealKind {
    @SerialName("chapter_start")
    CHAPTER_START,

    @SerialName("paragraph")
    PARAGRAPH,

    @SerialName("image")
    IMAGE,

    @SerialName("note")
    NOTE,
}

/**
 * Normalized source location. Page numbers are never used.
 */
@Serializable
data class Locator(
    @SerialName("href")
    val href: String,
    @SerialName("type")
    val type: String,
    @SerialName("title")
    val title: String? = null,
    @SerialName("progression")
    val progression: Double? = null,
    @SerialName("total_progression")
    val totalProgression: Double? = null,
    @SerialName("text_before")
    val textBefore: String? = null,
    @SerialName("text_after")
    val textAfter: String? = null,
) {
    init {
        require(href.isNotBlank()) { "href cannot be blank" }
        require(type.isNotBlank()) { "type cannot be blank" }
        if (progression != null) {
            require(progression in 0.0..1.0) { "progression must be between 0.0 and 1.0" }
        }
        if (totalProgression != null) {
            require(totalProgression in 0.0..1.0) { "totalProgression must be between 0.0 and 1.0" }
        }
    }
}

/**
 * Edition-specific location that orders disclosures.
 */
@Serializable
data class RevealAnchor(
    @SerialName("anchor_id")
    val anchorId: AnchorId,
    @SerialName("edition_id")
    val editionId: EditionId,
    @SerialName("ordinal")
    val ordinal: Int,
    @SerialName("locator")
    val locator: Locator,
    @SerialName("reveal_kind")
    val revealKind: RevealKind,
    @SerialName("text_hash")
    val textHash: String? = null,
) : Comparable<RevealAnchor> {
    init {
        require(ordinal >= 0) { "ordinal must be non-negative" }
        if (textHash != null) {
            require(textHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "textHash must be a 64-char lowercase hex string"
            }
        }
    }

    override fun compareTo(other: RevealAnchor): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<RevealAnchor> =
            compareBy<RevealAnchor> { it.ordinal }
    }
}

/**
 * Monotonic, edition-bound upper horizon boundary for graph viewing.
 */
@Serializable
data class RevealBoundary(
    @SerialName("edition_id")
    val editionId: EditionId,
    @SerialName("max_ordinal")
    val maxOrdinal: Int,
) {
    init {
        require(maxOrdinal >= 0) { "maxOrdinal must be non-negative" }
    }

    fun isPermitted(
        ordinal: Int,
        targetEditionId: EditionId,
    ): Boolean = targetEditionId == editionId && ordinal <= maxOrdinal
}
