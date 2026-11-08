// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

/**
 * Provenance metadata for extracted graph packs.
 */
data class Provenance(
    val validatorVersion: String,
    val extractionVersion: String? = null,
    val modelId: String? = null,
    val promptVersion: String? = null,
    val qualityReportId: String? = null,
) {
    init {
        require(validatorVersion.isNotBlank()) { "validatorVersion cannot be blank" }
    }
}

/**
 * Element counts stored in a graph pack manifest.
 */
data class ManifestCounts(
    val entities: Int = 0,
    val assertions: Int = 0,
    val events: Int = 0,
    val evidenceAnchors: Int = 0,
) {
    init {
        require(entities >= 0) { "entities count cannot be negative" }
        require(assertions >= 0) { "assertions count cannot be negative" }
        require(events >= 0) { "events count cannot be negative" }
        require(evidenceAnchors >= 0) { "evidenceAnchors count cannot be negative" }
    }
}

/**
 * Package manifest for a downloadable or locally generated graph pack.
 */
data class GraphPackManifest(
    val graphSchemaVersion: Int,
    val workId: WorkId,
    val editionId: EditionId,
    val graphVersion: String,
    val revealAnchorMapVersion: Int,
    val checksumSha256: String,
    val packageFormat: String,
    val publicationPolicy: String,
    val sourceExcerptPolicy: String,
    val createdAt: String,
    val copyrightBasis: String,
    val provenance: Provenance? = null,
    val counts: ManifestCounts? = null,
) {
    init {
        require(graphSchemaVersion >= 1) { "graphSchemaVersion must be >= 1" }
        require(revealAnchorMapVersion >= 1) { "revealAnchorMapVersion must be >= 1" }
        require(checksumSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "checksumSha256 must be a 64-char lowercase hex string"
        }
        require(graphVersion.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
            "graphVersion must be a semver string (e.g. 1.0.0)"
        }
    }
}

/**
 * In-memory representation of an immutable narrative graph pack.
 */
data class GraphPack(
    val manifest: GraphPackManifest,
    val entities: List<Entity>,
    val names: List<EntityName>,
    val assertions: List<Assertion>,
    val transitions: List<AssertionTransition> = emptyList(),
    val anchors: List<RevealAnchor> = emptyList(),
) {
    init {
        validateInvariants()
    }

    private fun validateInvariants() {
        // 1. Anchors must have dense monotonic ordinals starting from 0
        val sortedAnchors = anchors.sortedBy { it.ordinal }
        for (i in sortedAnchors.indices) {
            require(sortedAnchors[i].ordinal == i) {
                "Anchors must have dense monotonic ordinals starting from 0. Expected $i but got ${sortedAnchors[i].ordinal}"
            }
        }

        // 2. No edition mixing in anchors
        for (anchor in anchors) {
            require(anchor.editionId == manifest.editionId) {
                "Edition mixing forbidden: anchor ${anchor.anchorId.value} edition ${anchor.editionId.value} does not match pack edition ${manifest.editionId.value}"
            }
        }

        // 3. Entity and Name integrity
        val entityMap = entities.associateBy { it.entityId }
        val nameMap = names.associateBy { it.nameId }

        for (name in names) {
            require(entityMap.containsKey(name.entityId)) {
                "EntityName ${name.nameId.value} references unknown entity ${name.entityId.value}"
            }
        }

        for (entity in entities) {
            if (entity.canonicalNameId != null) {
                val name = nameMap[entity.canonicalNameId]
                require(name != null) {
                    "Entity ${entity.entityId.value} references unknown canonical name ${entity.canonicalNameId.value}"
                }
                require(name.entityId == entity.entityId) {
                    "Canonical name ${entity.canonicalNameId.value} belongs to entity ${name.entityId.value}, not ${entity.entityId.value}"
                }
            }
        }

        // 4. Assertion subject & object entity integrity
        val assertionMap = assertions.associateBy { it.assertionId }
        for (assertion in assertions) {
            require(entityMap.containsKey(assertion.subjectId)) {
                "Assertion ${assertion.assertionId.value} references unknown subject entity ${assertion.subjectId.value}"
            }
            if (assertion.objectRef is ObjectRef.Entity) {
                require(entityMap.containsKey(assertion.objectRef.entityId)) {
                    "Assertion ${assertion.assertionId.value} references unknown object entity ${assertion.objectRef.entityId.value}"
                }
            }
        }

        // 5. Transition integrity
        for (transition in transitions) {
            val assertion = assertionMap[transition.assertionId]
            require(assertion != null) {
                "Transition ${transition.transitionId.value} references unknown assertion ${transition.assertionId.value}"
            }
            require(transition.revealOrdinal >= assertion.firstReveal) {
                "Transition ${transition.transitionId.value} revealOrdinal (${transition.revealOrdinal}) cannot precede assertion firstReveal (${assertion.firstReveal})"
            }
        }
    }

    /**
     * Returns a copy of this GraphPack with all collections sorted deterministically.
     */
    fun canonical(): GraphPack =
        if (isCanonical()) {
            this
        } else {
            GraphPack(
                manifest = manifest,
                entities = entities.sortedWith(Entity.STABLE_COMPARATOR),
                names = names.sortedWith(EntityName.STABLE_COMPARATOR),
                assertions = assertions.sortedWith(Assertion.STABLE_COMPARATOR),
                transitions = transitions.sortedWith(AssertionTransition.STABLE_COMPARATOR),
                anchors = anchors.sortedWith(RevealAnchor.STABLE_COMPARATOR),
            )
        }

    /**
     * Checks whether all collections in this GraphPack are already in canonical order.
     */
    fun isCanonical(): Boolean =
        isSortedWith(entities, Entity.STABLE_COMPARATOR) &&
            isSortedWith(names, EntityName.STABLE_COMPARATOR) &&
            isSortedWith(assertions, Assertion.STABLE_COMPARATOR) &&
            isSortedWith(transitions, AssertionTransition.STABLE_COMPARATOR) &&
            isSortedWith(anchors, RevealAnchor.STABLE_COMPARATOR)

    companion object {
        /**
         * Factory function that constructs a canonical [GraphPack] by sorting all collections.
         */
        fun canonical(
            manifest: GraphPackManifest,
            entities: List<Entity>,
            names: List<EntityName>,
            assertions: List<Assertion>,
            transitions: List<AssertionTransition> = emptyList(),
            anchors: List<RevealAnchor> = emptyList(),
        ): GraphPack =
            GraphPack(
                manifest = manifest,
                entities = entities.sortedWith(Entity.STABLE_COMPARATOR),
                names = names.sortedWith(EntityName.STABLE_COMPARATOR),
                assertions = assertions.sortedWith(Assertion.STABLE_COMPARATOR),
                transitions = transitions.sortedWith(AssertionTransition.STABLE_COMPARATOR),
                anchors = anchors.sortedWith(RevealAnchor.STABLE_COMPARATOR),
            )
    }
}

private fun <T> isSortedWith(
    list: List<T>,
    comparator: Comparator<T>,
): Boolean {
    for (i in 0 until list.size - 1) {
        if (comparator.compare(list[i], list[i + 1]) > 0) {
            return false
        }
    }
    return true
}
