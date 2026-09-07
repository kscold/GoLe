plugins {
    // Gradle 툴체인 자동 프로비저닝: JDK 21이 없으면 자동 다운로드
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "api"
