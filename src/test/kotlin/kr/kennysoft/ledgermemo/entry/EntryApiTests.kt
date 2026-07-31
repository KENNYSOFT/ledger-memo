package kr.kennysoft.ledgermemo.entry

import kr.kennysoft.ledgermemo.TEST_ATTACHMENT_ROOT
import kr.kennysoft.ledgermemo.TEST_PASSWORD_HASH
import kr.kennysoft.ledgermemo.TEST_REMEMBER_ME_KEY
import kr.kennysoft.ledgermemo.TEST_USERNAME
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
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
 * 테스트가 만든 행이 남지 않도록 트랜잭션을 롤백한다.
 */
@SpringBootTest(properties = [TEST_USERNAME, TEST_PASSWORD_HASH, TEST_REMEMBER_ME_KEY, TEST_ATTACHMENT_ROOT])
@AutoConfigureMockMvc
@Transactional
class EntryApiTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `인증 없이 API 를 호출하면 401 이다`() {
        // API 요청은 로그인 페이지 HTML 대신 상태 코드로 답해야 클라이언트가 처리할 수 있다
        mockMvc.get("/api/entries").andExpect { status { isUnauthorized() } }
    }

    @Test
    @WithMockUser
    fun `한 줄 입력을 저장하면 파싱 결과가 함께 저장된다`() {
        // given
        val rawText = "원조해장촌 2인세트 4.5 소주2 1.0"

        // when
        val created = mockMvc.post("/api/entries") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rawText":"$rawText"}"""
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

    @Test
    @WithMockUser
    fun `원문도 첨부도 없으면 400 이다`() {
        mockMvc.post("/api/entries") {
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
            with(csrf())
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    @WithMockUser
    fun `상태를 완료로 바꾸고 되돌린다`() {
        // given
        val id = createEntry("택시 8100")

        // when
        mockMvc.put("/api/entries/$id/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"DONE"}"""
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DONE") }
            jsonPath("$.doneAt") { exists() }
        }

        // then — 되돌리면 완료 시각도 지워진다
        mockMvc.put("/api/entries/$id/status") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN"}"""
            with(csrf())
        }.andExpect {
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.doneAt") { doesNotExist() }
        }
    }

    @Test
    @WithMockUser
    fun `품목을 수정하면 합계를 다시 계산한다`() {
        // given
        val id = createEntry("택시 8100")

        // when — 총액을 함께 보내지 않았으므로 서버가 품목 합으로 다시 계산한다
        mockMvc.patch("/api/entries/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"name":"택시","amount":9000},{"name":"할증","amount":1000}]}"""
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalAmount") { value(10_000) }
            jsonPath("$.items.length()") { value(2) }
        }
    }

    @Test
    @WithMockUser
    fun `검색어로 목록을 좁힌다`() {
        // given
        createEntry("싸리골 해물파전2.3")
        createEntry("택시 8100")

        // when / then
        mockMvc.get("/api/entries") { param("q", "해물파전") }
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].place") { value("싸리골") }
            }
    }

    @Test
    @WithMockUser
    fun `삭제하면 조회되지 않는다`() {
        // given
        val id = createEntry("택시 8100")

        // when
        mockMvc.delete("/api/entries/$id") { with(csrf()) }.andExpect { status { isNoContent() } }

        // then
        mockMvc.get("/api/entries/$id").andExpect { status { isNotFound() } }
    }

    private fun createEntry(rawText: String): Long {
        val response = mockMvc.post("/api/entries") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rawText":"$rawText"}"""
            with(csrf())
        }.andReturn().response.contentAsString
        return Regex(""""id":(\d+)""").find(response)!!.groupValues[1].toLong()
    }
}
