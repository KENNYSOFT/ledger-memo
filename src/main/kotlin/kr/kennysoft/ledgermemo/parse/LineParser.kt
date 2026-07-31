package kr.kennysoft.ledgermemo.parse

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * 한 줄 입력 파서. 규칙은 DESIGN.md 2장 명세를 따른다.
 *
 * 파싱은 **절대 예외를 던지지 않는다.** 인식하지 못한 토큰은 품목명으로 흘려보내고, 원문은
 * 호출자가 그대로 보존한다. 파서는 "대부분 맞으면 이득" 수준을 목표로 하며 칩 수정 UI 가
 * 안전망이다 (DESIGN.md 2.3).
 */
@Component
class LineParser {

    fun parse(
        text: String,
        dictionary: PersonDictionary = PersonDictionary.EMPTY,
        today: LocalDate,
        now: LocalTime,
    ): ParsedLine {
        // 물음표는 불확실 표시일 뿐 값의 일부가 아니므로 떼어내고 판단한다.
        val uncertain = text.contains('?')
        val tokens = text.replace('?', ' ').split(' ', '\t', '\n').filter { it.isNotBlank() }

        val state = ParseState(dictionary, today)
        tokens.forEach { state.consume(it) }
        state.finish()

        val amounts = state.items.mapNotNull { it.amount }
        return ParsedLine(
            occurredOn = state.date ?: today,
            occurredAt = state.time ?: now.withSecond(0).withNano(0),
            place = state.place,
            items = state.items,
            totalAmount = if (amounts.isEmpty()) null else amounts.sum(),
            personNames = state.persons.distinct(),
            headcount = state.headcount,
            tags = state.tags.distinct(),
            uncertain = uncertain,
        )
    }

    /**
     * 토큰을 순서대로 먹으며 결과를 쌓는 누적 상태.
     *
     * 품목명은 금액을 만나는 시점에 확정된다. 금액 앞에 쌓인 토큰이 둘 이상이고 아직 장소가
     * 없으면 선두 하나를 장소로 뗀다 (`원조해장촌 2인세트 4.5`).
     */
    private class ParseState(
        private val dictionary: PersonDictionary,
        /** `M/D` 에는 연도가 없으므로 기준일의 연도를 쓴다. */
        private val today: LocalDate,
    ) {
        var date: LocalDate? = null
        var time: LocalTime? = null
        var place: String? = null
        var headcount: Int? = null
        val tags = mutableListOf<String>()
        val persons = mutableListOf<String>()
        val items = mutableListOf<ParsedItem>()

        /** 아직 품목명/장소로 확정되지 않은 토큰. */
        private val pending = mutableListOf<String>()
        private var amountSeen = false

        fun consume(token: String) {
            if (token.startsWith("#") && token.length > 1) {
                tags += token.substring(1)
                return
            }
            // 형식은 맞지만 값이 범위를 벗어나면(13/45 등) 인식하지 않고 품목명으로 흘려보낸다.
            DATE.matchEntire(token)?.let { m ->
                val parsed = runCatching {
                    LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }.getOrNull()
                if (parsed != null) {
                    date = parsed
                    return
                }
            }
            TIME.matchEntire(token)?.let { m ->
                val parsed = runCatching {
                    LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }.getOrNull()
                if (parsed != null) {
                    time = parsed
                    return
                }
            }
            HEADCOUNT.matchEntire(token)?.let { m ->
                val parsed = m.groupValues[1].toIntOrNull()
                if (parsed != null) {
                    headcount = parsed
                    return
                }
            }
            dictionary.resolve(token)?.let {
                persons += it
                return
            }
            if (token in KEYWORD_TAGS) {
                tags += token
                return
            }

            val standalone = parseStandaloneAmount(token)
            if (standalone != null) {
                addItem(resolveName(), standalone)
                return
            }

            val attached = parseAttachedAmount(token)
            if (attached != null) {
                val (name, amount) = attached
                // 품목명이 토큰 안에 있으므로 앞에 남은 토큰은 하나여도 장소다 (`싸리골 해물파전2.3`).
                takePlace(minPending = 1)
                val prefix = pending.joinToString(" ").also { pending.clear() }
                addItem(if (prefix.isBlank()) name else "$prefix $name", amount)
                return
            }

            pending += token
        }

        /** 금액 없이 끝난 토큰을 정리한다. 장소만 적고 만 경우가 여기에 해당한다. */
        fun finish() {
            if (pending.isEmpty()) return
            if (place == null) {
                place = pending.removeAt(0)
            }
            if (pending.isNotEmpty()) {
                items += ParsedItem(name = pending.joinToString(" "), qty = null, unitPrice = null, amount = null)
                pending.clear()
            }
        }

        private fun addItem(rawName: String?, amount: Amount) {
            val name = rawName ?: UNKNOWN_ITEM
            val (baseName, trailingQty) = splitTrailingQty(name)
            items += ParsedItem(
                name = baseName,
                // 단가 표기(1100x2)의 수량이 품목명 끝 숫자보다 명시적이다.
                qty = amount.qty ?: trailingQty,
                unitPrice = amount.unitPrice,
                amount = amount.value,
            )
            amountSeen = true
        }

        /**
         * 금액을 만난 시점에 쌓인 토큰으로 품목명을 만든다.
         *
         * 남은 토큰이 하나뿐이면 장소가 아니라 품목명으로 본다 (`택시 8100` 의 `택시`).
         */
        private fun resolveName(): String? {
            takePlace(minPending = 2)
            if (pending.isEmpty()) return null
            return pending.joinToString(" ").also { pending.clear() }
        }

        /** 첫 금액 앞에 쌓인 토큰이 [minPending] 개 이상이면 선두를 장소로 뗀다. */
        private fun takePlace(minPending: Int) {
            if (!amountSeen && place == null && pending.size >= minPending) {
                place = pending.removeAt(0)
            }
        }
    }

