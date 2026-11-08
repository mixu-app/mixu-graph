// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Coarse taxonomy of narrative events.
 */
@Serializable
enum class EventType {
    SCENE,
    DISCOVERY,
    TESTIMONY,
    DEATH,
    ARRIVAL,
    DEPARTURE,
    OTHER,
}

/**
 * Kind of narrative story time specification.
 */
@Serializable
enum class StoryTimeKind {
    EXACT,
    INTERVAL,
    RELATIVE,
    UNKNOWN,
    DISPUTED,
}

/**
 * Certainty level for story time expressions.
 */
@Serializable
enum class StoryTimeCertainty {
    CERTAIN,
    PROBABLE,
    UNCERTAIN,
}

/**
 * Partial, uncertain or relative time representation.
 */
@Serializable
data class StoryTime(
    @SerialName("kind")
    val kind: StoryTimeKind,
    @SerialName("earliest")
    val earliest: String? = null,
    @SerialName("latest")
    val latest: String? = null,
    @SerialName("expression")
    val expression: String? = null,
    @SerialName("certainty")
    val certainty: StoryTimeCertainty? = null,
)

/**
 * An event that occurs in the story world.
 */
@Serializable
data class Event(
    @SerialName("event_id")
    val eventId: EventId,
    @SerialName("event_type")
    val eventType: EventType,
    @SerialName("first_reveal")
    val firstReveal: Int,
    @SerialName("participant_ids")
    val participantIds: List<EntityId> = emptyList(),
    @SerialName("story_time")
    val storyTime: StoryTime? = null,
    @SerialName("evidence_anchor_ids")
    val evidenceAnchorIds: List<EvidenceAnchorId> = emptyList(),
) : Comparable<Event> {
    init {
        require(firstReveal >= 0) { "firstReveal must be non-negative" }
    }

    override fun compareTo(other: Event): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<Event> =
            compareBy<Event>({ it.firstReveal }, { it.eventId.value })
    }
}
