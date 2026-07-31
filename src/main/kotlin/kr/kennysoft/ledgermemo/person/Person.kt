package kr.kennysoft.ledgermemo.person

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 사람 마스터. 파서의 이름 사전이면서 사람별 미정산 조회의 기준이다 (DESIGN.md 3.2).
 */
@Entity
@Table(name = "person")
class Person(

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    /**
     * 별칭 목록. 쉼표로 구분한다 (`정민,박정민`).
     *
     * 별도 테이블로 두지 않은 것은 한 사람당 별칭이 많아야 두어 개이고, 파서가 매번
     * 전체를 메모리에 올려 매칭하기 때문이다.
     */
    @Column(name = "aliases", length = 500)
    var aliases: String? = null,

    /** 더 이상 등장하지 않는 사람을 사전에서 빼되 과거 기록은 유지하기 위한 플래그. */
    @Column(name = "active", nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    /** 이름과 별칭을 합친 매칭 후보. */
    fun matchNames(): List<String> =
        listOf(name) + (aliases?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
}
