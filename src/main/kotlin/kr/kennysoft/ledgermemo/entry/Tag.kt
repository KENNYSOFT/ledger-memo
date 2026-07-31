package kr.kennysoft.ledgermemo.entry

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** `#회사` 처럼 입력에 붙은 분류 태그. 재사용을 위해 마스터로 둔다. */
@Entity
@Table(name = "tag")
class Tag(

    @Column(name = "name", nullable = false, length = 100)
    var name: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
}
