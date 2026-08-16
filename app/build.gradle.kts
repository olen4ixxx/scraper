plugins {
    java
    application
    id("org.springframework.boot") version "3.5.0"
}

application {
    mainClass = "org.example.flightsearch.app.Application"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db"))
    implementation(project(":collector"))
    implementation(project(":collector-wizz"))
    implementation(project(":collector-ryanair"))
    implementation(project(":collector-vueling"))
    implementation(project(":collector-transavia"))
    implementation(project(":search"))
    implementation(project(":api"))
    
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter:3.5.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc:3.5.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux:3.5.0")
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.0")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.5.0")
    implementation("org.springframework.boot:spring-boot-starter-aop:3.5.0")
    implementation("io.projectreactor.netty:reactor-netty:1.2.2")
    
    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    
    // Flyway
    implementation("org.flywaydb:flyway-core:11.0.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.0.1")
    
    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.0")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}
