package kr.kennysoft.ledgermemo.person

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface EntryPersonRepository : JpaRepository<EntryPerson, Long> {

    /**
     * 사람별 미정산 합계. 금액이 정해진 것만 더한다 (shareAmount 가 비면 아직 판단 전).
     */
    @Query(
        """
        SELECT ep.person AS person,
               SUM(ep.shareAmount) AS totalAmount,
               COUNT(ep) AS entryCount
        FROM EntryPerson ep
        WHERE ep.settled = false
          AND ep.shareAmount IS NOT NULL
          AND ep.role <> kr.kennysoft.ledgermemo.person.EntryPersonRole.ATTENDEE
        GROUP BY ep.person
        ORDER BY SUM(ep.shareAmount) DESC
        """,
    )
    fun findUnsettledSummaries(): List<SettlementSummary>
}

/** 사람별 미정산 집계 결과. */
interface SettlementSummary {
    val person: Person
    val totalAmount: Long
    val entryCount: Long
}
