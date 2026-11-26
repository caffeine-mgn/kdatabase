import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
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

    // Apple mobile: kpostgres публикуется под iOS, и без этих таргетов
    // зависимость от kdatabase не резолвится в appleMain. mingw — по той же
    // причине: kpostgres собран и под Windows.
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    mingwX64()

    // Хвост Apple и Android Native объявлен потому, что ksqlite (драйвер)
    // публикуется под них, и его зависимость на kdatabase обязана резолвиться
    // во всех source set'ах. Контракты и механика пула от платформы не
    // зависят, поэтому добавление таргетов ничего не стоит: кода под них нет,
    // есть только пустой klib.
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosX64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

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

publishing {
    repositories {
        // Локальная публикация для соседних репозиториев (kpostgres, ksqlite,
        // korm): они собираются против свежей версии без похода в Central.
        mavenLocal()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
