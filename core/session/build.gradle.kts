import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.silverbullet.kode.core.session"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }

        withHostTest {
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:model"))
            api(project(":core:network"))
            api(project(":core:datastore"))
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
        }
    }
}
