package kr.kennysoft.ledgermemo.entry

import org.springframework.data.jpa.repository.JpaRepository

interface TagRepository : JpaRepository<Tag, Long> {

    fun findByNameIn(names: Collection<String>): List<Tag>
}
