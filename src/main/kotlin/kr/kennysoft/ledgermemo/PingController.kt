package kr.kennysoft.ledgermemo

import kr.kennysoft.ledgermemo.entry.EntryRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 배포 검증용 엔드포인트. DB 연결과 Flyway 마이그레이션 적용 여부를 한 번에 확인한다
 * (count 쿼리가 성공하면 스키마가 존재한다는 뜻).
 */
@RestController
class PingController(
    private val entryRepository: EntryRepository,
) {

    @GetMapping("/api/ping")
    fun ping(): Map<String, Any> = mapOf(
        "status" to "ok",
        "entryCount" to entryRepository.count(),
    )
}
