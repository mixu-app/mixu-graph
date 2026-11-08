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
) : Comparable<EntityName> {
    init {
        require(displayText.isNotBlank()) { "displayText cannot be blank" }
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
    }

    override fun compareTo(other: EntityName): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<EntityName> =
            compareBy<EntityName>({ it.firstReveal }, { it.nameKind.ordinal }, { it.nameId.value })
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
) : Comparable<Entity> {
    init {
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
    }

    override fun compareTo(other: Entity): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<Entity> =
            compareBy<Entity>({ it.firstReveal }, { it.entityId.value })
    }
}
