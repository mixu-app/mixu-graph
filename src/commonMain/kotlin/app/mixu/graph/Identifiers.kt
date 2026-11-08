// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlin.jvm.JvmInline

@JvmInline
value class EntityId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EntityId cannot be empty" }
    }
}

@JvmInline
value class NameId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "NameId cannot be empty" }
    }
}

@JvmInline
value class AssertionId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "AssertionId cannot be empty" }
    }
}

@JvmInline
value class TransitionId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "TransitionId cannot be empty" }
    }
}

@JvmInline
value class AnchorId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "AnchorId cannot be empty" }
    }
}

@JvmInline
value class EvidenceAnchorId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EvidenceAnchorId cannot be empty" }
    }
}

@JvmInline
value class EditionId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "EditionId cannot be empty" }
    }
}

@JvmInline
value class WorkId(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "WorkId cannot be empty" }
    }
}
