package kr.kennysoft.ledgermemo.config

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 로그인 실패 횟수를 세어 무차별 시도를 늦춘다 (DESIGN.md 6).
 *
 * 단일 사용자 서비스라 외부 저장소 없이 메모리에 둔다. 재시작하면 초기화되지만, 앞단
 * httpd 의 차단과 병행하는 보조 수단이므로 충분하다.
 */
@Component
class LoginAttemptService(
    private val clock: Clock,
) {

    private val attempts = ConcurrentHashMap<String, Attempt>()

    fun isBlocked(key: String): Boolean {
        val attempt = attempts[key] ?: return false
        if (attempt.count < MAX_ATTEMPTS) return false

        val unblockAt = attempt.lastFailedAt.plus(BLOCK_DURATION)
        if (Instant.now(clock).isAfter(unblockAt)) {
            // 차단 시간이 지났으면 카운터를 버리고 다시 기회를 준다.
            attempts.remove(key)
            return false
        }
        return true
    }

    fun recordFailure(key: String) {
        attempts.compute(key) { _, existing ->
            val count = (existing?.count ?: 0) + 1
            Attempt(count, Instant.now(clock))
        }
    }

    fun recordSuccess(key: String) {
        attempts.remove(key)
    }

    private data class Attempt(val count: Int, val lastFailedAt: Instant)

    private companion object {
        const val MAX_ATTEMPTS = 5
        val BLOCK_DURATION: Duration = Duration.ofMinutes(15)
    }
}
