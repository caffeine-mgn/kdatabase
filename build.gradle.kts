import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "pw.binom.db"
/*
 * Порядок версии: -Pversion (так передаёт CI и так ждёт vanniktech) → тег
 * GitHub → libVersion из gradle.properties → 0.1.0.
 *
 * `-Pversion` обязан быть ПЕРВЫМ: Gradle уже использует это имя для
 * Project.version, и если читать libVersion раньше, свойство из
 * gradle.properties (0.1.0) перебьёт тег — релиз молча уедет версией 0.1.0.
 */
version = (findProperty("version") as String?)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("v")
    ?: (findProperty("libVersion") as String?)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: "0.1.0"

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

mavenPublishing {
    /*
     * Публикация в Maven Central через vanniktech.
     *
     * Версия берётся из тега CI (GITHUB_REF_NAME): тег `0.2.0` публикует 0.2.0,
     * локально без тега остаётся 0.1.0.
     */
    publishToMavenCentral(automaticRelease = true)

    /*
     * Два режима подписи:
     *  * CI: -PsigningUseGpg=true — ключ импортирован в keyring джобой, Gradle
     *    зовёт бинарь gpg;
     *  * локально: -PskipSigning=true — publishToMavenLocal без ключа.
     */
    if (findProperty("skipSigning") != "true") signAllPublications()

    coordinates(group.toString(), "kdatabase", version.toString())

    pom {
        name.set("kdatabase")
        description.set("Database contracts and connection pool for Kotlin Multiplatform")
        url.set("https://github.com/caffeine-mgn/kdatabase")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("subochev")
                name.set("Anton Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }
        scm {
            connection.set("https://github.com/caffeine-mgn/kdatabase.git")
            url.set("https://github.com/caffeine-mgn/kdatabase")
        }
    }
}

/*
 * Локальная публикация остаётся доступной для соседних репозиториев
 * (kpostgres, ksqlite, korm): mavenLocal дешевле, чем поход в Central за
 * свежей версией.
 */
pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<org.gradle.plugins.signing.SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] системный gpg (ключ из keyring)")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
