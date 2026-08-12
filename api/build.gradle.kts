plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":search"))
    
    // Spring Web
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.0")
}
