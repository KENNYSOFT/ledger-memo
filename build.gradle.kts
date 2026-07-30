import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.hibernate.orm") version "7.4.1.Final"
    id("org.graalvm.buildtools.native") version "1.1.1"
}

group = "kr.kennysoft"
version = "0.0.1-SNAPSHOT"
description = "가계부 분개 전 거래 초안 메모 서비스"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Boot 4 는 autoconfiguration 이 모듈로 분리되어 있다. flyway-core 만 넣으면
    // 라이브러리는 있어도 마이그레이션이 실행되지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Flyway 10+ 부터 MySQL 지원은 별도 모듈이고, 위 스타터에 포함되지 않는다.
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

hibernate {
    enhancement {
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        // 컨텍스트 로드 실패(스키마 검증 등)의 원인 체인을 CI 로그에서 바로 보기 위해.
        // 기본 설정에서는 예외 클래스명만 남아 원인 추적이 불가능하다.
        exceptionFormat = TestExceptionFormat.FULL
        events("failed")
        showStackTraces = true
    }
}

graalvmNative {
    binaries {
        named("main") {
            // Containerfile 이 이 이름으로 COPY 한다.
            imageName = "ledger-memo"
        }
    }
}
