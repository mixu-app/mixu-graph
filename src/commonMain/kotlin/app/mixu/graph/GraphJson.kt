// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlinx.serialization.json.Json

/**
 * Canonical JSON serialization configuration for Mixu narrative graph models.
 *
 * ## Unknown-Field Policy
 * On decode, unknown fields are silently ignored ([JsonBuilder.ignoreUnknownKeys] = true)
 * to permit forward-compatible contract extensions as schemas evolve across versions.
 * Newer producers may introduce additional optional metadata fields that older consumers
 * can safely bypass without failing deserialization.
 *
 * ## Encoding Policy
 * - Compact JSON output ([JsonBuilder.prettyPrint] = false) for wire and storage efficiency.
 * - Defaults are omitted when matching schema defaults ([JsonBuilder.encodeDefaults] = false),
 *   except where explicitly required by contracts (annotated with `@EncodeDefault`).
 */
val GraphJson: Json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
        isLenient = false
    }

/**
 * Alias for [GraphJson] matching the library namespace.
 */
val MixuGraphJson: Json = GraphJson
