plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":search"))
    implementation(project(":db"))

    // Spring Web
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc:3.5.0")
}
