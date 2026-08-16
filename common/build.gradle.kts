plugins {
    java
}

dependencies {
    // Jackson for JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.17")
}
