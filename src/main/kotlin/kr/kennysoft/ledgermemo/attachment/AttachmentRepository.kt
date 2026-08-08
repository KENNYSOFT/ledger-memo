package kr.kennysoft.ledgermemo.attachment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AttachmentRepository : JpaRepository<Attachment, Long> {

    /**
     * 여러 기록의 첨부 개수를 한 번에 센다.
     *
     * 목록에서 `entry.attachments.size` 를 쓰면 행마다 쿼리가 나가고, `open-in-view=false`
     * 라 트랜잭션 밖에서는 아예 예외가 된다. 페이지의 id 로 한 번만 조회한다.
     */
    @Query(
        """
        SELECT a.entry.id AS entryId, COUNT(a) AS count, MIN(a.id) AS firstId
        FROM Attachment a
        WHERE a.entry.id IN :entryIds
        GROUP BY a.entry.id
        """,
    )
    fun countByEntryIds(@Param("entryIds") entryIds: Collection<Long>): List<EntryAttachmentCount>
}

/**
 * 기록별 첨부 개수와 대표 첨부.
 *
 * 목록에 썸네일을 띄우려면 개수만으로는 부족해 가장 먼저 올린 첨부의 id 를 함께 받는다.
 */
interface EntryAttachmentCount {
    val entryId: Long
    val count: Long
    val firstId: Long
}
