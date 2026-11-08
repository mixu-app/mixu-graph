// SPDX-License-Identifier: Apache-2.0

plugins {
    id("mixu.kotlin.multiplatform")
    id("mixu.quality")
    id("mixu.reproducible")
    alias(libs.plugins.kotlin.serialization)
}

group = "app.mixu"
version = "0.1.0"
description = "Temporal narrative graph engine for Mixu."

val mixuCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(mixuCatalog.findLibrary("kotlinx-serialization-json").get())
        }
    }
}
