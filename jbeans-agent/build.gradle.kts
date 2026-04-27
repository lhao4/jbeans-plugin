plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// 零外部依赖 - 只用 JDK 内置类
dependencies {
    // 无
}

tasks.jar {
    archiveFileName.set("jbeans-agent")
    manifest {
        attributes(
            "Agent-Class" to "com.jbeans.debug.agent.JbeansDebugAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
}
