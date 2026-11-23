pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "xrncal-android"

// domain: the calendar's pure-Kotlin core (iCalendar, recurrence, the lunar calendar, holidays,
// reminders, the month grid), tested on the JVM with the laptop app's vectors. app: the phone app.
include(":domain")
include(":app")
