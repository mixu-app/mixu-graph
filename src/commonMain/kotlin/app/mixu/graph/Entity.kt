// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Coarse taxonomy for graph entities.
 */
@Serializable
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
@Serializable
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
@Serializable
data class EntityName(
    @SerialName("name_id")
    val nameId: NameId,
    @SerialName("entity_id")
    val entityId: EntityId,
    @SerialName("display_text")
    val displayText: String,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("name_kind")
    val nameKind: NameKind,
    @SerialName("evidence_anchor_id")
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
@Serializable
data class Entity(
    @SerialName("entity_id")
    val entityId: EntityId,
    @SerialName("entity_type")
    val entityType: EntityType,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("canonical_name_id")
    val canonicalNameId: NameId? = null,
    @SerialName("notes")
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
