package kr.kennysoft.ledgermemo.config

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints

/**
 * Flyway 의 SQLException 변환 경로를 native image 에서 살린다.
 *
 * DB 연결이 실패하면 Flyway 는 원인을 구체적으로 알려주기 위해
 * `FlywaySqlException.throwFlywayExceptionIfPossible` 에서 전용 예외 클래스들의
 * `isFlywaySpecificVersionOf` 를 리플렉션으로 찾는다. native image 에 그 메타데이터가 없으면
 * `NoSuchMethodException` 이 발생하면서 **원래의 연결 실패 원인이 완전히 가려진다**
 * (실측: 잘못된 DB 암호로 기동했을 때 Access denied 대신 NoSuchMethodException 만 남았다).
 */
class FlywayExceptionRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        SQL_EXCEPTION_TYPES.forEach { type ->
            hints.reflection().registerType(TypeReference.of(type), MemberCategory.INVOKE_PUBLIC_METHODS)
        }
    }

    private companion object {
        private const val PACKAGE = "org.flywaydb.core.internal.exception.sqlExceptions"

        /** flyway-core 12.4 의 해당 패키지 전체. Flyway 업그레이드 시 클래스 추가 여부를 확인할 것. */
        private val SQL_EXCEPTION_TYPES = listOf(
            "$PACKAGE.FlywaySqlErrorCode",
            "$PACKAGE.FlywaySqlNoDriversForInteractiveAuthException",
            "$PACKAGE.FlywaySqlNoIntegratedAuthException",
            "$PACKAGE.FlywaySqlServerErrorCode",
            "$PACKAGE.FlywaySqlServerUntrustedCertificateSqlException",
            "$PACKAGE.FlywaySqlUnableToConnectToDbException",
        )
    }
}

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(FlywayExceptionRuntimeHints::class)
class NativeHintsConfig
