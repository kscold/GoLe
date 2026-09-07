plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.google.protobuf") version "0.9.6"
}

group = "com.gole"
version = "0.0.1-SNAPSHOT"
description = "GoLe 브릭 중고거래 API (hexagonal)"

java {
    toolchain {
        // Java 21 LTS. Spring Boot 4는 Java 17~26을 지원한다.
        // 로컬과 GCP 운영 이미지가 Temurin 21을 사용하므로 21로 고정한다.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.21.4"
extra["awsSdkVersion"] = "2.31.6"
extra["grpcVersion"] = "1.84.0"
extra["protobufVersion"] = "4.36.1"

dependencies {
    // Web / Validation / Actuator
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // 문의 분류 LangGraph 서비스와 공유하는 protobuf 계약 및 내부 gRPC 클라이언트.
    implementation("io.grpc:grpc-protobuf:${property("grpcVersion")}")
    implementation("io.grpc:grpc-stub:${property("grpcVersion")}")
    implementation("io.grpc:grpc-netty-shaded:${property("grpcVersion")}")
    implementation("com.google.protobuf:protobuf-java:${property("protobufVersion")}")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    // CoolSMS(SOLAPI) 카카오 알림톡 발송 공식 SDK.
    implementation("com.solapi:sdk:1.1.0")

    // FCM HTTP v1 인증. Firebase Admin SDK 전체(Firestore·Auth·Storage 포함) 대신
    // 액세스 토큰 발급·갱신만 담당하는 인증 라이브러리만 쓴다. 발송은 JDK HttpClient로 직접 한다.
    implementation("com.google.auth:google-auth-library-oauth2-http:1.30.0")

    // PortOne Standard Webhooks signature verification (HMAC-SHA256 + replay-window validation).
    implementation("io.portone:server-sdk:0.24.0")

    // AOP (클린코드: 로깅/트랜잭션/감사 등 횡단 관심사 분리)
    // Spring Boot 4에는 starter-aop가 없어 aspectjweaver를 직접 사용한다.
    // spring-aop는 spring-context를 통해 전이 포함되고, AopAutoConfiguration이 기본 활성.
    implementation("org.aspectj:aspectjweaver")

    // Data: MongoDB(primary) + Redis(cache/chat/ranking)
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 비밀번호 해싱(BCrypt). 전체 Spring Security 스택 없이 crypto 모듈만 사용한다.
    // 버전은 Spring Boot dependency management(BOM)가 관리한다.
    implementation("org.springframework.security:spring-security-crypto")

    // 객체 스토리지(MinIO, S3 호환). AWS SDK v2 S3 클라이언트.
    implementation("software.amazon.awssdk:s3")

    // Swagger / OpenAPI 문서. /swagger-ui.html, /v3/api-docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${property("protobufVersion")}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${property("grpcVersion")}"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
        mavenBom("software.amazon.awssdk:bom:${property("awsSdkVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 통합 테스트(*IntegrationTest)는 Docker(Testcontainers)가 필요하므로 기본 test에서 제외.
tasks.named<Test>("test") {
    filter { excludeTestsMatching("*IntegrationTest") }
}

// Docker 사용 가능 환경(CI 등)에서 명시적으로 실행: ./gradlew integrationTest
tasks.register<Test>("integrationTest") {
    description = "Testcontainers 기반 통합 테스트 실행"
    group = "verification"
    useJUnitPlatform()
    filter { includeTestsMatching("*IntegrationTest") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter("test")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

// 코드 포맷 일관성(Spotless + Palantir Java Format). CI에서 spotlessCheck로 강제한다.
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
