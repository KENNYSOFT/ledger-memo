package kr.kennysoft.ledgermemo.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 단일 사용자 인증 (DESIGN.md 6).
 *
 * 폰에서 재로그인이 사실상 없도록 remember-me 를 1년으로 두고, 토큰은 DB 에 영속화해
 * 재배포에도 살아남게 한다.
 */
@Configuration
class SecurityConfig(
    private val properties: AuthProperties,
    private val loginAttemptService: LoginAttemptService,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity, tokenRepository: PersistentTokenRepository): SecurityFilterChain = http
        .authorizeHttpRequests { auth ->
            // PWA 설치와 로그인에 필요한 정적 리소스만 열어 둔다.
            auth.requestMatchers("/login", "/login.html", "/manifest.webmanifest", "/sw.js", "/icons/**").permitAll()
            auth.requestMatchers("/actuator/health").permitAll()
            auth.anyRequest().authenticated()
        }
        .formLogin { form ->
            form.loginPage("/login.html")
                .loginProcessingUrl("/login")
                .successHandler(successHandler())
                .failureHandler(failureHandler())
                .permitAll()
        }
        .logout { logout ->
            logout.logoutUrl("/logout")
                .logoutSuccessUrl("/login.html")
                .deleteCookies("JSESSIONID")
        }
        .rememberMe { remember ->
            remember.key(properties.rememberMeKey)
                .tokenRepository(tokenRepository)
                .tokenValiditySeconds(REMEMBER_ME_SECONDS)
                .rememberMeParameter("remember-me")
        }
        .csrf { csrf ->
            // SPA 가 쿠키에서 토큰을 읽어 헤더로 돌려준다. 기본 XOR 핸들러는 쿠키 값과
            // 헤더 값이 달라져 SPA 에서 맞추기 번거로우므로 raw 토큰 핸들러를 쓴다.
            csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
        }
        // CsrfToken 은 지연 생성이라 누군가 읽어야 응답에 쿠키가 실린다.
        .addFilterAfter(CsrfCookieFilter(), UsernamePasswordAuthenticationFilter::class.java)
        .exceptionHandling { handling ->
            // API 가 세션 만료로 로그인 HTML 을 받으면 클라이언트가 JSON 파싱에 실패한다.
            handling.defaultAuthenticationEntryPointFor(
                { _, response, _ -> response.sendError(HttpStatus.UNAUTHORIZED.value()) },
                { request -> request.requestURI.startsWith("/api/") },
            )
        }
        .httpBasic { it.disable() }
        .build()

    /**
     * 사용자는 환경변수로 주입된 한 명뿐이다.
     *
     * 비밀번호는 이미 `{argon2}` prefix 가 붙은 해시로 들어오므로 여기서 다시 인코딩하지 않는다.
     */
    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager(
        User.withUsername(properties.username)
            .password(properties.passwordHash)
            .roles("USER")
            .build(),
    )

    /**
     * `{id}` prefix 로 알고리즘을 판별하는 위임 인코더.
     *
     * Argon2id 해시를 쓰되, 나중에 알고리즘을 바꿔도 기존 해시를 그대로 검증할 수 있다.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun tokenRepository(dataSource: DataSource): PersistentTokenRepository =
        JdbcTokenRepositoryImpl().apply { setDataSource(dataSource) }

    private fun successHandler() = AuthenticationSuccessHandler { request, response, _ ->
        loginAttemptService.recordSuccess(clientKey(request))
        response.sendRedirect("/")
    }

    private fun failureHandler() = AuthenticationFailureHandler { request, response, _ ->
        val key = clientKey(request)
        loginAttemptService.recordFailure(key)
        if (loginAttemptService.isBlocked(key)) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "너무 많이 시도했습니다. 잠시 후 다시 시도하세요.")
        } else {
            response.sendRedirect("/login.html?error")
        }
    }

    /** IP 와 계정을 함께 키로 삼는다 (DESIGN.md 6). */
    private fun clientKey(request: HttpServletRequest): String =
        "${request.remoteAddr}|${request.getParameter("username").orEmpty()}"

    private companion object {
        const val REMEMBER_ME_SECONDS = 365 * 24 * 60 * 60
    }
}

/**
 * CsrfToken 을 강제로 읽어 응답에 쿠키가 실리게 한다.
 *
 * Spring Security 는 토큰을 지연 생성하므로, 아무도 읽지 않으면 쿠키가 내려가지 않아
 * SPA 의 첫 POST 가 403 이 된다.
 */
class CsrfCookieFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        filterChain.doFilter(request, response)
    }
}
