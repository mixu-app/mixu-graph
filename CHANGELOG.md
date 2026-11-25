# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Additive v1 support for ADR-042 runtime invariants: optional `reveal_char_end`
  gating across domain models, SQLite storage, and `TemporalProjectionEngine`
  (`INV-7`), conditional unframed `OBSERVED` DSU clustering in
  `IdentityLink.isCollapsible()` and `computeConditionalIdentityClusters` (`INV-3`),
  and per-alias reveal isolation (`INV-6`).
- Repository scaffolding: `AGENTS.md`, `repo.toml`, `.carryctx/config.toml`,
  `justfile` gates, CI workflow, `README.md` and `docs/design.md`.
- Documented the module boundary: this library is consumed by `mixu-client` as an
  Apache-2.0 dependency and must not depend on client or server code.
