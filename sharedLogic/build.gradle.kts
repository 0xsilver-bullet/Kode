import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * Umbrella module exported to iOS as the `SharedLogic` framework.
 *
 * It contains no code of its own: it re-exports the layered `:core` modules so
 * Xcode has a single framework to link against. When the iOS UI lands, this is
 * where `:sharedUI` gets added to the export list.
 */
kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true

            // `export` is what makes these modules' public API visible to Swift
            // rather than merely linked in.
            export(project(":core:common"))
            export(project(":core:model"))
            export(project(":core:rpc"))
            export(project(":core:network"))
            export(project(":core:datastore"))
        }
    }

    android {
        namespace = "com.silverbullet.kode.sharedLogic"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:model"))
            api(project(":core:rpc"))
            api(project(":core:network"))
            api(project(":core:datastore"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
