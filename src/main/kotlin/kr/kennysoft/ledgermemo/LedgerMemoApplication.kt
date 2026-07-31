package kr.kennysoft.ledgermemo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

@SpringBootApplication
@ConfigurationPropertiesScan
class LedgerMemoApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == GENERATE_PASSWORD_HASH) {
        generatePasswordHash()
        return
    }
    runApplication<LedgerMemoApplication>(*args)
}

private const val GENERATE_PASSWORD_HASH = "--generate-password-hash"

/**
 * `LEDGER_AUTH_PASSWORD_HASH` 에 넣을 Argon2id 해시를 만든다.
 *
 * 비밀번호를 명령줄 인자로 받지 않는 것이 핵심이다. 인자로 넘기면 shell history 와 `ps`
 * 출력에 평문이 남는다. 콘솔이 있으면 입력을 가리고, 파이프로 넘겨도 동작한다.
 *
 * 접두사 `{argon2}` 는 검증 측의 DelegatingPasswordEncoder 가 알고리즘을 고르는 데 쓴다.
 */
private fun generatePasswordHash() {
    val console = System.console()
    val password = console?.readPassword("비밀번호: ")?.concatToString()
        ?: readlnOrNull()

    if (password.isNullOrBlank()) {
        System.err.println("비밀번호를 읽지 못했습니다.")
        return
    }

    // PasswordEncoderFactories 가 "argon2" 에 매핑하는 것과 같은 파라미터를 쓴다.
    val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    println("{argon2}${encoder.encode(password)}")
}
