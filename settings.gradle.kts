pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TherapyLocal"
include(":app")

if (rootDir.path.contains("OneDrive", ignoreCase = true)) {
    val localBuildRoot = java.io.File(System.getProperty("user.home"), ".gradle-local-build/Therapy_local")
    gradle.beforeProject {
        layout.buildDirectory.set(java.io.File(localBuildRoot, name))
    }
}

