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
) {
    companion object {
        /**
         * Stable comparator ordering exact matches first, then entity firstReveal ASC,
         * then entityId ASC, then matched nameId ASC.
         */
        fun comparatorFor(query: String): Comparator<GraphSearchHit> {
            val trimmed = query.trim()
            return compareBy(
                { !it.matchedText.equals(trimmed, ignoreCase = true) },
                { it.entity.entity.firstReveal },
                { it.entity.entity.entityId.value },
                { it.matchedName.nameId.value },
            )
        }
    }
}

/**
 * Result of conditional non-destructive DSU resolution over visible entities and identity links (INV-3).
 */
data class ConditionalIdentityResolution(
    val resolvedByEntity: Map<EntityId, EntityId>,
    val dsuClusters: Map<EntityId, List<EntityId>>,
    val collapsibleLinks: List<IdentityLink>,
    val hypothesizedLinks: List<IdentityLink>,
)

/**
 * Compute deterministic, non-destructive DSU clusters strictly from [visibleEntities] and
 * [visibleLinks]. Only unframed OBSERVED links (or omitted epistemic_status for v1 compatibility)
 * collapse clusters; cluster roots prefer the earliest disclosed entity.
 */
fun computeConditionalIdentityClusters(
    visibleEntities: List<Entity>,
    visibleLinks: List<IdentityLink>,
): ConditionalIdentityResolution {
    val entityById = visibleEntities.associateBy { it.entityId }
    val parent = entityById.keys.associateWith { it }.toMutableMap()
    val rootRank =
        compareBy<Entity>(
            { it.firstReveal },
            { it.revealCharEnd ?: 0 },
            { it.entityId.value },
        )

    fun find(id: EntityId): EntityId {
        var cur = id
        while (parent[cur] != cur) {
            val next = parent[cur] ?: break
            parent[cur] = parent[next] ?: next
            cur = next
        }
        return cur
    }

    fun union(
        a: EntityId,
        b: EntityId,
    ) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        val ea = entityById[ra]
        val eb = entityById[rb]
        if (ea != null && eb != null && rootRank.compare(ea, eb) <= 0) {
            parent[rb] = ra
        } else if (ea != null && eb != null) {
            parent[ra] = rb
        } else if (ra.value <= rb.value) {
            parent[rb] = ra
        } else {
            parent[ra] = rb
        }
    }

    val collapsible = mutableListOf<IdentityLink>()
    val hypothesized = mutableListOf<IdentityLink>()
    for (link in visibleLinks) {
        val endpointsVisible =
            link.entityIds.size == 2 &&
                entityById.containsKey(link.entityIds[0]) &&
                entityById.containsKey(link.entityIds[1])
        if (endpointsVisible && link.isCollapsible()) {
            collapsible.add(link)
            union(link.entityIds[0], link.entityIds[1])
        } else {
            hypothesized.add(link)
        }
    }

    val resolved = entityById.keys.associateWith { find(it) }
    val clusters = mutableMapOf<EntityId, MutableList<EntityId>>()
    for (id in entityById.keys.sortedBy { it.value }) {
        val root = resolved[id] ?: id
        clusters.getOrPut(root) { mutableListOf() }.add(id)
    }
    return ConditionalIdentityResolution(
        resolvedByEntity = resolved,
        dsuClusters = clusters.mapValues { (_, v) -> v.toList() },
        collapsibleLinks = collapsible,
        hypothesizedLinks = hypothesized,
    )
}

/**
 * Pure projection engine that executes queries directly against a [GraphPack] under a given reveal ordinal.
 * Filtering happens in the query layer; unfiltered structures are never materialized.
 */
