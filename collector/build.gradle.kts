plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db"))
    
    // Spring WebClient
    implementation("org.springframework.boot:spring-boot-starter-webflux:3.5.0")
}
