package kr.kennysoft.ledgermemo.entry

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import kr.kennysoft.ledgermemo.attachment.Attachment
import kr.kennysoft.ledgermemo.person.EntryPerson
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

    /**
     * 파싱된 품목. 재파싱 때 통째로 갈아끼우므로 orphanRemoval 을 켠다.
     */
    @OneToMany(mappedBy = "entry", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    var items: MutableList<EntryItem> = mutableListOf()

    @OneToMany(mappedBy = "entry", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var persons: MutableList<EntryPerson> = mutableListOf()

    @OneToMany(mappedBy = "entry", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var attachments: MutableList<Attachment> = mutableListOf()

    /**
     * 태그는 마스터를 공유하므로 cascade 를 걸지 않는다. 새 태그 생성은 TagService 가
     * 담당한다 (cascade PERSIST 를 걸면 같은 이름 태그가 중복 저장되어 unique 제약에 걸린다).
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "entry_tag",
        joinColumns = [JoinColumn(name = "entry_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")],
    )
    var tags: MutableSet<Tag> = mutableSetOf()

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    /** 품목을 통째로 교체한다. 파싱 결과 반영과 재파싱에서 함께 쓴다. */
    fun replaceItems(newItems: List<EntryItem>) {
        items.clear()
        items.addAll(newItems)
    }
}
