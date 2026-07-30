package kr.kennysoft.ledgermemo.entry

/** 거래 초안 처리 상태. 분개장으로 옮기면 DONE 으로 바꿔 목록에서 숨긴다. */
enum class EntryStatus {
    OPEN,
    DONE,
}
