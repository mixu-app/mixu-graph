// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

/**
 * Coarse taxonomy for graph entities.
 */
enum class EntityType {
    PERSON,
    PERSONA,
    LOCATION,
    ORGANIZATION,
    OBJECT,
}

/**
 * Classification of an entity name disclosure.
 */
enum class NameKind {
    CANONICAL,
    ALIAS,
    HONORIFIC,
    PSEUDONYM,
    DESCRIPTION,
    TITLE,
}

/**
 * A display name or alias with its own reveal boundary.
 */
data class EntityName(
    val nameId: NameId,
    val entityId: EntityId,
    val displayText: String,
    val firstReveal: Int,
    val nameKind: NameKind,
    val evidenceAnchorId: EvidenceAnchorId? = null,
) {
    init {
        require(displayText.isNotBlank()) { "displayText cannot be blank" }
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
    }
}

/**
 * A core narrative entity.
 */
data class Entity(
    val entityId: EntityId,
    val entityType: EntityType,
    val firstReveal: Int,
    val canonicalNameId: NameId? = null,
    val notes: String? = null,
) {
    init {
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
    }
}
