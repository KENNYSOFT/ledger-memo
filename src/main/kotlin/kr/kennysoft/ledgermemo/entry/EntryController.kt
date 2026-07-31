package kr.kennysoft.ledgermemo.entry

import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class EntryController(
    private val entryService: EntryService,
) {

    /** 타이핑 중 미리보기. 저장하지 않는다. */
    @PostMapping("/api/parse")
    fun parse(@Valid @RequestBody request: ParseRequest): ParseResponse =
        ParseResponse.from(entryService.parse(request.text))

    @PostMapping("/api/entries")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: EntryCreateRequest): EntryDetailResponse =
        EntryDetailResponse.from(entryService.create(request))

    /** 여러 줄을 한 번에 기록으로 만든다 (Keep 미완료분 이관). */
    @PostMapping("/api/entries/bulk")
    fun bulkImport(@Valid @RequestBody request: BulkImportRequest): BulkImportResponse =
        entryService.bulkImport(request.text)

    @GetMapping("/api/entries")
    fun list(
        @RequestParam(required = false) status: EntryStatus?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) personId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): Map<String, Any> {
        val result = entryService.search(status, from, to, q, personId, PageRequest.of(page, size.coerceIn(1, 200)))
        return mapOf(
            "content" to result.content.map { EntrySummaryResponse.from(it) },
            "page" to result.number,
            "totalPages" to result.totalPages,
            "totalElements" to result.totalElements,
        )
    }

    /** 작성 화면 하단의 "최근 저장" 3건. */
    @GetMapping("/api/entries/recent")
    fun recent(): List<EntrySummaryResponse> = entryService.recent().map { EntrySummaryResponse.from(it) }

    /** 카테고리/결제수단/태그 자동완성 후보. */
    @GetMapping("/api/hints")
    fun hints(): HintsResponse = entryService.hints()

    @GetMapping("/api/entries/{id}")
    fun get(@PathVariable id: Long): EntryDetailResponse = EntryDetailResponse.from(entryService.get(id))

    @PatchMapping("/api/entries/{id}")
    fun patch(@PathVariable id: Long, @Valid @RequestBody request: EntryPatchRequest): EntryDetailResponse =
        EntryDetailResponse.from(entryService.patch(id, request))

    @PostMapping("/api/entries/{id}/reparse")
    fun reparse(@PathVariable id: Long): EntryDetailResponse =
        EntryDetailResponse.from(entryService.reparse(id))

    @PutMapping("/api/entries/{id}/status")
    fun changeStatus(@PathVariable id: Long, @RequestBody request: StatusRequest): EntryDetailResponse =
        EntryDetailResponse.from(entryService.changeStatus(id, request.status))

    @DeleteMapping("/api/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = entryService.delete(id)
}
