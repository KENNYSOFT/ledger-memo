package kr.kennysoft.ledgermemo.person

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import kr.kennysoft.ledgermemo.entry.Entry

/**
 * 거래에 동석한 사람. 정산이 필요한 경우 [shareAmount] 와 [settled] 로 추적한다.
 *
 * 본 가계부에 `채권: 양진혁 5/23 통행료` 처럼 사람 단위 채권이 쌓이므로, 여기서 미정산
 * 합계를 보고 그대로 옮긴다 (DESIGN.md 3.2).
 */
@Entity
@Table(name = "entry_person")
class EntryPerson(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    var entry: Entry,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    var person: Person,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: EntryPersonRole,

    /** 이 사람이 부담해야 할 금액. 모르면 비워 둔다. */
    @Column(name = "share_amount")
    var shareAmount: Int? = null,

    @Column(name = "settled", nullable = false)
    var settled: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
}
