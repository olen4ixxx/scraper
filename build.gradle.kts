plugins {
    java
}

group = "org.example.flightsearch"
version = "1.0-SNAPSHOT"

allprojects {
    group = "org.example.flightsearch"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.compilerArgs.add("--enable-preview")
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-preview")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }
}
