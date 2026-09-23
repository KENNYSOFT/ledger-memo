package kr.kennysoft.ledgermemo.parse

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kr.kennysoft.ledgermemo.person.Person
import java.time.LocalDate
import java.time.LocalTime

/**
 * 파서 명세 검증 (DESIGN.md 2장).
 *
 * 기준일/기준시각을 고정 주입해 실행 시점에 결과가 흔들리지 않게 한다.
 */
class LineParserTests : FreeSpec({

    val parser = LineParser()
    val today = LocalDate.of(2026, 7, 31)

    fun parse(text: String, dictionary: PersonDictionary = PersonDictionary.EMPTY) =
        parser.parse(text, dictionary, today)

    "DESIGN 검증 예제" - {
        "1 - 2인 술자리 합계가 정합한다" {
            // given
            val text = "원조해장촌 2인세트 4.5 소주2 1.0 맥주3 1.5"

            // when
            val result = parse(text)

            // then
            result.place shouldBe "원조해장촌"
            result.totalAmount shouldBe 70_000
            result.items shouldHaveSize 3

            result.items[0].name shouldBe "2인세트"
            result.items[0].amount shouldBe 45_000
            result.items[0].qty.shouldBeNull()

            result.items[1].name shouldBe "소주"
            result.items[1].qty shouldBe 2
            result.items[1].amount shouldBe 10_000

            result.items[2].name shouldBe "맥주"
            result.items[2].qty shouldBe 3
            result.items[2].amount shouldBe 15_000
        }

        "2 - 품목명에 붙은 금액과 물음표를 처리한다" {
            // given
            val text = "싸리골 해물파전2.3 지평2 1.0 콜라 0.2?"

            // when
            val result = parse(text)

            // then
            result.place shouldBe "싸리골"
            result.totalAmount shouldBe 35_000
            result.uncertain.shouldBeTrue()

            result.items[0].name shouldBe "해물파전"
            result.items[0].amount shouldBe 23_000

            result.items[1].name shouldBe "지평"
            result.items[1].qty shouldBe 2
            result.items[1].amount shouldBe 10_000

            result.items[2].name shouldBe "콜라"
            result.items[2].amount shouldBe 2_000
        }
    }

    "금액" - {
        "3자리 이상 정수는 원 단위다" {
            // when
            val result = parse("택시 8100")

            // then
            result.totalAmount shouldBe 8_100
            result.items.single().name shouldBe "택시"
            // 토큰이 하나뿐이면 장소가 아니라 품목명으로 본다
            result.place.shouldBeNull()
        }

        "만 표기는 만원 단위다" {
            parse("모바일선물 3만원 등록").totalAmount shouldBe 30_000
            parse("모바일선물 3만").totalAmount shouldBe 30_000
            parse("선물 1.5만").totalAmount shouldBe 15_000
        }

        "k 표기는 천원 단위다" {
            parse("맥주 8k").totalAmount shouldBe 8_000
            parse("숙성 통삼겹살 36k").totalAmount shouldBe 36_000
            parse("초연 세트 79k").totalAmount shouldBe 79_000
            parse("공기밥 1.5k").totalAmount shouldBe 1_500
        }

        "원 단위를 붙여 적은 금액도 인식한다" {
            // 실제 사례: "돈연 124000원 추정"
            parse("돈연 124000원").totalAmount shouldBe 124_000
            // "만원"이 붙은 것은 만원 단위로 먼저 잡는다
            parse("선물 3만원").totalAmount shouldBe 30_000
        }

        "단가 곱하기 수량을 계산한다" {
            // when
            val result = parse("아이시스500ML 1100x2")

            // then
            val item = result.items.single()
            // 품목명 끝 숫자가 아니라 문자로 끝나므로 수량으로 떼지 않는다
            item.name shouldBe "아이시스500ML"
            item.unitPrice shouldBe 1_100
            item.qty shouldBe 2
            item.amount shouldBe 2_200
        }

        "별표도 곱하기로 인식한다" {
            parse("생수 1100*2").totalAmount shouldBe 2_200
        }

        "곱셈 기호가 수량과 떨어져 있어도 인식한다" {
            // when - 실제 사례: "맥주 × 2 8k"
            val result = parse("맥주 × 2 8k")

            // then
            val item = result.items.single()
            item.name shouldBe "맥주"
            item.qty shouldBe 2
            item.amount shouldBe 8_000
        }

        "소수점 단가에 수량을 곱한다" {
            // when - 실제 사례: "수원왕갈비 3.1x4" (3.1만원 x 4)
            val result = parse("청기와타운 수원왕갈비 3.1x4")

            // then
            result.place shouldBe "청기와타운"
            val item = result.items.single()
            item.unitPrice shouldBe 31_000
            item.qty shouldBe 4
            item.amount shouldBe 124_000
        }

        "총 N 은 품목이 아니라 합계다" {
            // given - 실제 임포트에서 "총"이 품목으로 잡혀 합계가 이중 계산됐다
            val text = "황태해장국 1.2 스팸과계란후라이 1.4 소주 0.4 총 3.0"

            // when
            val result = parse(text)

            // then - 품목 합(30,000)과 같지만 "총" 값을 그대로 쓴다
            result.totalAmount shouldBe 30_000
            result.items.map { it.name } shouldNotContain "총"
        }

        "총 값이 품목 합과 달라도 총을 신뢰한다" {
            // given - 품목을 다 적지 않은 경우 (실제 사례: 이가네양꼬치)
            val text = "등심꼬치 2.3 마늘 0.3 총 9.6"

            // when / then - 2.6만이 아니라 9.6만
            parse(text).totalAmount shouldBe 96_000
        }
    }

    "품목과 장소" - {
        "장소만 적어도 유효하다" {
            // when
            val result = parse("원조해장촌")

            // then
            result.place shouldBe "원조해장촌"
            result.items.shouldBeEmpty()
            result.totalAmount.shouldBeNull()
        }

        "금액 없는 품목도 버리지 않는다" {
            // when
            val result = parse("다이소 건전지")

            // then - 원문 보존 원칙상 인식 못한 토큰도 품목으로 남긴다
            result.place shouldBe "다이소"
            result.items.single().name shouldBe "건전지"
            result.items.single().amount.shouldBeNull()
        }

        "쉼표는 항목 구분자로 본다" {
            // when - 실제 사례: "콘칩 2.5, 테라 5.5x3"
            val result = parse("콘칩 2.5, 테라 5.5x3")

            // then
            result.items shouldHaveSize 2
            result.items[0].name shouldBe "콘칩"
            result.items[0].amount shouldBe 25_000
            result.items[1].name shouldBe "테라"
            result.items[1].amount shouldBe 165_000
        }

        "금액 뒤에 남은 토큰을 장소로 만들지 않는다" {
            // given - 사전에 없는 이름이 금액 뒤에 온 경우.
            // 실제 임포트에서 "제로슈가라거 4.9 나" 의 장소가 "나" 로 잡혔다.
            val result = parse("제로슈가라거 4.9 나")

            // then - 장소는 비어 있어야 한다
            result.place.shouldBeNull()
            result.items[0].name shouldBe "제로슈가라거"
        }

        "여러 토큰이 뭉친 서술은 품목이 아니라 메모로 보낸다" {
            // given - 실제 사례: 파싱할 수 없는 자유 서술
            val text = "티머니 한번에 적립된 듯한데 내역 확인이 안됨"

            // when
            val result = parse(text)

            // then
            result.place shouldBe "티머니"
            result.items.shouldBeEmpty()
            result.memo shouldBe "한번에 적립된 듯한데 내역 확인이 안됨"
        }

        "짧은 꼬리 토큰은 그대로 품목으로 남긴다" {
            // when - 메모로 보낼 만큼 길지 않다
            val result = parse("윤 노랑통닭 세가지맛")

            // then
            result.place shouldBe "윤"
            result.items.single().name shouldBe "노랑통닭 세가지맛"
            result.memo.shouldBeNull()
        }
    }

    "날짜와 시각" - {
        "날짜는 M-D 형태만 인식하고 없으면 오늘이다" {
            parse("5/31 택시 8100").occurredOn shouldBe LocalDate.of(2026, 5, 31)
            parse("택시 8100").occurredOn shouldBe today
        }

        "잘못된 날짜는 날짜로 보지 않는다" {
            // when - 13월은 존재하지 않는다
            val result = parse("13/45 택시 8100")

            // then
            result.occurredOn shouldBe today
        }

        "시각은 콜론이 있는 것만 인식한다" {
            // 콜론이 있으면 시각
            parse("21:37 택시 8100").occurredAt shouldBe LocalTime.of(21, 37)

            // 콜론이 없는 네 자리는 금액과 구분할 수 없으므로 시각이 아니다 (DESIGN.md 2.2)
            val result = parse("택시 2137")
            result.totalAmount shouldBe 2_137

            // 시각이 없으면 비워 둔다. 파서가 "지금"으로 채우면 임포트에서 실행 시각이 박힌다.
            result.occurredAt.shouldBeNull()
        }
    }

    "사람과 태그" - {
        "사람 사전에 있는 이름과 별칭을 인식한다" {
            // given
            val dictionary = PersonDictionary.of(
                listOf(
                    Person(name = "박정민", aliases = "정민"),
                    Person(name = "박채원"),
                ),
            )

            // when
            val result = parse("원조해장촌 2인세트 4.5 정민 박채원", dictionary)

            // then - 별칭으로 적어도 정식 이름으로 돌아온다
            result.personNames shouldBe listOf("박정민", "박채원")
        }

        "인원수와 태그를 인식한다" {
            // when
            val result = parse("택시 8100 3명 #출장 회사")

            // then
            result.headcount shouldBe 3
            result.tags shouldBe listOf("출장", "회사")
        }
    }

    "일괄 임포트의 줄 판별" - {
        "날짜나 시각 유무로 하위 항목을 가른다" {
            // 일괄 임포트에서 독립 기록과 하위 항목을 가르는 기준이다
            parser.hasDateOrTime("6/28 18:30 돈연 124000원 추정").shouldBeTrue()
            parser.hasDateOrTime("22:54 42900 대화료").shouldBeTrue()
            parser.hasDateOrTime("6/15 브롱스").shouldBeTrue()

            // Keep 에서 상하관계로 적힌 하위 항목에는 날짜도 시각도 없다
            parser.hasDateOrTime("맥주 × 2 8k").shouldBeFalse()
            parser.hasDateOrTime("골든 에일 4.9").shouldBeFalse()
            parser.hasDateOrTime("공기밥 1k").shouldBeFalse()
        }

        "시각만 있는 줄은 날짜가 없는 것으로 본다" {
            // 이 줄들은 위에서 날짜를 물려받아야 한다. 그러지 않으면 임포트한 당일로 저장된다.
            parser.hasDate("21:41 택시 6700").shouldBeFalse()
            parser.hasTime("21:41 택시 6700").shouldBeTrue()
        }

        "날짜만 적은 머리글을 알아본다" {
            // Keep 에서 날짜를 머리글로 적고 아래에 시각별 항목을 나열한 경우
            parser.isDateOnly("6/12").shouldBeTrue()
            parser.isDateOnly("6/12 (목)").shouldBeTrue()
            parser.isDateOnly("6/12 목").shouldBeTrue()
            parser.isDateOnly("- 6/12 -").shouldBeTrue()

            // 내용이 함께 있으면 머리글이 아니라 기록이다
            parser.isDateOnly("6/11 박채원결제").shouldBeFalse()
            parser.isDateOnly("6/15 브롱스").shouldBeFalse()
            parser.isDateOnly("21:41 택시 6700").shouldBeFalse()
            parser.isDateOnly("택시 6700").shouldBeFalse()
        }
    }

    "빈 입력에도 예외를 던지지 않는다" {
        // when
        val result = parse("   ")

        // then
        result.occurredOn shouldBe today
        result.place.shouldBeNull()
        result.items.shouldBeEmpty()
        result.totalAmount.shouldBeNull()
    }
})
