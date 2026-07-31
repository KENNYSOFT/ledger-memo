package kr.kennysoft.ledgermemo.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration(proxyBeanMethods = false)
class TimeConfig {

    /**
     * 기록 시각의 기준 시간대는 KST 다.
     *
     * DB 에는 Instant 를 UTC 로 저장하지만(application.yml 의 hibernate.jdbc.time_zone),
     * "오늘"과 "지금 몇 시"는 사용자가 사는 시간대로 판단해야 한다. 테스트에서 고정 Clock 을
     * 주입할 수 있도록 빈으로 둔다.
     */
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
