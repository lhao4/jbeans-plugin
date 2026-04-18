plugins {
    id("java")
}

group = "com.github.lhao4"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.jar {
    manifest {
        attributes(
            "Agent-Class" to "com.github.lhao4.jbeans.agent.InspectorAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}
