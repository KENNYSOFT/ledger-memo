plugins {
    id("com.gradle.develocity") version "4.5.0"
}

develocity {
    buildScan {
        // 공개 scans.gradle.com 에 게시. 약관에 미리 동의해 CI 에서 프롬프트 없이 게시된다.
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"

        // 약관에 동의하면 기본 동작이 '항상 게시'다 (--scan 을 주지 않아도 게시된다).
        // 로컬 빌드 정보가 무심코 공개되지 않도록 CI 에서만 게시한다.
        // 로컬에서 스캔이 필요하면 CI=true 를 붙여 실행할 것.
        publishing.onlyIf { System.getenv("CI") != null }

        // 스캔은 링크를 아는 누구나 볼 수 있으므로 로컬/러너 환경 식별 정보는 가린다.
        obfuscation {
            username { "redacted" }
            hostname { "redacted" }
            ipAddresses { addresses -> addresses.map { "0.0.0.0" } }
        }
    }
}

rootProject.name = "ledger-memo"
