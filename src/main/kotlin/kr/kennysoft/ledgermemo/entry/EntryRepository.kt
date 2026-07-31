package kr.kennysoft.ledgermemo.entry

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional

interface EntryRepository : JpaRepository<Entry, Long> {

    /**
     * 목록 검색. 모든 조건이 nullable 이고 null 이면 그 조건을 적용하지 않는다.
     *
     * 검색어는 장소/원문/품목명을 함께 본다. 품목 조인으로 행이 늘어나므로 DISTINCT 가
     * 필요하다. 목록은 요약만 보여주므로 연관을 fetch 하지 않는다.
     */
    @Query(
        """
        SELECT DISTINCT e FROM Entry e
        LEFT JOIN e.items i
        WHERE (:status IS NULL OR e.status = :status)
          AND (:from IS NULL OR e.occurredOn >= :from)
          AND (:to IS NULL OR e.occurredOn <= :to)
          AND (
            :q IS NULL
            OR e.place LIKE CONCAT('%', :q, '%')
            OR e.rawText LIKE CONCAT('%', :q, '%')
            OR i.name LIKE CONCAT('%', :q, '%')
          )
          AND (:personId IS NULL OR EXISTS (
            SELECT 1 FROM EntryPerson ep WHERE ep.entry = e AND ep.person.id = :personId
          ))
        ORDER BY e.occurredOn DESC, e.occurredAt DESC, e.id DESC
        """,
        countQuery = """
        SELECT COUNT(DISTINCT e) FROM Entry e
        LEFT JOIN e.items i
        WHERE (:status IS NULL OR e.status = :status)
          AND (:from IS NULL OR e.occurredOn >= :from)
          AND (:to IS NULL OR e.occurredOn <= :to)
          AND (
            :q IS NULL
            OR e.place LIKE CONCAT('%', :q, '%')
            OR e.rawText LIKE CONCAT('%', :q, '%')
            OR i.name LIKE CONCAT('%', :q, '%')
          )
          AND (:personId IS NULL OR EXISTS (
            SELECT 1 FROM EntryPerson ep WHERE ep.entry = e AND ep.person.id = :personId
          ))
        """,
    )
    fun search(
        @Param("status") status: EntryStatus?,
        @Param("from") from: LocalDate?,
        @Param("to") to: LocalDate?,
        @Param("q") q: String?,
        @Param("personId") personId: Long?,
        pageable: Pageable,
    ): Page<Entry>

    /** 단건 조회. 상세가 품목을 항상 함께 쓰므로 한 번에 가져온다. */
    @EntityGraph(attributePaths = ["items"])
    fun findWithItemsById(id: Long): Optional<Entry>

    /** 작성 화면의 "최근 저장" 목록. */
    fun findTop3ByOrderByCreatedAtDesc(): List<Entry>
}
