package kr.kennysoft.ledgermemo.person

import org.springframework.data.jpa.repository.JpaRepository

interface PersonRepository : JpaRepository<Person, Long> {

    /** 파서의 이름 사전. 비활성 인물은 새 입력에서 매칭하지 않는다. */
    fun findByActiveTrue(): List<Person>

    fun findByName(name: String): Person?
}
