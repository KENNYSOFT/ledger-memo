package kr.kennysoft.ledgermemo.parse

import kr.kennysoft.ledgermemo.person.Person
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 파서 명세 검증 (DESIGN.md 2장).
 *
 * 기준일/기준시각을 고정 주입해 실행 시점에 결과가 흔들리지 않게 한다.
 */
class LineParserTests {

    private val parser = LineParser()
    private val today = LocalDate.of(2026, 7, 31)
    private val now = LocalTime.of(21, 37)

    private fun parse(text: String, dictionary: PersonDictionary = PersonDictionary.EMPTY) =
        parser.parse(text, dictionary, today, now)

    @Test
    fun `DESIGN 검증 예제 1 - 2인 술자리 합계가 정합한다`() {
        // given
        val text = "원조해장촌 2인세트 4.5 소주2 1.0 맥주3 1.5"

        // when
        val result = parse(text)

        // then
        assertEquals("원조해장촌", result.place)
        assertEquals(70_000, result.totalAmount)
        assertEquals(3, result.items.size)

        assertEquals("2인세트", result.items[0].name)
        assertEquals(45_000, result.items[0].amount)
        assertNull(result.items[0].qty)

        assertEquals("소주", result.items[1].name)
        assertEquals(2, result.items[1].qty)
        assertEquals(10_000, result.items[1].amount)

        assertEquals("맥주", result.items[2].name)
        assertEquals(3, result.items[2].qty)
        assertEquals(15_000, result.items[2].amount)
    }

    @Test
    fun `DESIGN 검증 예제 2 - 품목명에 붙은 금액과 물음표를 처리한다`() {
        // given
        val text = "싸리골 해물파전2.3 지평2 1.0 콜라 0.2?"

        // when
        val result = parse(text)

        // then
        assertEquals("싸리골", result.place)
        assertEquals(35_000, result.totalAmount)
        assertTrue(result.uncertain)

        assertEquals("해물파전", result.items[0].name)
        assertEquals(23_000, result.items[0].amount)

        assertEquals("지평", result.items[1].name)
        assertEquals(2, result.items[1].qty)
        assertEquals(10_000, result.items[1].amount)

        assertEquals("콜라", result.items[2].name)
        assertEquals(2_000, result.items[2].amount)
    }

    @Test
    fun `3자리 이상 정수는 원 단위다`() {
        // when
        val result = parse("택시 8100")

        // then
        assertEquals(8_100, result.totalAmount)
        assertEquals("택시", result.items.single().name)
        // 토큰이 하나뿐이면 장소가 아니라 품목명으로 본다
        assertNull(result.place)
    }

    @Test
    fun `만 표기는 만원 단위다`() {
        assertEquals(30_000, parse("모바일선물 3만원 등록").totalAmount)
        assertEquals(30_000, parse("모바일선물 3만").totalAmount)
        assertEquals(15_000, parse("선물 1.5만").totalAmount)
    }

    @Test
    fun `단가 곱하기 수량을 계산한다`() {
        // when
        val result = parse("아이시스500ML 1100x2")

        // then
        val item = result.items.single()
        // 품목명 끝 숫자가 아니라 문자로 끝나므로 수량으로 떼지 않는다
        assertEquals("아이시스500ML", item.name)
        assertEquals(1_100, item.unitPrice)
        assertEquals(2, item.qty)
        assertEquals(2_200, item.amount)
    }

    @Test
    fun `별표도 곱하기로 인식한다`() {
        assertEquals(2_200, parse("생수 1100*2").totalAmount)
    }

    @Test
    fun `날짜는 M-D 형태만 인식하고 없으면 오늘이다`() {
        assertEquals(LocalDate.of(2026, 5, 31), parse("5/31 택시 8100").occurredOn)
        assertEquals(today, parse("택시 8100").occurredOn)
    }

    @Test
    fun `시각은 콜론이 있는 것만 인식한다`() {
        // 콜론이 있으면 시각
        assertEquals(LocalTime.of(21, 37), parse("21:37 택시 8100").occurredAt)

        // 콜론이 없는 네 자리는 금액과 구분할 수 없으므로 시각이 아니다 (DESIGN.md 2.2)
        val result = parse("택시 2137")
        assertEquals(now, result.occurredAt)
        assertEquals(2_137, result.totalAmount)
    }

    @Test
    fun `사람 사전에 있는 이름과 별칭을 인식한다`() {
        // given
        val dictionary = PersonDictionary.of(
            listOf(
                Person(name = "박정민", aliases = "정민"),
                Person(name = "박채원"),
            ),
        )

        // when
        val result = parse("원조해장촌 2인세트 4.5 정민 박채원", dictionary)

        // then — 별칭으로 적어도 정식 이름으로 돌아온다
        assertEquals(listOf("박정민", "박채원"), result.personNames)
    }

    @Test
    fun `인원수와 태그를 인식한다`() {
        // when
        val result = parse("택시 8100 3명 #출장 회사")

        // then
        assertEquals(3, result.headcount)
        assertEquals(listOf("출장", "회사"), result.tags)
    }

    @Test
    fun `장소만 적어도 유효하다`() {
        // when
        val result = parse("원조해장촌")

        // then
        assertEquals("원조해장촌", result.place)
        assertTrue(result.items.isEmpty())
        assertNull(result.totalAmount)
    }

    @Test
    fun `금액 없는 품목도 버리지 않는다`() {
        // when
        val result = parse("다이소 건전지")

        // then — 원문 보존 원칙상 인식 못한 토큰도 품목으로 남긴다
        assertEquals("다이소", result.place)
        assertEquals("건전지", result.items.single().name)
        assertNull(result.items.single().amount)
    }

    @Test
    fun `잘못된 날짜는 날짜로 보지 않는다`() {
        // when — 13월은 존재하지 않는다
        val result = parse("13/45 택시 8100")

        // then
        assertEquals(today, result.occurredOn)
    }

    @Test
    fun `빈 입력에도 예외를 던지지 않는다`() {
        // when
        val result = parse("   ")

        // then
        assertEquals(today, result.occurredOn)
        assertNull(result.place)
        assertTrue(result.items.isEmpty())
        assertNull(result.totalAmount)
    }
}
