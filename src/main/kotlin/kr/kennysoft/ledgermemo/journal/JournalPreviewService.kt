package kr.kennysoft.ledgermemo.journal

import kr.kennysoft.ledgermemo.entry.Entry
import kr.kennysoft.ledgermemo.parse.ParsedLine
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 기록을 분개장 행으로 옮긴다.
 *
 * 본 가계부의 실제 분개를 따른 구조다 — **비용 행 N개(대변) + 결제 행 1개(차변)**. 비용 행에는
 * 결제수단/결제처를 적지 않고 소득관련은 `해당 없음` 이며, 결제 행에만 결제수단·결제처·
 * 소득관련이 들어간다.
 *
 * 계정을 확정할 수 없으면 `?` 를 남긴다. 지어내는 것보다 눈에 보이게 두는 편이 낫다 —
 * 그대로 옮기면 분개장이 틀어지기 때문이다.
 */
@Service
class JournalPreviewService {

    fun preview(entry: Entry): JournalPreview = build(
        date = entry.occurredOn,
        time = entry.occurredAt,
        place = entry.place,
        items = entry.items.map { PreviewItem(it.name, it.qty, it.amount) },
        totalAmount = entry.totalAmount,
        categoryHint = entry.categoryHint,
        paymentHint = entry.paymentHint,
        memo = entry.memo,
    )

    fun preview(parsed: ParsedLine, categoryHint: String? = null, paymentHint: String? = null): JournalPreview =
        build(
            date = parsed.occurredOn,
            time = parsed.occurredAt,
            place = parsed.place,
            items = parsed.items.map { PreviewItem(it.name, it.qty, it.amount) },
            totalAmount = parsed.totalAmount,
            categoryHint = categoryHint,
            paymentHint = paymentHint,
            memo = parsed.memo,
        )

    private data class PreviewItem(val name: String, val qty: Int?, val amount: Int?)

    private fun build(
        date: LocalDate,
        time: LocalTime?,
        place: String?,
        items: List<PreviewItem>,
        totalAmount: Int?,
        categoryHint: String?,
        paymentHint: String?,
        memo: String?,
    ): JournalPreview {
        val expenseAccount = categoryHint?.takeIf { it.isNotBlank() }
            ?.let { "비용: $it" }
            ?: UNKNOWN_EXPENSE
        val payment = resolvePayment(paymentHint)

        val rows = mutableListOf<JournalRow>()

        // 금액이 있는 품목만 비용 행이 된다. 금액을 모르는 품목은 분개할 수 없다.
        val priced = items.filter { it.amount != null }
        priced.forEach { item ->
            rows += JournalRow(
                item = EXPENSE_ITEM,
                amount = requireNotNull(item.amount),
                incomeRelated = NOT_APPLICABLE,
                title = item.name,
                paymentMethod = "",
                payee = "",
                account = expenseAccount,
                // 수량은 제목에 넣으면 지저분해지므로 메모로 보낸다.
                memo = item.qty?.let { "${it}개" } ?: "",
            )
        }

        // 품목을 못 잡았으면 합계만으로 비용 한 줄을 만든다 (사진만 있는 기록 등).
        val settlement = totalAmount ?: priced.sumOf { requireNotNull(it.amount) }.takeIf { it != 0 }
        if (rows.isEmpty() && settlement != null) {
            rows += JournalRow(
                item = EXPENSE_ITEM,
                amount = settlement,
                incomeRelated = NOT_APPLICABLE,
                title = place ?: UNKNOWN_TITLE,
                paymentMethod = "",
                payee = "",
                account = expenseAccount,
                memo = memo.orEmpty(),
            )
        }

        if (settlement != null) {
            rows += JournalRow(
                item = payment.item,
                amount = settlement,
                incomeRelated = payment.incomeRelated,
                // 결제 행의 제목은 거래를 대표한다. 장소가 없으면 첫 품목명을 쓴다.
                title = place ?: priced.firstOrNull()?.name ?: UNKNOWN_TITLE,
                paymentMethod = payment.method,
                payee = place.orEmpty(),
                account = payment.account,
                memo = "",
            )
        }

        return JournalPreview(
            date = date.format(DATE_FORMAT),
            time = time?.format(TIME_FORMAT).orEmpty(),
            rows = rows,
            debitTotal = rows.filter { it.item.endsWith(DEBIT) }.sumOf { it.amount },
            creditTotal = rows.filter { it.item.endsWith(CREDIT) }.sumOf { it.amount },
            needsAttention = rows.any { it.account.contains('?') },
        )
    }

    /** 결제수단 문자열에서 항목/계정/소득관련을 정한다. */
    private fun resolvePayment(hint: String?): Payment {
        val text = hint?.trim().orEmpty()
        if (text.isEmpty()) return Payment(DEBIT_ASSET, UNKNOWN_ASSET, "", "")

        return when {
            // 카드는 체크든 신용이든 `카드:` 계정이라 부채가 늘어난다 (본 가계부 실제 분개).
            // 체크카드는 즉시 정산되어 부채 감소와 계좌 차감이 따라붙지만(grammar LM + D),
            // 그 3행은 결제수단 문자열만으로 만들 수 없어 사용자가 시트에서 채운다.
            // 여기서는 소득관련만 구분한다.
            CHECK_CARD_HINTS.any { text.contains(it) } ->
                Payment(DEBIT_LIABILITY, "카드: $text", "직불카드 등", text)
            text.contains("카드") ->
                Payment(DEBIT_LIABILITY, "카드: $text", "신용카드", text)
            ACCOUNT_HINTS.any { text.contains(it) } ->
                Payment(DEBIT_ASSET, "계좌: $text", "직불카드 등", text)
            text.contains("현금") ->
                Payment(DEBIT_ASSET, "자산: 현금", "현금영수증", text)
            // 페이/포인트류는 자산 차감이지만 계정명을 확정할 수 없다.
            else -> Payment(DEBIT_ASSET, "자산: $text", "직불카드 등", text)
        }
    }

    private data class Payment(
        val item: String,
        val account: String,
        val incomeRelated: String,
        val method: String,
    )

    private companion object {
        val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

        const val DEBIT = "차변"
        const val CREDIT = "대변"

        const val EXPENSE_ITEM = "비용의 발생 | 대변"
        const val DEBIT_ASSET = "자산의 감소 | 차변"
        const val DEBIT_LIABILITY = "부채의 증가 | 차변"

        const val NOT_APPLICABLE = "해당 없음"
        const val UNKNOWN_EXPENSE = "비용: ?"
        const val UNKNOWN_ASSET = "자산: ?"
        const val UNKNOWN_TITLE = "(제목 없음)"

        /** 체크/직불은 이름에 "카드" 가 들어가도 부채가 아니라 계좌 차감이다. */
        val CHECK_CARD_HINTS = listOf("체크", "직불")
        val ACCOUNT_HINTS = listOf("계좌", "은행", "이체", "계좌이체")
    }
}
