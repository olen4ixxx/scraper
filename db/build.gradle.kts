plugins {
    java
}

dependencies {
    implementation(project(":common"))
    
    // Spring Data JDBC
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc:3.5.0")
    
    // Spring Data (для CrudRepository)
    implementation("org.springframework.data:spring-data-jdbc:3.5.0")
    
    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    
    // Flyway
    implementation("org.flywaydb:flyway-core:11.0.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.0.1")
}
