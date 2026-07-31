package kr.kennysoft.ledgermemo.config

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference

/**
 * Kotlin 의 빈 컬렉션 싱글톤을 native image 에서 Jackson 이 다룰 수 있게 한다.
 *
 * `sorted()` / `distinct()` / `toList()` 는 결과가 비면 `kotlin.collections.EmptyList` 를
 * 돌려준다. 이는 Kotlin 내부 object 라서 native image 에 Kotlin 메타데이터가 없으면
 * jackson-module-kotlin 이 직렬화하다 `KotlinReflectionInternalError: Unresolved class` 로
 * 실패한다.
 *
 * **JVM 테스트로는 절대 잡히지 않는다** — JVM 에서는 메타데이터가 그대로 있어 정상 동작하고,
 * native 바이너리에서만 터진다. 실측: 태그 없이 저장하면 응답의 빈 태그 목록이 EmptyList 라
 * 모든 저장이 500 으로 실패했다.
 *
 * @see <a href="https://github.com/quarkusio/quarkus/issues/44472">Quarkus #44472</a>
 */
class KotlinCollectionsRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        EMPTY_COLLECTION_TYPES.forEach { type ->
            hints.reflection().registerType(
                TypeReference.of(type),
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.PUBLIC_FIELDS,
                MemberCategory.DECLARED_FIELDS,
            )
        }
    }

    private companion object {
        /**
         * 빈 컬렉션 싱글톤 셋.
         *
         * 크기가 1일 때 쓰이는 `listOf(x)` 는 `java.util.Collections.singletonList` 라
         * Kotlin 메타데이터가 필요 없어 대상이 아니다.
         */
        private val EMPTY_COLLECTION_TYPES = listOf(
            "kotlin.collections.EmptyList",
            "kotlin.collections.EmptyMap",
            "kotlin.collections.EmptySet",
        )
    }
}
