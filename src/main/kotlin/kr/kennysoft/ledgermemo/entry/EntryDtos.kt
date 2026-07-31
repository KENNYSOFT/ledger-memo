package kr.kennysoft.ledgermemo.entry

import jakarta.validation.constraints.Size
import kr.kennysoft.ledgermemo.parse.ParsedLine
import kr.kennysoft.ledgermemo.person.EntryPerson
import kr.kennysoft.ledgermemo.person.EntryPersonRole
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** 한 줄 파싱 미리보기 요청. */
data class ParseRequest(
    @field:Size(max = 2000)
    val text: String?,
)

/** 파싱 결과 미리보기. 저장하지 않고 칩으로만 보여준다. */
data class ParseResponse(
    val occurredOn: LocalDate,
    val occurredAt: LocalTime?,
    val place: String?,
    val totalAmount: Int?,
    val headcount: Int?,
    val uncertain: Boolean,
    val memo: String?,
    val items: List<ItemResponse>,
    val personNames: List<String>,
    val tags: List<String>,
) {
    companion object {
        fun from(parsed: ParsedLine) = ParseResponse(
            occurredOn = parsed.occurredOn,
            occurredAt = parsed.occurredAt,
            place = parsed.place,
            totalAmount = parsed.totalAmount,
            headcount = parsed.headcount,
            uncertain = parsed.uncertain,
            memo = parsed.memo,
            items = parsed.items.map { ItemResponse(null, it.name, it.qty, it.unitPrice, it.amount) },
            personNames = parsed.personNames,
            tags = parsed.tags,
        )
    }
}

/**
 * 생성 요청. 원문만 보내면 서버가 파싱해 채운다.
 *
 * 사진만으로도 유효한 기록이므로 [rawText] 는 비어 있을 수 있다. 대신 첨부가 최소 하나
 * 있어야 하며, 그 검증은 서비스가 한다 (DESIGN.md 1.3).
 */
data class EntryCreateRequest(
    @field:Size(max = 2000)
    val rawText: String? = null,

    /** 파싱 결과를 사용자가 칩에서 고쳤다면 그 값이 우선한다. */
    val occurredOn: LocalDate? = null,
    val occurredAt: LocalTime? = null,
    @field:Size(max = 200)
    val place: String? = null,
    val totalAmount: Int? = null,
    @field:Size(max = 100)
    val categoryHint: String? = null,
    @field:Size(max = 100)
    val paymentHint: String? = null,
    val headcount: Int? = null,
    val memo: String? = null,

    /** 첨부만으로 만드는 기록인지. true 면 rawText 가 없어도 통과시킨다. */
    val attachmentOnly: Boolean = false,
)

/** 부분 수정. null 인 필드는 건드리지 않는다. */
data class EntryPatchRequest(
    val occurredOn: LocalDate? = null,
    val occurredAt: LocalTime? = null,
    @field:Size(max = 200)
    val place: String? = null,
    val totalAmount: Int? = null,
    @field:Size(max = 100)
    val categoryHint: String? = null,
    @field:Size(max = 100)
    val paymentHint: String? = null,
    val headcount: Int? = null,
    val memo: String? = null,
    val uncertain: Boolean? = null,
    /** 지정하면 품목을 통째로 교체한다. */
    val items: List<ItemRequest>? = null,
    /** 지정하면 태그를 통째로 교체한다. 빈 배열이면 모두 뗀다. */
    val tags: List<String>? = null,
)

/** 자유 입력 필드의 자동완성 후보. 지금까지의 입력이 그대로 사전이 된다. */
data class HintsResponse(
    val categories: List<String>,
    val payments: List<String>,
    val tags: List<String>,
)

data class ItemRequest(
    @field:Size(max = 200)
    val name: String,
    val qty: Int? = null,
    val unitPrice: Int? = null,
    val amount: Int? = null,
)

data class StatusRequest(val status: EntryStatus)

data class ItemResponse(
    val id: Long?,
    val name: String,
    val qty: Int?,
    val unitPrice: Int?,
    val amount: Int?,
)

data class AttachmentResponse(
    val id: Long,
    val contentType: String,
    val bytes: Int,
    val hasThumb: Boolean,
)

