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
 *
 * 규칙은 실제 임포트 결과를 보고 늘려 왔다. 지원 표기는 [parseStandaloneAmount] 와
 * 상수 목록을 참고.
 */
@Component
class LineParser {

    /**
     * 줄에 날짜나 시각이 적혀 있는지.
     *
     * 일괄 임포트에서 "이 줄이 독립된 기록인가, 직전 기록의 하위 항목인가"를 가르는 데 쓴다.
     * Keep 에서 상하관계로 적어둔 하위 항목에는 날짜/시각이 없다.
     */
    fun hasDateOrTime(line: String): Boolean =
        DATE.containsMatchIn(line) || TIME.containsMatchIn(line)

    fun parse(
        text: String,
        dictionary: PersonDictionary = PersonDictionary.EMPTY,
        today: LocalDate,
        now: LocalTime,
    ): ParsedLine {
        // 물음표는 불확실 표시일 뿐 값의 일부가 아니므로 떼어내고 판단한다.
        // 쉼표는 항목 구분자로 쓰이므로(`콘칩 2.5, 테라 5.5x3`) 공백과 같이 취급한다.
        val uncertain = text.contains('?')
        val tokens = text.replace('?', ' ').replace(',', ' ')
            .split(' ', '\t', '\n')
            .filter { it.isNotBlank() }

        val state = ParseState(dictionary, today)
        tokens.forEach { state.consume(it) }
        state.finish()

        val amounts = state.items.mapNotNull { it.amount }
        return ParsedLine(
            occurredOn = state.date ?: today,
            occurredAt = state.time ?: now.withSecond(0).withNano(0),
            place = state.place,
            items = state.items,
            // 원문에 "총 N" 이 있으면 그것이 사용자가 적어둔 합계다. 품목 합보다 신뢰한다
            // (품목을 다 적지 않거나 할인이 섞인 경우가 있어 품목 합이 실제와 다르다).
            totalAmount = state.statedTotal ?: if (amounts.isEmpty()) null else amounts.sum(),
            // distinct() 는 결과가 비면 kotlin.collections.EmptyList 를 돌려주고, 그것이
            // native image 에서 Jackson 직렬화를 깨뜨린다 (KotlinCollectionsRuntimeHints 참고).
            personNames = ArrayList(state.persons.distinct()),
            headcount = state.headcount,
            tags = ArrayList(state.tags.distinct()),
            uncertain = uncertain,
            memo = state.memo(),
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

        /** 원문에 "총 N" 으로 적힌 합계. */
        var statedTotal: Int? = null
        val tags = mutableListOf<String>()
        val persons = mutableListOf<String>()
        val items = mutableListOf<ParsedItem>()

        /** 아직 품목명/장소로 확정되지 않은 토큰. */
        private val pending = mutableListOf<String>()
        private val memoParts = mutableListOf<String>()
        private var amountSeen = false

        /** "총" 을 만난 직후. 다음 금액은 품목이 아니라 합계다. */
        private var totalPending = false

        /** `× 2` 처럼 곱셈 기호 뒤에 떨어져 적힌 수량. */
        private var expectQty = false
        private var explicitQty: Int? = null

        fun memo(): String? = memoParts.joinToString(" ").takeIf { it.isNotBlank() }

        fun consume(token: String) {
            if (token.startsWith("#") && token.length > 1) {
                tags += token.substring(1)
                return
            }
            // "총 9.6" 처럼 합계를 적어둔 경우. 품목이 아니므로 다음 금액을 합계로 받는다.
            if (token in TOTAL_MARKERS) {
                totalPending = true
                return
            }
            // 곱셈 기호가 수량과 떨어져 있는 표기 (`맥주 × 2 8k`).
            if (token in MULTIPLY_MARKERS) {
                expectQty = true
                return
            }
            if (expectQty) {
                expectQty = false
                val qty = token.toIntOrNull()
                if (qty != null && qty in 1..MAX_QTY) {
                    explicitQty = qty
                    return
                }
                // 수량이 아니면 아래 일반 처리로 흘려보낸다.
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
                if (totalPending) {
                    // 합계를 품목으로 만들면 합계가 이중 계산된다.
                    statedTotal = standalone.value
                    totalPending = false
                    flushPending()
                    return
                }
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

        /**
         * 금액 없이 끝난 토큰을 정리한다.
         *
         * 🚨 **금액이 이미 나온 뒤에 남은 토큰은 장소가 아니다.** 사람 이름이나 부연 설명인
         * 경우가 대부분이라, 장소로 만들면 `"제로슈가라거 4.9 나"` 의 장소가 `"나"` 가 된다.
         */
        fun finish() {
            if (pending.isEmpty()) return
            if (!amountSeen && place == null) {
                place = pending.removeAt(0)
            }
            flushPending()
        }

        /**
         * 남은 토큰을 품목 또는 메모로 내보낸다.
         *
         * 토큰이 여러 개면 품목명이 아니라 자유 서술이다("한번에 적립된 듯한데 내역 확인이
         * 안됨..."). 품목으로 만들면 목록이 지저분해지므로 메모로 흘려보낸다.
         */
        private fun flushPending() {
            if (pending.isEmpty()) return
            val tokenCount = pending.size
            val text = pending.joinToString(" ")
            pending.clear()

            if (tokenCount >= MEMO_TOKEN_THRESHOLD) {
                memoParts += text
            } else {
                items += ParsedItem(name = text, qty = explicitQty, unitPrice = null, amount = null)
                explicitQty = null
            }
        }

        private fun addItem(rawName: String?, amount: Amount) {
            val name = rawName ?: UNKNOWN_ITEM
            val (baseName, trailingQty) = splitTrailingQty(name)
            items += ParsedItem(
                name = baseName,
                // 명시된 수량(1100x2, × 2)이 품목명 끝 숫자보다 신뢰도가 높다.
                qty = amount.qty ?: explicitQty ?: trailingQty,
                unitPrice = amount.unitPrice,
                amount = amount.value,
            )
            explicitQty = null
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

        /** 단가x수량. 단가에 소수점이 오면(3.1x4) 만원 단위다. */
        val UNIT_PRICE = Regex("""(\d+(?:\.\d+)?)[xX*×](\d+)""")
        val MAN = Regex("""(\d+(?:\.\d+)?)만원?""")

        /** `8k` = 8,000원. 메뉴판이나 메모에서 흔한 천원 단위 표기. */
        val THOUSAND = Regex("""(\d+(?:\.\d+)?)[kK]""")
        val DECIMAL = Regex("""\d+\.\d+""")
        val WON = Regex("""\d{3,}""")

        /** `124000원` 처럼 단위를 붙여 적은 원 단위. `만` 이 붙은 것은 MAN 이 먼저 잡는다. */
        val WON_SUFFIX = Regex("""(\d+)원""")

        val NAME_MAN = Regex("""(.+?)(\d+(?:\.\d+)?)만원?""")
        val NAME_THOUSAND = Regex("""(.+?)(\d+(?:\.\d+)?)[kK]""")
        val NAME_DECIMAL = Regex("""(.+?)(\d+\.\d+)""")
        val NAME_WON_SUFFIX = Regex("""(.+?)(\d+)원""")
        val NAME_WON = Regex("""(.+?)(\d{3,})""")

        /** 품목명 끝 1~2자리 숫자는 수량. 3자리 이상은 금액이라 여기서 다루지 않는다. */
        val TRAILING_QTY = Regex("""(.+?)(\d{1,2})""")

        /** 사전 없이도 태그로 인식할 키워드 (DESIGN.md 2.2). */
        val KEYWORD_TAGS = setOf("회사", "가족")

        /** 뒤에 오는 금액이 합계임을 알리는 표기. */
        val TOTAL_MARKERS = setOf("총", "합계", "총액")

        /** 수량과 떨어져 적힌 곱셈 기호. */
        val MULTIPLY_MARKERS = setOf("x", "X", "*", "×")

        const val MAX_QTY = 99

        /** 이 개수 이상의 토큰이 뭉쳐 있으면 품목명이 아니라 서술로 본다. */
        const val MEMO_TOKEN_THRESHOLD = 5

        const val UNKNOWN_ITEM = "미상"

        /** 소수점 표기는 만원 단위다 (`4.5` = 45,000). */
        fun manToWon(text: String): Int =
            BigDecimal(text).multiply(BigDecimal(10_000)).toInt()

        /** `8k` = 8,000. */
        fun thousandToWon(text: String): Int =
            BigDecimal(text).multiply(BigDecimal(1_000)).toInt()

        fun parseStandaloneAmount(token: String): Amount? {
            UNIT_PRICE.matchEntire(token)?.let { m ->
                val unitText = m.groupValues[1]
                val qty = m.groupValues[2].toIntOrNull() ?: return@let
                // 소수점 단가는 만원 단위 (`3.1x4` = 31,000 x 4).
                val unit = if (unitText.contains('.')) manToWon(unitText) else unitText.toIntOrNull() ?: return@let
                return Amount(value = unit * qty, qty = qty, unitPrice = unit)
            }
            MAN.matchEntire(token)?.let { m ->
                return Amount(manToWon(m.groupValues[1]))
            }
            THOUSAND.matchEntire(token)?.let { m ->
                return Amount(thousandToWon(m.groupValues[1]))
            }
            if (DECIMAL.matches(token)) {
                return Amount(manToWon(token))
            }
            WON_SUFFIX.matchEntire(token)?.let { m ->
                return m.groupValues[1].toIntOrNull()?.let { Amount(it) }
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
            NAME_THOUSAND.matchEntire(token)?.let { m ->
                return m.groupValues[1] to Amount(thousandToWon(m.groupValues[2]))
            }
            NAME_DECIMAL.matchEntire(token)?.let { m ->
                return m.groupValues[1] to Amount(manToWon(m.groupValues[2]))
            }
            NAME_WON_SUFFIX.matchEntire(token)?.let { m ->
                val won = m.groupValues[2].toIntOrNull() ?: return@let
                return m.groupValues[1] to Amount(won)
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
