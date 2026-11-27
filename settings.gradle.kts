pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven(url = "http://nexus.xx/repository/maven-public/") {
            isAllowInsecureProtocol = true
        }
    }
}

dependencyResolutionManagement {
    repositories {
        // mavenCentral — чтобы артефакты резолвились на чистом раннере CI, где
        // нет ни mavenLocal, ни доступа к внутреннему Nexus.
        mavenCentral()
        // mavenLocal первым: соседние репозитории собираются против свежей
        // локально опубликованной версии, не дожидаясь Central.
        mavenLocal()
        maven(url = "http://nexus.xx/repository/maven-public/") {
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "kdatabase"