class TemporalProjectionEngine(
    val pack: GraphPack,
) {
    private val canonicalPack: GraphPack = pack.canonical()

    /**
     * Compute the full revealed graph view at cursor ([viewOrdinal], [viewCharOffset]) for the pack's edition.
     */
    fun project(
        viewOrdinal: Int,
        viewCharOffset: Int? = null,
        identityLinks: List<IdentityLink> = canonicalPack.identityLinks,
    ): RevealedGraphView {
        require(viewOrdinal >= 0) { "viewOrdinal must be non-negative" }
        if (viewCharOffset != null) {
            require(viewCharOffset >= 0) { "viewCharOffset must be non-negative" }
        }

        val namesByEntity =
            canonicalPack.names
                .filter {
                    isDisclosedAt(
                        it.firstReveal,
                        it.revealCharEnd,
                        viewOrdinal,
                        viewCharOffset,
                    )
                }.groupBy { it.entityId }

        val visibleRawEntities =
            canonicalPack.entities.filter {
                isDisclosedAt(it.firstReveal, it.revealCharEnd, viewOrdinal, viewCharOffset)
            }
        val visibleLinks =
            identityLinks
                .filter {
                    isDisclosedAt(it.firstReveal, it.revealCharEnd, viewOrdinal, viewCharOffset)
                }.sortedWith(IdentityLink.STABLE_COMPARATOR)
        val resolution = computeConditionalIdentityClusters(visibleRawEntities, visibleLinks)

        val visibleEntities = mutableListOf<RevealedEntity>()
        val visibleEntityMap = mutableMapOf<EntityId, RevealedEntity>()

        for (entity in visibleRawEntities) {
            val revealedNames = namesByEntity[entity.entityId] ?: emptyList()
            val canonicalName =
                revealedNames.find { it.nameId == entity.canonicalNameId }
                    ?: revealedNames.firstOrNull()

            val revealed =
                RevealedEntity(
                    entity = entity,
                    canonicalName = canonicalName,
                    allRevealedNames = revealedNames,
                    viewOrdinal = viewOrdinal,
                    viewCharOffset = viewCharOffset,
                    resolvedEntityId =
                        resolution.resolvedByEntity[entity.entityId] ?: entity.entityId,
                )
            visibleEntities.add(revealed)
            visibleEntityMap[entity.entityId] = revealed
        }
        visibleEntities.sortWith(RevealedEntity.STABLE_COMPARATOR)

        val transitionsByAssertion =
            canonicalPack.transitions
                .filter {
                    isDisclosedAt(
                        it.revealOrdinal,
                        it.revealCharEnd,
                        viewOrdinal,
                        viewCharOffset,
                    )
                }.sortedWith(
                    compareBy<AssertionTransition>(
                        { it.revealOrdinal },
                        { it.revealCharEnd ?: 0 },
                        { it.transitionId.value },
                    ),
                ).groupBy { it.assertionId }

        val visibleAssertions = mutableListOf<RevealedAssertion>()
        for (assertion in canonicalPack.assertions) {
            if (isDisclosedAt(
                    assertion.firstReveal,
                    assertion.revealCharEnd,
                    viewOrdinal,
                    viewCharOffset,
                )
            ) {
                if (!visibleEntityMap.containsKey(assertion.subjectId)) continue

                if (assertion.objectRef is ObjectRef.Entity) {
                    if (!visibleEntityMap.containsKey(assertion.objectRef.entityId)) continue
                }

                val validTransitions = transitionsByAssertion[assertion.assertionId] ?: emptyList()
                val activeTransition = validTransitions.lastOrNull()
                val currentStatus = activeTransition?.status ?: assertion.epistemicStatus

                visibleAssertions.add(
                    RevealedAssertion(
                        assertion = assertion,
                        currentStatus = currentStatus,
                        activeTransition = activeTransition,
                        viewOrdinal = viewOrdinal,
                        viewCharOffset = viewCharOffset,
                    ),
                )
            }
        }
        visibleAssertions.sortWith(RevealedAssertion.STABLE_COMPARATOR)

        return RevealedGraphView(
            editionId = canonicalPack.manifest.editionId,
            viewOrdinal = viewOrdinal,
            entities = visibleEntities,
            assertions = visibleAssertions,
            viewCharOffset = viewCharOffset,
            identityLinks = visibleLinks,
            collapsibleIdentityLinks = resolution.collapsibleLinks,
            hypothesizedIdentityLinks = resolution.hypothesizedLinks,
            dsuClusters = resolution.dsuClusters,
        )
    }

    /**
     * Entity lookup: returns RevealedEntity if disclosed at or before ([viewOrdinal], [viewCharOffset]), null otherwise.
     */
    fun findEntity(
        entityId: EntityId,
        viewOrdinal: Int,
        viewCharOffset: Int? = null,
    ): RevealedEntity? {
        val entity =
            canonicalPack.entities.find {
                it.entityId == entityId &&
                    isDisclosedAt(it.firstReveal, it.revealCharEnd, viewOrdinal, viewCharOffset)
            } ?: return null
        val revealedNames =
            canonicalPack.names
                .filter {
                    it.entityId == entityId &&
                        isDisclosedAt(it.firstReveal, it.revealCharEnd, viewOrdinal, viewCharOffset)
                }.sortedWith(EntityName.STABLE_COMPARATOR)
        val canonicalName =
            revealedNames.find { it.nameId == entity.canonicalNameId }
                ?: revealedNames.firstOrNull()

        return RevealedEntity(
            entity = entity,
            canonicalName = canonicalName,
            allRevealedNames = revealedNames,
            viewOrdinal = viewOrdinal,
            viewCharOffset = viewCharOffset,
        )
    }

    /**
     * Assertions touching a specific entity (as subject or object) visible at ([viewOrdinal], [viewCharOffset]).
     */
    fun findAssertionsForEntity(
        entityId: EntityId,
        viewOrdinal: Int,
        viewCharOffset: Int? = null,
    ): List<RevealedAssertion> {
        findEntity(entityId, viewOrdinal, viewCharOffset) ?: return emptyList()
        val view = project(viewOrdinal, viewCharOffset)
        return view.assertions
            .filter {
                it.assertion.subjectId == entityId ||
                    (
                        it.assertion.objectRef is ObjectRef.Entity &&
                            it.assertion.objectRef.entityId == entityId
                    )
            }.sortedWith(RevealedAssertion.STABLE_COMPARATOR)
    }

    /**
     * Bounded neighbourhood around a focus entity up to given [depth] (1 or 2).
     */
    fun boundedNeighbourhood(
        focusEntityId: EntityId,
        viewOrdinal: Int,
        depth: Int = 1,
        viewCharOffset: Int? = null,
    ): BoundedNeighbourhood? {
        require(depth in 1..2) { "Depth must be 1 or 2" }
        val focus = findEntity(focusEntityId, viewOrdinal, viewCharOffset) ?: return null
        val view = project(viewOrdinal, viewCharOffset)

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
                .sortedWith(RevealedEntity.STABLE_COMPARATOR)

        val sortedAssertions = collectedAssertions.sortedWith(RevealedAssertion.STABLE_COMPARATOR)

        return BoundedNeighbourhood(
            focusEntity = focus,
            neighbours = neighbours,
            assertions = sortedAssertions,
            depth = depth,
        )
    }

    /**
     * Search over revealed names and entities visible at ([viewOrdinal], [viewCharOffset]).
     */
    fun search(
        query: String,
        viewOrdinal: Int,
        viewCharOffset: Int? = null,
    ): List<GraphSearchHit> {
        if (query.isBlank()) return emptyList()
        val normalizedQuery = query.trim().lowercase()

        val namesByEntity =
            canonicalPack.names
                .filter {
                    isDisclosedAt(
                        it.firstReveal,
                        it.revealCharEnd,
                        viewOrdinal,
                        viewCharOffset,
                    )
                }.groupBy { it.entityId }

        val results = mutableListOf<GraphSearchHit>()
        for (entity in canonicalPack.entities) {
            if (isDisclosedAt(
                    entity.firstReveal,
                    entity.revealCharEnd,
                    viewOrdinal,
                    viewCharOffset,
                )
            ) {
                val revealedNames = namesByEntity[entity.entityId] ?: emptyList()
                val matchingName =
                    revealedNames.find {
                        it.displayText.equals(query.trim(), ignoreCase = true)
                    } ?: revealedNames.find {
                        it.displayText.lowercase().contains(normalizedQuery)
                    }

                if (matchingName != null) {
                    val canonicalName =
                        revealedNames.find { it.nameId == entity.canonicalNameId }
                            ?: revealedNames.firstOrNull()
                    val revealed =
                        RevealedEntity(
                            entity = entity,
                            canonicalName = canonicalName,
                            allRevealedNames = revealedNames,
                            viewOrdinal = viewOrdinal,
                            viewCharOffset = viewCharOffset,
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
        return results.sortedWith(GraphSearchHit.comparatorFor(query))
    }
}
