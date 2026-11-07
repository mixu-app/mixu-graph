# Rule: repository scope (mixu-graph)

- Visibility: **public**. Everything committed here is world-readable forever.
- Stack: Kotlin Multiplatform library.
- Do not edit: any Android/Compose/Readium import; `source/`, `node_modules/`.
- Gate: `just check` (the command recorded in `[verification]`). A gate that
  cannot run fails loudly; a documented phase exception is the only skip.
- Language: English for code, comments, docs, commit subjects; Chinese only for
  reader-facing UI copy and a public README body.
- Never commit: credentials, tokens, presigned URLs, book text, user notes,
  in-copyright extracted graphs.
- Task prefix is `CTX`; branches are `ctx-XXXX/<type>-<slug>`.
