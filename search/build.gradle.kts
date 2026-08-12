plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db"))
    
    // Spring Data JDBC
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc:3.5.0")
}
