plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.25" apply false
}

allprojects {
    group = providers.gradleProperty("pluginGroup").get()
    version = providers.gradleProperty("pluginVersion").get()

    repositories {
        mavenCentral()
    }
}
