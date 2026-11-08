// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

/**
 * Result of a bounded neighbourhood query around a focus entity.
 */
data class BoundedNeighbourhood(
    val focusEntity: RevealedEntity,
    val neighbours: List<RevealedEntity>,
    val assertions: List<RevealedAssertion>,
    val depth: Int,
)

/**
 * Search hit over a filtered projection.
 */
data class GraphSearchHit(
    val entity: RevealedEntity,
    val matchedName: EntityName,
    val matchedText: String,
)

/**
 * Pure projection engine that executes queries directly against a [GraphPack] under a given reveal ordinal.
 * Filtering happens in the query layer; unfiltered structures are never materialized.
 */
class TemporalProjectionEngine(
    val pack: GraphPack,
) {
    /**
     * Compute the full revealed graph view at ordinal [viewOrdinal] for the pack's edition.
     */
    fun project(viewOrdinal: Int): RevealedGraphView {
        require(viewOrdinal >= 0) { "viewOrdinal must be non-negative" }

        val visibleEntities = mutableListOf<RevealedEntity>()
        val visibleEntityMap = mutableMapOf<EntityId, RevealedEntity>()

        for (entity in pack.entities) {
            if (entity.firstReveal <= viewOrdinal) {
                val revealedNames =
                    pack.names.filter {
                        it.entityId == entity.entityId && it.firstReveal <= viewOrdinal
                    }
                val canonicalName =
                    revealedNames.find { it.nameId == entity.canonicalNameId }
                        ?: revealedNames.minByOrNull { it.firstReveal }

                val revealed =
                    RevealedEntity(
                        entity = entity,
                        canonicalName = canonicalName,
                        allRevealedNames = revealedNames,
                        viewOrdinal = viewOrdinal,
                    )
                visibleEntities.add(revealed)
                visibleEntityMap[entity.entityId] = revealed
            }
        }

        val visibleAssertions = mutableListOf<RevealedAssertion>()
        for (assertion in pack.assertions) {
            if (assertion.firstReveal <= viewOrdinal) {
                if (!visibleEntityMap.containsKey(assertion.subjectId)) continue

                if (assertion.objectRef is ObjectRef.Entity) {
                    if (!visibleEntityMap.containsKey(assertion.objectRef.entityId)) continue
                }

                val validTransitions =
                    pack.transitions
                        .filter {
                            it.assertionId == assertion.assertionId &&
                                it.revealOrdinal <= viewOrdinal
                        }.sortedBy { it.revealOrdinal }
                val activeTransition = validTransitions.lastOrNull()
                val currentStatus = activeTransition?.status ?: assertion.epistemicStatus

                visibleAssertions.add(
                    RevealedAssertion(
                        assertion = assertion,
                        currentStatus = currentStatus,
                        activeTransition = activeTransition,
                        viewOrdinal = viewOrdinal,
                    ),
                )
            }
        }

        return RevealedGraphView(
            editionId = pack.manifest.editionId,
            viewOrdinal = viewOrdinal,
            entities = visibleEntities,
            assertions = visibleAssertions,
        )
    }

    /**
     * Entity lookup: returns RevealedEntity if disclosed at or before [viewOrdinal], null otherwise.
     */
    fun findEntity(
        entityId: EntityId,
        viewOrdinal: Int,
    ): RevealedEntity? {
        val entity =
            pack.entities.find { it.entityId == entityId && it.firstReveal <= viewOrdinal }
                ?: return null
        val revealedNames =
            pack.names.filter {
                it.entityId == entityId && it.firstReveal <= viewOrdinal
            }
        val canonicalName =
            revealedNames.find { it.nameId == entity.canonicalNameId }
                ?: revealedNames.minByOrNull { it.firstReveal }

        return RevealedEntity(
            entity = entity,
            canonicalName = canonicalName,
            allRevealedNames = revealedNames,
            viewOrdinal = viewOrdinal,
        )
    }

    /**
     * Assertions touching a specific entity (as subject or object) visible at [viewOrdinal].
     */
    fun findAssertionsForEntity(
        entityId: EntityId,
        viewOrdinal: Int,
    ): List<RevealedAssertion> {
        findEntity(entityId, viewOrdinal) ?: return emptyList()
        val view = project(viewOrdinal)
        return view.assertions.filter {
            it.assertion.subjectId == entityId ||
                (
                    it.assertion.objectRef is ObjectRef.Entity &&
                        it.assertion.objectRef.entityId == entityId
                )
        }
    }

    /**
     * Bounded neighbourhood around a focus entity up to given [depth] (1 or 2).
     */
    fun boundedNeighbourhood(
        focusEntityId: EntityId,
        viewOrdinal: Int,
        depth: Int = 1,
    ): BoundedNeighbourhood? {
        require(depth in 1..2) { "Depth must be 1 or 2" }
        val focus = findEntity(focusEntityId, viewOrdinal) ?: return null
        val view = project(viewOrdinal)

        val visitedEntityIds = mutableSetOf(focusEntityId)
        val collectedAssertions = mutableListOf<RevealedAssertion>()
        var currentFrontier = setOf(focusEntityId)

        for (d in 1..depth) {
            val nextFrontier = mutableSetOf<EntityId>()
            for (assertion in view.assertions) {
                val subjectId = assertion.assertion.subjectId
                val objectId = (assertion.assertion.objectRef as? ObjectRef.Entity)?.entityId

                val matchesSubject = currentFrontier.contains(subjectId)
                val matchesObject = objectId != null && currentFrontier.contains(objectId)

                if (matchesSubject || matchesObject) {
                    if (!collectedAssertions.contains(assertion)) {
                        collectedAssertions.add(assertion)
                    }
                    if (matchesSubject &&
                        objectId != null &&
                        !visitedEntityIds.contains(objectId)
                    ) {
                        nextFrontier.add(objectId)
                    }
                    if (matchesObject && !visitedEntityIds.contains(subjectId)) {
                        nextFrontier.add(subjectId)
                    }
                }
            }
            visitedEntityIds.addAll(nextFrontier)
            currentFrontier = nextFrontier
            if (currentFrontier.isEmpty()) break
        }

        val neighbours =
            visitedEntityIds
                .filter { it != focusEntityId }
                .mapNotNull { id -> view.entities.find { it.entity.entityId == id } }

        return BoundedNeighbourhood(
            focusEntity = focus,
            neighbours = neighbours,
            assertions = collectedAssertions,
            depth = depth,
        )
    }

    /**
     * Search over revealed names and entities visible at [viewOrdinal].
     */
    fun search(
        query: String,
        viewOrdinal: Int,
    ): List<GraphSearchHit> {
        if (query.isBlank()) return emptyList()
        val normalizedQuery = query.trim().lowercase()

        val results = mutableListOf<GraphSearchHit>()
        for (entity in pack.entities) {
            if (entity.firstReveal <= viewOrdinal) {
                val revealedNames =
                    pack.names.filter {
                        it.entityId == entity.entityId && it.firstReveal <= viewOrdinal
                    }
                val matchingName =
                    revealedNames.find {
                        it.displayText.lowercase().contains(
                            normalizedQuery,
                        )
                    }
                if (matchingName != null) {
                    val canonicalName =
                        revealedNames.find { it.nameId == entity.canonicalNameId }
                            ?: revealedNames.minByOrNull { it.firstReveal }
                    val revealed =
                        RevealedEntity(
                            entity = entity,
                            canonicalName = canonicalName,
                            allRevealedNames = revealedNames,
                            viewOrdinal = viewOrdinal,
                        )
                    results.add(
                        GraphSearchHit(
                            entity = revealed,
                            matchedName = matchingName,
                            matchedText = matchingName.displayText,
                        ),
                    )
                }
            }
        }
        return results
    }
}
