# mixu-graph agent guide

## Scope and authority

- Governs only this repository. Workspace rules in `../AGENTS.md` apply; the
  roster is `../workspace.toml`.
- Public repository, **Apache-2.0**. Assume a reader is building a visual novel
  or a story game, not Mixu: no Mixu-specific concepts leak into the API.
- Must not depend on `mixu-client`, `mixu-server`, or any Android, Compose or
  Readium type.
- Canonical semantics live in `mixu-docs` →
  `docs/specifications/narrative-graph.md`; `docs/design.md` is the
  implementation contract. If prose and code disagree, fix both in one change.

## Current phase

- Implementation phase. Scoped CarryCtx tasks authorize incremental features.
- `just check` runs markdownlint and Gradle (`./gradlew --no-daemon check`). Do not weaken the recipe.

## Non-negotiable semantics

1. Reveal ordinals are edition-bound, dense and monotonic; never mix editions,
   never derive an ordinal from a rendered page number.
2. Storage with a reveal boundary is append-only. A correction is a new
   `assertion_transition`, never an `UPDATE` that erases an earlier state.
3. A projection at ordinal `r` returns only values with `first_reveal <= r`, and
   absence must be indistinguishable from non-existence: no placeholder, count,
   layout slot or "hidden" flag.
4. Filtering belongs to the query/projection layer. Assembling a full graph and
   filtering afterwards leaks through FTS, counts and coordinates.
5. Identity merges are reveal-scoped; before the boundary two personas are two
   entities with no canonical edge.
6. Attribution stays explicit (narrator, witness claim, observation, inference,
   later confirmation). There is no single boolean `is_true`.
7. Reader overlays reference canonical ids and never mutate a pack.

## API rules

- Return `Revealed*` types from every public query. A raw `Entity` or a generic
  `Map<String, Any>` in a signature invites an unfiltered leak.
- No I/O, clock, locale or randomness inside the projection path.
- Semver applies to behaviour, not only signatures: a projection change is at
  least a minor version.

## Testing

- Assert on identifiers, never on rendered strings.
- Each invariant (`P-SPOILER-01..06` in `docs/design.md`) maps to at least one
  fixture case from `mixu-fixtures`; show the fixture failing before the fix.
- Performance thresholds come from a measurement recorded in `docs/design.md`.
  Never invent a P95 number.

## Gates

```sh
just check      # markdownlint + gradle check
just lint-md
```

A gate that cannot run fails loudly. A missing or broken tool runs from the pinned
container (`just <tool>-docker`) instead of being installed system-wide or skipped.

## CarryCtx

- `cd` here before `carryctx` or Git.
- Branches `ctx-XXXX/<type>-<slug>`; checkpoint as you go.
- Export `CARRYCTX_TASK` and `CARRYCTX_AGENT` before committing.
- No commit, push or release without authorization.

## Review checklist

1. Can a caller obtain an unfiltered record through this API?
2. Does absence leak anything (count, id, ordering, layout slot)?
3. Is an earlier reveal state still reproducible after a later transition?
4. Does the library still compile without client or server types?
5. Is there a test that fails without this change?
