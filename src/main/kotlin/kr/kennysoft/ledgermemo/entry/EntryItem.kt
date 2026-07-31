package kr.kennysoft.ledgermemo.entry

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 한 줄 입력에서 파싱된 품목. `2인세트 4.5` 한 덩어리가 한 행이 된다.
 *
 * 파싱이 틀릴 수 있으므로 모든 값이 nullable 이다. 원문은 [Entry.rawText] 가 보존한다.
 */
@Entity
@Table(name = "entry_item")
class EntryItem(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    var entry: Entry,

    /** 입력에 나타난 순서. 원문 순서를 재현하기 위해 보존한다. */
    @Column(name = "seq", nullable = false)
    var seq: Int,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    /** `소주2` 처럼 품목명 끝에 붙은 수량. */
    @Column(name = "qty")
    var qty: Int? = null,

    /** `1100x2` 처럼 단가가 명시된 경우에만 채운다. */
    @Column(name = "unit_price")
    var unitPrice: Int? = null,

    /** 이 품목의 금액. qty/unitPrice 가 있으면 그 곱. */
    @Column(name = "amount")
    var amount: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
}
