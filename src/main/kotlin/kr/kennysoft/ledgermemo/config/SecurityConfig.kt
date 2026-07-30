package kr.kennysoft.ledgermemo.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * Spike 단계 최소 설정. 검증용 엔드포인트만 열고 나머지는 인증을 요구한다.
 *
 * 실제 로그인(Argon2id + remember-me 1년)은 DESIGN.md 6장에 따라 2단계에서 구현한다.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { auth ->
            auth.requestMatchers("/api/ping", "/actuator/health").permitAll()
            auth.anyRequest().authenticated()
        }
        .csrf { it.disable() }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .build()
}
