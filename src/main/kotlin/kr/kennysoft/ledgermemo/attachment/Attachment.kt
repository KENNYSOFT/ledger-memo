package kr.kennysoft.ledgermemo.attachment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import kr.kennysoft.ledgermemo.entry.Entry
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

/**
 * 영수증 사진. 파일은 파일시스템에 두고 DB 에는 메타데이터만 남긴다 (DESIGN.md 3.2).
 *
 * 리사이즈와 썸네일 생성은 클라이언트가 하므로 서버는 받은 바이트를 그대로 저장한다
 * (DESIGN.md 1.4 — native image 에서 ImageIO 의존을 만들지 않기 위함).
 */
@Entity
@Table(name = "attachment")
class Attachment(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    var entry: Entry,

    /** 저장 루트로부터의 상대 경로 (`2026/07/{uuid}.jpg`). */
    @Column(name = "file_path", nullable = false, length = 300)
    var filePath: String,

    /** 썸네일 상대 경로. 클라이언트가 함께 올렸을 때만 채워진다. */
    @Column(name = "thumb_path", length = 300)
    var thumbPath: String? = null,

    @Column(name = "content_type", nullable = false, length = 50)
    var contentType: String,

    @Column(name = "bytes", nullable = false)
    var bytes: Int,

    /** EXIF 촬영 시각. 클라이언트가 추출해 보낸다 (DESIGN.md 1.3). */
    @Column(name = "shot_at")
    var shotAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
}
