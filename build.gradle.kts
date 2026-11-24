import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "pw.binom.db"
version = (findProperty("libVersion") as String?) ?: "0.1.0"

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Таргеты native: пул и контракты не зависят от платформы, но именно
    // linuxX64 — цель переезда провижинера без JVM.
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    // JS — ради IndexedDB в браузере. Контракты и пул от платформы не
    // зависят; драйвер IndexedDB появится отдельным модулем позже.
    // `nodejs()` нужен для запуска тестов в CI: браузерного раннера нет.
    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
