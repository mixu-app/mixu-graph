// SPDX-License-Identifier: Apache-2.0

rootProject.name = "mixu-graph"

pluginManagement {
    includeBuild("build-logic-src/build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("build-logic-src/gradle/libs.versions.toml"))
        }
    }
}
