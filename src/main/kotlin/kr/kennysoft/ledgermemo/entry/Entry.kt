package kr.kennysoft.ledgermemo.entry

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 거래 초안. 한 줄 입력 또는 사진 한 장으로 만들어진다.
 *
 * raw_text 는 입력 원문을 그대로 보존한다. 파싱이 틀려도 정보가 유실되지 않고,
 * 파서 규칙을 개선한 뒤 재파싱할 수 있다 (DESIGN.md 1.2).
 */
@Entity
@Table(name = "entry")
class Entry(

    @Column(name = "occurred_on", nullable = false)
    var occurredOn: LocalDate,

    /** 시각을 모르는 기록도 허용한다. */
    @Column(name = "occurred_at")
    var occurredAt: LocalTime? = null,

    @Column(name = "raw_text", columnDefinition = "TEXT")
    var rawText: String? = null,

    @Column(name = "place", length = 200)
    var place: String? = null,

    @Column(name = "total_amount")
    var totalAmount: Int? = null,

    @Column(name = "category_hint", length = 100)
    var categoryHint: String? = null,

    @Column(name = "payment_hint", length = 100)
    var paymentHint: String? = null,

    @Column(name = "headcount")
    var headcount: Int? = null,

    /** 입력에 물음표가 있었던 항목. 금액/내용이 불확실함을 표시. */
    @Column(name = "uncertain", nullable = false)
    var uncertain: Boolean = false,

    @Column(name = "memo", columnDefinition = "TEXT")
    var memo: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    var status: EntryStatus = EntryStatus.OPEN,

    @Column(name = "done_at")
    var doneAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
