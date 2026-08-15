rootProject.name = "Kode"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Platform entry points.
include(":androidApp")

// Umbrella module consumed by iosApp as the `SharedLogic` framework, and by
// androidApp as the single Compose entry point.
include(":sharedLogic")
include(":sharedUI")

// Layered shared code. Everything below is `commonMain`-first: the iOS targets
// are declared so the compiler proves the code stays platform-agnostic, even
// though we are not building iOS UI yet.
include(":core:common")
include(":core:model")
include(":core:rpc")
include(":core:network")
include(":core:datastore")
include(":core:session")
include(":core:designsystem")

include(":feature:connection")
include(":feature:threads")
