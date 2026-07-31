package kr.kennysoft.ledgermemo.entry

import jakarta.validation.constraints.Size
import kr.kennysoft.ledgermemo.parse.ParsedLine
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

/** 목록용 요약. 품목/첨부 전체를 싣지 않는다. */
data class EntrySummaryResponse(
    val id: Long,
    val occurredOn: LocalDate,
    val occurredAt: LocalTime?,
    val place: String?,
    val totalAmount: Int?,
    val uncertain: Boolean,
    val status: EntryStatus,
    val rawText: String?,
    val itemCount: Int,
    val attachmentCount: Int,
) {
    companion object {
        fun from(entry: Entry) = EntrySummaryResponse(
            id = requireNotNull(entry.id),
            occurredOn = entry.occurredOn,
            occurredAt = entry.occurredAt,
            place = entry.place,
            totalAmount = entry.totalAmount,
            uncertain = entry.uncertain,
            status = entry.status,
            rawText = entry.rawText,
            itemCount = entry.items.size,
            attachmentCount = entry.attachments.size,
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
    val personNames: List<String>,
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
            personNames = entry.persons.map { it.person.name },
            tags = entry.tags.map { it.name }.sorted(),
        )
    }
}
