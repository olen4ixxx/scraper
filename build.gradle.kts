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

    // Tests were added after two failures that a test would have caught in seconds and that
    // instead took days to notice against live sites: a 404 read as "this route has no flights"
    // for nine days, and an airline quietly changing its response shape. Applied through
    // plugins.withType so each module picks this up when it applies the java plugin, rather
    // than at root configuration time when testImplementation does not exist yet.
    plugins.withType<JavaPlugin> {
        dependencies {
            "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
            // The collection orchestrator collaborates with four Spring Data repositories, and
            // stubbing those by hand is hundreds of lines of methods nothing calls.
            "testImplementation"("org.mockito:mockito-core:5.14.2")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }
}
