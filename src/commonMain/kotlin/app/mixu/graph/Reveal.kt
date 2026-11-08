// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

/**
 * Coarse classification used for chapter-level rewind and UI grouping.
 */
enum class RevealKind {
    CHAPTER_START,
    PARAGRAPH,
    IMAGE,
    NOTE,
}

/**
 * Normalized source location. Page numbers are never used.
 */
data class Locator(
    val href: String,
    val type: String,
    val title: String? = null,
    val progression: Double? = null,
    val totalProgression: Double? = null,
    val textBefore: String? = null,
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
data class RevealAnchor(
    val anchorId: AnchorId,
    val editionId: EditionId,
    val ordinal: Int,
    val locator: Locator,
    val revealKind: RevealKind,
    val textHash: String? = null,
) {
    init {
        require(ordinal >= 0) { "ordinal must be non-negative" }
        if (textHash != null) {
            require(textHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "textHash must be a 64-char lowercase hex string"
            }
        }
    }
}

/**
 * Monotonic, edition-bound upper horizon boundary for graph viewing.
 */
data class RevealBoundary(
    val editionId: EditionId,
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