    /** 금액 해석 결과. 단가 표기일 때만 [qty] 와 [unitPrice] 가 채워진다. */
    private data class Amount(val value: Int, val qty: Int? = null, val unitPrice: Int? = null)

    private companion object {
        val DATE = Regex("""(\d{1,2})/(\d{1,2})""")

        /** 콜론이 있는 것만 시각으로 본다. `2137` 은 금액과 구분할 수 없다 (DESIGN.md 2.2). */
        val TIME = Regex("""(\d{1,2}):(\d{2})""")
        val HEADCOUNT = Regex("""(\d+)명""")

        val UNIT_PRICE = Regex("""(\d+)[xX*](\d+)""")
        val MAN = Regex("""(\d+(?:\.\d+)?)만원?""")
        val DECIMAL = Regex("""\d+\.\d+""")
        val WON = Regex("""\d{3,}""")

        val NAME_MAN = Regex("""(.+?)(\d+(?:\.\d+)?)만원?""")
        val NAME_DECIMAL = Regex("""(.+?)(\d+\.\d+)""")
        val NAME_WON = Regex("""(.+?)(\d{3,})""")

        /** 품목명 끝 1~2자리 숫자는 수량. 3자리 이상은 금액이라 여기서 다루지 않는다. */
        val TRAILING_QTY = Regex("""(.+?)(\d{1,2})""")

        /** 사전 없이도 태그로 인식할 키워드 (DESIGN.md 2.2). */
        val KEYWORD_TAGS = setOf("회사", "가족")

        const val UNKNOWN_ITEM = "미상"

        /** 소수점 표기는 만원 단위다 (`4.5` = 45,000). */
        fun manToWon(text: String): Int =
            BigDecimal(text).multiply(BigDecimal(10_000)).toInt()

        fun parseStandaloneAmount(token: String): Amount? {
            UNIT_PRICE.matchEntire(token)?.let { m ->
                val unit = m.groupValues[1].toIntOrNull() ?: return@let
                val qty = m.groupValues[2].toIntOrNull() ?: return@let
                return Amount(value = unit * qty, qty = qty, unitPrice = unit)
            }
            MAN.matchEntire(token)?.let { m ->
                return Amount(manToWon(m.groupValues[1]))
            }
            if (DECIMAL.matches(token)) {
                return Amount(manToWon(token))
            }
            if (WON.matches(token)) {
                return token.toIntOrNull()?.let { Amount(it) }
            }
            return null
        }

        /** `해물파전2.3` 처럼 품목명과 금액이 붙은 토큰. */
        fun parseAttachedAmount(token: String): Pair<String, Amount>? {
            NAME_MAN.matchEntire(token)?.let { m ->
                return m.groupValues[1] to Amount(manToWon(m.groupValues[2]))
            }
            NAME_DECIMAL.matchEntire(token)?.let { m ->
                return m.groupValues[1] to Amount(manToWon(m.groupValues[2]))
            }
            NAME_WON.matchEntire(token)?.let { m ->
                val won = m.groupValues[2].toIntOrNull() ?: return@let
                return m.groupValues[1] to Amount(won)
            }
            return null
        }

        /** `소주2` → (소주, 2). `아이시스500ML` 처럼 끝이 문자면 분리하지 않는다. */
        fun splitTrailingQty(name: String): Pair<String, Int?> {
            val m = TRAILING_QTY.matchEntire(name) ?: return name to null
            val qty = m.groupValues[2].toIntOrNull() ?: return name to null
            return m.groupValues[1] to qty
        }
    }
}
