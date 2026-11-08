// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Kind of graph object targeted by a user overlay annotation.
 */
@Serializable
enum class OverlayObjectType {
    ENTITY,
    ASSERTION,
    EVENT,
    EDGE,
}

/**
 * Visual or semantic type of user annotation.
 */
@Serializable
enum class OverlayKind {
    COLOR,
    ALIAS,
    NOTE,
    HYPOTHESIS_EDGE,
    CORRECTION,
}

/**
 * Temporal visibility policy for reader annotations during replay.
 */
@Serializable
enum class OverlayVisibility {
    ALWAYS,
    FROM_CREATION,
}

/**
 * Private reader annotation referencing stable graph identifiers.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UserOverlay(
    @SerialName("overlay_id")
    val overlayId: OverlayId,
    @SerialName("object_type")
    val objectType: OverlayObjectType,
    @SerialName("object_id")
    val objectId: String,
    @SerialName("device_id")
    val deviceId: DeviceId? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("created_at_reveal")
    val createdAtReveal: Int,
    @SerialName("kind")
    val kind: OverlayKind,
    @SerialName("payload")
    val payload: JsonObject,
    @EncodeDefault
    @SerialName("visibility")
    val visibility: OverlayVisibility = OverlayVisibility.FROM_CREATION,
) : Comparable<UserOverlay> {
    init {
        require(createdAtReveal >= 0) { "createdAtReveal must be non-negative" }
    }

    override fun compareTo(other: UserOverlay): Int = STABLE_COMPARATOR.compare(this, other)

    companion object {
        val STABLE_COMPARATOR: Comparator<UserOverlay> =
            compareBy<UserOverlay>({ it.createdAtReveal }, { it.overlayId.value })
    }
}
