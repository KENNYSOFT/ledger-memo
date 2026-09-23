package kr.kennysoft.ledgermemo.journal

import io.kotest.core.spec.style.FreeSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kr.kennysoft.ledgermemo.parse.LineParser
import kr.kennysoft.ledgermemo.parse.PersonDictionary
import java.time.LocalDate

/**
 * 분개 변환 검증.
 *
 * 본 가계부의 실제 분개 구조를 기준으로 한다 - 비용 행 N개(대변) + 결제 행 1개(차변),
 * 비용 행에는 결제수단/결제처가 없고 소득관련은 `해당 없음`.
 */
class JournalPreviewServiceTests : FreeSpec({

    val parser = LineParser()
    val service = JournalPreviewService()
    val today = LocalDate.of(2026, 8, 9)

    fun preview(text: String, category: String? = null, payment: String? = null) =
        service.preview(parser.parse(text, PersonDictionary.EMPTY, today), category, payment)

    "행 구성" - {
        "품목마다 비용 행이 생기고 결제 행 하나가 붙는다" {
            // when
            val result = preview("원조해장촌 2인세트 4.5 소주2 1.0 맥주3 1.5", category = "식비", payment = "KB국민카드")

            // then - 비용 3행 + 결제 1행
            result.rows shouldHaveSize 4

            val expenses = result.rows.dropLast(1)
            expenses.forAll { row ->
                row.item shouldBe "비용의 발생 | 대변"
                row.account shouldBe "비용: 식비"
                row.incomeRelated shouldBe "해당 없음"
                // 비용 행에는 결제수단/결제처를 적지 않는다
                row.paymentMethod shouldBe ""
                row.payee shouldBe ""
            }
            expenses.map { it.title } shouldBe listOf("2인세트", "소주", "맥주")
            expenses.map { it.amount } shouldBe listOf(45_000, 10_000, 15_000)

            val payment = result.rows.last()
            payment.item shouldBe "부채의 증가 | 차변"
            payment.account shouldBe "카드: KB국민카드"
            payment.incomeRelated shouldBe "신용카드"
            payment.title shouldBe "원조해장촌"
            payment.payee shouldBe "원조해장촌"
            payment.amount shouldBe 70_000
        }

        "차변과 대변이 균형을 이룬다" {
            // when
            val result = preview("원조해장촌 2인세트 4.5 소주2 1.0", payment = "현금")

            // then
            result.creditTotal shouldBe result.debitTotal
            result.debitTotal shouldBe 55_000
        }

        "금액을 모르는 품목은 분개하지 않는다" {
            // given - "건전지"는 금액이 없다
            val result = preview("다이소 건전지", category = "생활")

            // then - 분개할 금액이 없으므로 행이 만들어지지 않는다
            result.rows.shouldBeEmpty()
        }

        "품목을 못 잡아도 합계가 있으면 비용 한 줄로 만든다" {
            // given - 총액만 적힌 경우
            val result = preview("강남수향 295000", category = "식비", payment = "우리카드")

            // then
            result.rows shouldHaveSize 2
            result.rows.first().amount shouldBe 295_000
            result.rows.last().amount shouldBe 295_000
        }

        "수량은 제목이 아니라 메모로 보낸다" {
            // when
            val result = preview("원조해장촌 소주2 1.0", category = "식비")

            // then - 제목은 품목명만, 수량은 메모로
            val expense = result.rows.first()
            expense.title shouldBe "소주"
            expense.memo shouldBe "2개"
        }
    }

    "결제수단별 계정" - {
        "카드는 체크든 신용이든 부채가 늘고 소득관련만 다르다" {
            // 본 가계부 실제 분개 기준. "카드: 신한카드 하이패스(체크)" 도 부채의 증가다.
            // 체크카드의 즉시 정산(부채 감소 + 계좌 차감)은 결제수단만으로 만들 수 없어 제외한다.
            val check = preview("택시 8100", payment = "신한카드 하이패스(체크)")
            check.rows.last().item shouldBe "부채의 증가 | 차변"
            check.rows.last().account shouldBe "카드: 신한카드 하이패스(체크)"
            check.rows.last().incomeRelated shouldBe "직불카드 등"

            val credit = preview("택시 8100", payment = "우리카드")
            credit.rows.last().item shouldBe "부채의 증가 | 차변"
            credit.rows.last().incomeRelated shouldBe "신용카드"
        }

        "계좌와 현금을 구분한다" {
            val account = preview("기부 60000", payment = "우리은행")
            account.rows.last().account shouldBe "계좌: 우리은행"

            val cash = preview("택시 8100", payment = "현금")
            cash.rows.last().account shouldBe "자산: 현금"
            cash.rows.last().incomeRelated shouldBe "현금영수증"
        }
    }

    "주의 표시" - {
        "카테고리와 결제수단을 모르면 물음표를 남긴다" {
            // when
            val result = preview("택시 8100")

            // then - 지어내지 않는다. 그대로 옮기면 분개장이 틀어지므로 눈에 보이게 둔다
            result.rows.first().account shouldBe "비용: ?"
            result.rows.last().account shouldBe "자산: ?"
            result.needsAttention.shouldBeTrue()
        }

        "계정이 모두 확정되면 주의 표시가 없다" {
            val result = preview("택시 8100", category = "교통", payment = "현금")
            result.needsAttention.shouldBeFalse()
        }
    }

    "날짜와 시각을 시트가 읽는 형식으로 준다" {
        val result = preview("21:37 택시 8100")
        result.date shouldBe "2026-08-09"
        result.time shouldBe "21:37"

        // 시각을 모르면 비운다 (지어내지 않는다)
        preview("택시 8100").time shouldBe ""
    }
})
