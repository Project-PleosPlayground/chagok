pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
plugins {
    // JDK 17 이 없는 PC(Windows, JDK 21 만 설치)에서 Gradle 이 17 을 받아오게 한다. 17 이 있으면 아무 일도 안 한다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") } // Pleos SDK
    }
}
rootProject.name = "pleos-safety-poc"
include(":engine")
include(":core")     // 화면 뒤 로직: 세션 저장 인터페이스·미션·성장·코인·종료 파이프라인·리포트 집계 (순수 Kotlin)
include(":app")   // Android 래퍼. Pleos Vehicle SDK · AAOS 속도 · 음성
