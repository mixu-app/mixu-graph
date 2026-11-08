# Design: temporal narrative graph

Status: **Active** · Owner: mixu-core · Consumes: `mixu-contracts` schemas

## Problem

A narrative graph is not a knowledge base of what is true; it is a record of what
has been *disclosed* to a reader, and in which order. Two questions dominate
every design decision:

1. What may be shown at reveal ordinal `r`?
2. How do later disclosures change earlier statements without rewriting history?

## Model

```kotlin
value class RevealOrdinal(val value: Int)

data class RevealedEntity(
    val id: EntityId,
    val type: EntityType,
    val displayName: RevealedName,
    val firstReveal: RevealOrdinal,
)

data class RevealedAssertion(
    val id: AssertionId,
    val subject: EntityId,
    val predicate: String,
    val objectRef: ObjectRef,
    val attribution: Attribution,
    val status: EpistemicStatus,      // status as of the view ordinal, not the latest
    val evidence: EvidenceAnchor,
)
```

Storage is append-only for anything with a reveal boundary. `assertion` rows are
immutable; `assertion_transition` rows are appended. There is no `UPDATE` path
that erases an earlier state — a correction is new data.

## Projection contract

`projection(edition, r)` must satisfy, for every returned value:

```text
value.first_reveal <= r
```

and for every absent value, absence must be indistinguishable from
non-existence: no placeholder, no count, no layout slot, no "hidden" marker.

The implementation is allowed to use SQL, an in-memory index, or a materialized
view. It is not allowed to filter after assembling a full graph, because every
derived artifact (FTS index, node count, viewport packing, embeddings) can leak
through size or position.

## Invariants (test-gated)

| ID | Invariant |
|---|---|
| P-SPOILER-01 | No label, alias or name with `first_reveal > r` appears in any projection at `r` |
| P-SPOILER-02 | An assertion contradicted at `r2` still reports its earlier status at `r1 < r2` |
| P-SPOILER-03 | An identity merge at `r2` never changes the `r1` display |
| P-SPOILER-04 | Search, FTS, autocomplete, counts and previews obey the same `r` |
| P-SPOILER-05 | A backward reader jump changes the reader locator, not the horizon |
| P-SPOILER-06 | Hidden metadata (coordinates, ids, edge slots) reveals nothing |

Each invariant maps to a fixture case in `mixu-fixtures`; a change that touches
projection must show the fixture failing before the fix.

## Edition binding

Ordinals are dense within an edition and meaningless across editions. A
translation or an abridgement is a different edition with its own anchor map.
Exact binary hashes identify a file, not a narrative position; a normalized
fingerprint plus chapter alignment may *suggest* compatibility, and a human
validates it. Never attach a graph by title alone.

## Overlays

Reader-authored data (colour, alias, note, hypothesis edge) references stable
canonical ids and lives separately. Visibility policy:

| Overlay kind | At view ordinal earlier than `created_at_reveal` |
|---|---|
| `COLOR`, `ALIAS` | hidden by default (`FROM_CREATION`) |
| `NOTE` | hidden by default; an explicit override may show it |
| `HYPOTHESIS_EDGE` | hidden by default, and always labelled reader-authored |
| `CORRECTION` | visible, but never rewrites canonical data |

## Query surface

Minimum for M1: entity lookup, assertions about an entity, evidence navigation,
bounded neighbourhood (depth 1–2), and full-text search over the filtered
projection. Everything returns `Revealed*` types so an unfiltered record cannot
be smuggled through a generic map.

## Versioning

The graph pack carries `graph_schema_version` and `graph_version`. Entity ids
must stay stable across pack revisions; a remap requires a migration table and a
user-visible review. This library refuses to load a pack whose schema major
version it does not implement.

## Performance notes (to be measured, not assumed)

Targets are empty on purpose. M1 measures, on a mid-range Android phone and one
E-Ink device: projection of a 300-entity graph at a mid-book ordinal, bounded
neighbourhood query, and search over the filtered index. Thresholds are set from
those measurements — do not paste invented P95 numbers into a document.

## Open questions

- Modelling unreliable narration beyond attribution classes.
- Interval and relative story time (`"the previous night"`) without fabricating
  timestamps.
- Stable ids when a translation re-segments chapters.
- Whether an overlay may reference an entity the reader has not reached yet
  (current answer: no — it would leak existence).
