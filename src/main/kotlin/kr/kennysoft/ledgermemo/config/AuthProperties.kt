package kr.kennysoft.ledgermemo.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 단일 사용자 인증 설정 (DESIGN.md 6).
 *
 * 값은 모두 환경변수로 주입되며 기본값이 없다. 미주입 시 기동이 실패하는 것이 의도다.
 */
@ConfigurationProperties(prefix = "ledger.auth")
data class AuthProperties(
    val username: String,

    /** Argon2id 해시 (`{argon2}$argon2id$...` 형식). 평문을 두지 않는다. */
    val passwordHash: String,

    /** remember-me 토큰 서명 키. */
    val rememberMeKey: String,
)
