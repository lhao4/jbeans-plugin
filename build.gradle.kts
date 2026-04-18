plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.github.lhao4"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

// Separate source set for the agent (no IntelliJ deps — runs in target JVM)
sourceSets {
    create("agent") {
        java.srcDir("src/agent/java")
    }
}

// Agent must be compiled to Java 8 bytecode — it runs inside the target JVM
tasks.named<JavaCompile>("compileAgentJava") {
    options.release.set(8)
}

// Build a minimal agent JAR with only agent classes + Agent-Class manifest entry
val agentJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("compileAgentJava"))
    archiveFileName.set("jbeans-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated-resources/agent"))
    manifest {
        attributes("Agent-Class" to "com.github.lhao4.jbeans.agent.JBeansAgent")
    }
    from(sourceSets["agent"].output)
}

// Make agent JAR available as a plugin resource at /agent/jbeans-agent.jar
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated-resources"))
tasks.processResources { dependsOn(agentJar) }

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
}
