plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.shrestaexclusive"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()

    val colimaDockerSocket = file("${System.getProperty("user.home")}/.colima/default/docker.sock")
    if (System.getenv("DOCKER_HOST").isNullOrBlank() && colimaDockerSocket.exists()) {
        environment("DOCKER_HOST", "unix://${colimaDockerSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        systemProperty("api.version", "1.41")
    }
}

tasks.register<Exec>("verifyNoRuntimeStaticMedia") {
    group = "verification"
    description = "Ensures SHRESTA-BE does not package or serve runtime static media; seed media must go through object storage."
    commandLine("sh", "scripts/verify-no-runtime-static-media")
}

tasks.named("check") {
    dependsOn("verifyNoRuntimeStaticMedia")
}
