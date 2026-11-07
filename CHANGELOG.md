# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Repository scaffolding: `AGENTS.md`, `repo.toml`, `.carryctx/config.toml`,
  `justfile` gates, CI workflow, `README.md` and `docs/design.md`.
- Documented the module boundary: this library is consumed by `mixu-client` as an
  Apache-2.0 dependency and must not depend on client or server code.
