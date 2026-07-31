package kr.kennysoft.ledgermemo.parse

import java.time.LocalDate
import java.time.LocalTime

/**
 * 한 줄 입력의 파싱 결과. 저장 전 미리보기(`POST /api/parse`)와 저장 시점 모두 같은 값을 쓴다.
 *
 * 파싱은 실패해도 예외를 던지지 않는다. 인식하지 못한 부분은 비워 두고 원문을 보존하며,
 * 사용자가 칩 UI 에서 고친다 (DESIGN.md 1.2 / 2.3).
 */
data class ParsedLine(
    val occurredOn: LocalDate,
    val occurredAt: LocalTime?,
    val place: String?,
    val items: List<ParsedItem>,
    val totalAmount: Int?,
    val personNames: List<String>,
    val headcount: Int?,
    val tags: List<String>,
    val uncertain: Boolean,

    /**
     * 품목으로 보기 어려운 자유 서술.
     *
     * 여러 토큰이 뭉쳐 있으면 품목명이 아니라 설명이므로 여기로 흘려보낸다. 원문은 그대로
     * 보존되지만, 목록에 쓸모없는 품목이 쌓이는 것을 막는다.
     */
    val memo: String?,
)

/** 파싱된 품목 하나. */
data class ParsedItem(
    val name: String,
    val qty: Int?,
    val unitPrice: Int?,
    val amount: Int?,
)