/** 상세 화면에서 정산을 다루려면 이름만으로는 부족해 식별자와 금액을 함께 준다. */
data class EntryPersonResponse(
    val id: Long,
    val personId: Long,
    val name: String,
    val role: EntryPersonRole,
    val shareAmount: Int?,
    val settled: Boolean,
) {
    companion object {
        fun from(entryPerson: EntryPerson) = EntryPersonResponse(
            id = requireNotNull(entryPerson.id),
            personId = requireNotNull(entryPerson.person.id),
            name = entryPerson.person.name,
            role = entryPerson.role,
            shareAmount = entryPerson.shareAmount,
            settled = entryPerson.settled,
        )
    }
}

/**
 * 여러 줄을 한 번에 밀어 넣는다. Keep 에 쌓인 미완료분을 옮길 때 쓴다 (DESIGN.md 8-3단계).
 */
data class BulkImportRequest(
    @field:Size(max = 100_000)
    val text: String,
)

data class BulkImportResponse(
    val created: List<EntrySummaryResponse>,
    /** 파싱은 했지만 저장하지 못한 줄. 원문을 돌려주어 사용자가 손으로 처리할 수 있게 한다. */
    val failed: List<FailedLine>,
)

data class FailedLine(val text: String, val reason: String)

/**
 * 목록용 요약. 품목/첨부 전체를 싣지 않는다.
 *
 * 첨부 개수는 엔티티의 lazy 컬렉션을 건드리지 않고 [attachmentCount] 로 받는다. 목록은
 * 페이지 단위로 카운트를 한 번에 조회하므로 행마다 쿼리가 나가지 않는다.
 */
data class EntrySummaryResponse(
    val id: Long,
    val occurredOn: LocalDate,
    val occurredAt: LocalTime?,
    val place: String?,
    val totalAmount: Int?,
    val uncertain: Boolean,
    val status: EntryStatus,
    val rawText: String?,
    val attachmentCount: Int,
) {
    companion object {
        fun from(entry: Entry, attachmentCount: Int) = EntrySummaryResponse(
            id = requireNotNull(entry.id),
            occurredOn = entry.occurredOn,
            occurredAt = entry.occurredAt,
            place = entry.place,
            totalAmount = entry.totalAmount,
            uncertain = entry.uncertain,
            status = entry.status,
            rawText = entry.rawText,
            attachmentCount = attachmentCount,
        )
    }
}

/** 상세. 자식을 모두 포함한다. */
data class EntryDetailResponse(
    val id: Long,
    val occurredOn: LocalDate,
    val occurredAt: LocalTime?,
    val rawText: String?,
    val place: String?,
    val totalAmount: Int?,
    val categoryHint: String?,
    val paymentHint: String?,
    val headcount: Int?,
    val uncertain: Boolean,
    val memo: String?,
    val status: EntryStatus,
    val doneAt: Instant?,
    val createdAt: Instant,
    val items: List<ItemResponse>,
    val attachments: List<AttachmentResponse>,
    val persons: List<EntryPersonResponse>,
    val tags: List<String>,
) {
    companion object {
        fun from(entry: Entry) = EntryDetailResponse(
            id = requireNotNull(entry.id),
            occurredOn = entry.occurredOn,
            occurredAt = entry.occurredAt,
            rawText = entry.rawText,
            place = entry.place,
            totalAmount = entry.totalAmount,
            categoryHint = entry.categoryHint,
            paymentHint = entry.paymentHint,
            headcount = entry.headcount,
            uncertain = entry.uncertain,
            memo = entry.memo,
            status = entry.status,
            doneAt = entry.doneAt,
            createdAt = entry.createdAt,
            items = entry.items.map { ItemResponse(it.id, it.name, it.qty, it.unitPrice, it.amount) },
            attachments = entry.attachments.map {
                AttachmentResponse(requireNotNull(it.id), it.contentType, it.bytes, it.thumbPath != null)
            },
            persons = entry.persons.map { EntryPersonResponse.from(it) },
            // sorted() 는 결과가 비면 kotlin.collections.EmptyList 를 돌려주고, 그것이
            // native image 에서 Jackson 직렬화를 깨뜨린다 (KotlinCollectionsRuntimeHints 참고).
            // 힌트로도 막지만 애초에 만들지 않는 편이 안전하다.
            tags = ArrayList(entry.tags.map { it.name }.sorted()),
        )
    }
}
