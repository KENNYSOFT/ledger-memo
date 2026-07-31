package kr.kennysoft.ledgermemo.parse

import kr.kennysoft.ledgermemo.person.Person

/**
 * 파서가 쓰는 이름 사전. 별칭으로 적어도 정식 이름으로 되돌린다.
 *
 * 파서를 순수 함수로 두기 위해 DB 조회 결과를 이 형태로 주입받는다.
 */
class PersonDictionary(entries: Map<String, String>) {

    /** 별칭 → 정식 이름. */
    private val byAlias: Map<String, String> = entries

    /** 토큰이 사람 이름이면 정식 이름을, 아니면 null 을 돌려준다. */
    fun resolve(token: String): String? = byAlias[token]

    companion object {
        val EMPTY = PersonDictionary(emptyMap())

        fun of(persons: Collection<Person>): PersonDictionary =
            PersonDictionary(persons.flatMap { p -> p.matchNames().map { it to p.name } }.toMap())
    }
}
