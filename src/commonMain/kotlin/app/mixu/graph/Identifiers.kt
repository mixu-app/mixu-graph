// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class EntityId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EntityId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class NameId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "NameId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class AssertionId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "AssertionId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class TransitionId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "TransitionId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class AnchorId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "AnchorId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class EvidenceAnchorId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EvidenceAnchorId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class EditionId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EditionId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class WorkId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "WorkId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class EventId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EventId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class LinkId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "LinkId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class OverlayId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "OverlayId cannot be empty" }
    }
}

@Serializable
@JvmInline
value class DeviceId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "DeviceId cannot be empty" }
    }
}
