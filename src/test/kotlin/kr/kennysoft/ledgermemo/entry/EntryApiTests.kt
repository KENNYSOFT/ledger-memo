package kr.kennysoft.ledgermemo.entry

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FreeSpec
import io.kotest.extensions.spring.SpringExtension
import kr.kennysoft.ledgermemo.TEST_ATTACHMENT_ROOT
import kr.kennysoft.ledgermemo.TEST_PASSWORD_HASH
import kr.kennysoft.ledgermemo.TEST_REMEMBER_ME_KEY
import kr.kennysoft.ledgermemo.TEST_USERNAME
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

/**
 * entries API 왕복 검증. MySQL 이 필요하므로 CI 에서 돌아간다.
 *
 * 테스트가 만든 행이 남지 않도록 트랜잭션을 롤백한다. [SpringExtension] 이 테스트마다 Spring 의
 * beforeTestMethod/afterTestMethod 를 불러 주므로 클래스에 단 `@Transactional` 이 그대로 걸린다.
 * 그 탐색이 이 클래스를 상속한 가짜 메서드로 이뤄지므로 클래스가 open 이어야 하는데,
 * `@SpringBootTest` 가 붙어 있어 kotlin-spring 플러그인이 열어 준다.
 *
 * 로그인은 요청마다 `user()` 로 붙인다. kotest 의 테스트는 메서드가 아니라서 메서드에 다는
 * `@WithMockUser` 가 걸리지 않는다.
 */
@SpringBootTest(properties = [TEST_USERNAME, TEST_PASSWORD_HASH, TEST_REMEMBER_ME_KEY, TEST_ATTACHMENT_ROOT])
@AutoConfigureMockMvc
@Transactional
@ApplyExtension(SpringExtension::class)
class EntryApiTests(mockMvc: MockMvc) : FreeSpec({

    val loggedIn = user("tester")

    fun createEntry(rawText: String): Long {
        val response = mockMvc.post("/api/entries") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rawText":"$rawText"}"""
            with(loggedIn)
            with(csrf())
        }.andReturn().response.contentAsString
        return Regex(""""id":(\d+)""").find(response)!!.groupValues[1].toLong()
    }

    "인증 없이 API 를 호출하면 401 이다" {
        // API 요청은 로그인 페이지 HTML 대신 상태 코드로 답해야 클라이언트가 처리할 수 있다
        mockMvc.get("/api/entries").andExpect { status { isUnauthorized() } }
    }

    "로그인한 상태에서" - {
        "한 줄 입력을 저장하면 파싱 결과가 함께 저장된다" {
            // given
            val rawText = "원조해장촌 2인세트 4.5 소주2 1.0"

            // when
            val created = mockMvc.post("/api/entries") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rawText":"$rawText"}"""
                with(loggedIn)
                with(csrf())
            }

            // then
            created.andExpect {
                status { isCreated() }
                jsonPath("$.place") { value("원조해장촌") }
                jsonPath("$.totalAmount") { value(55_000) }
                jsonPath("$.rawText") { value(rawText) }
                jsonPath("$.items.length()") { value(2) }
                jsonPath("$.items[1].name") { value("소주") }
                jsonPath("$.items[1].qty") { value(2) }
                jsonPath("$.status") { value("OPEN") }
            }
        }

        "원문도 첨부도 없으면 400 이다" {
            mockMvc.post("/api/entries") {
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
                with(loggedIn)
                with(csrf())
            }.andExpect { status { isBadRequest() } }
        }

        "상태를 완료로 바꾸고 되돌린다" {
            // given
            val id = createEntry("택시 8100")

            // when
            mockMvc.put("/api/entries/$id/status") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"DONE"}"""
                with(loggedIn)
                with(csrf())
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DONE") }
                jsonPath("$.doneAt") { exists() }
            }

            // then - 되돌리면 완료 시각도 지워진다
            mockMvc.put("/api/entries/$id/status") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"OPEN"}"""
                with(loggedIn)
                with(csrf())
            }.andExpect {
                jsonPath("$.status") { value("OPEN") }
                jsonPath("$.doneAt") { doesNotExist() }
            }
        }

        "품목을 수정하면 합계를 다시 계산한다" {
            // given
            val id = createEntry("택시 8100")

            // when - 총액을 함께 보내지 않았으므로 서버가 품목 합으로 다시 계산한다
            mockMvc.patch("/api/entries/$id") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"items":[{"name":"택시","amount":9000},{"name":"할증","amount":1000}]}"""
                with(loggedIn)
                with(csrf())
            }.andExpect {
                status { isOk() }
                jsonPath("$.totalAmount") { value(10_000) }
                jsonPath("$.items.length()") { value(2) }
            }
        }

        "검색어로 목록을 좁힌다" {
            // given
            createEntry("싸리골 해물파전2.3")
            createEntry("택시 8100")

            // when / then
            mockMvc.get("/api/entries") {
                param("q", "해물파전")
                with(loggedIn)
            }.andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].place") { value("싸리골") }
            }
        }

        "삭제하면 조회되지 않는다" {
            // given
            val id = createEntry("택시 8100")

            // when
            mockMvc.delete("/api/entries/$id") {
                with(loggedIn)
                with(csrf())
            }.andExpect { status { isNoContent() } }

            // then
            mockMvc.get("/api/entries/$id") { with(loggedIn) }.andExpect { status { isNotFound() } }
        }
    }
})
