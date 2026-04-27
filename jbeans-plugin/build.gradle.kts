import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

val copyAgentJar by tasks.registering(Copy::class) {
    dependsOn(":jbeans-agent:jar")
    from(project(":jbeans-agent").tasks.named("jar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/agent"))
    rename { "jbeans-agent.jar" }
}

tasks.named("processResources") {
    dependsOn(copyAgentJar)
}