plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db"))
    
    // Spring WebClient
    implementation("org.springframework.boot:spring-boot-starter-webflux:3.5.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
}
