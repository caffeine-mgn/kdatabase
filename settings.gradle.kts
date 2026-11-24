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
        mavenLocal()
        maven(url = "http://nexus.xx/repository/maven-public/") {
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "kdatabase"
