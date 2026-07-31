package kr.kennysoft.ledgermemo.person

/**
 * 거래에서 그 사람이 가지는 정산상 위치.
 *
 * 파서는 이름만 인식하므로 기본값은 [ATTENDEE] 이고, 정산 관계가 확정되면 사용자가
 * 상세 화면에서 바꾼다.
 */
enum class EntryPersonRole {

    /** 동석했으나 금전 관계는 아직 미정. 파서가 이름을 찾았을 때의 기본값. */
    ATTENDEE,

    /** 내가 대신 냈고 받을 사람 (채권). */
    DEBTOR,

    /** 그 사람이 대신 냈고 갚을 대상 (채무). */
    CREDITOR,
}
