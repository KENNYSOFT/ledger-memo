package kr.kennysoft.ledgermemo

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 컨텍스트 로드 + Flyway 마이그레이션 + JPA 매핑 검증(ddl-auto=validate)을 한 번에 확인한다.
 *
 * MySQL 연결이 필요하므로 CI 에서는 MySQL service container 로 실행된다
 * (LEDGER_DB_* 환경변수 주입). 로컬에서는 DB 없이 컴파일 검증만 수행한다.
 */
@SpringBootTest
class LedgerMemoApplicationTests {

    @Test
    fun contextLoads() {
    }
}
