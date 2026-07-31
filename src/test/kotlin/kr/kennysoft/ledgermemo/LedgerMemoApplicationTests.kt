package kr.kennysoft.ledgermemo

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 컨텍스트 로드 + Flyway 마이그레이션 + JPA 매핑 검증(ddl-auto=validate)을 한 번에 확인한다.
 *
 * MySQL 연결이 필요하므로 CI 에서는 MySQL service container 로 실행된다
 * (LEDGER_DB_* 환경변수 주입). 로컬에서는 DB 없이 컴파일 검증만 수행한다.
 */
@SpringBootTest(properties = [TEST_USERNAME, TEST_PASSWORD_HASH, TEST_REMEMBER_ME_KEY, TEST_ATTACHMENT_ROOT])
class LedgerMemoApplicationTests {

    @Test
    fun contextLoads() {
    }
}

/**
 * 인증 설정은 기본값이 없어 미주입 시 기동이 실패한다 (fail-fast). 테스트에서는 값을 직접 준다.
 *
 * `application.yml` 을 test 쪽에 복사하지 않는 이유는, classpath 에서 같은 이름 파일 하나만
 * 로드되어 main 설정 전체가 가려지기 때문이다.
 */
const val TEST_USERNAME = "ledger.auth.username=tester"

/** `{noop}` 은 해시 없이 평문을 비교한다. 테스트 전용이다. */
const val TEST_PASSWORD_HASH = "ledger.auth.password-hash={noop}test-password"
const val TEST_REMEMBER_ME_KEY = "ledger.auth.remember-me-key=test-remember-me-key"
const val TEST_ATTACHMENT_ROOT = "ledger.attachment.root=\${java.io.tmpdir}/ledger-memo-test-att"
