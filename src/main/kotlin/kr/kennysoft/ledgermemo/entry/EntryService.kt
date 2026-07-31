package kr.kennysoft.ledgermemo.entry

import kr.kennysoft.ledgermemo.parse.LineParser
import kr.kennysoft.ledgermemo.parse.ParsedLine
import kr.kennysoft.ledgermemo.parse.PersonDictionary
import kr.kennysoft.ledgermemo.person.EntryPerson
import kr.kennysoft.ledgermemo.person.EntryPersonRole
import kr.kennysoft.ledgermemo.person.Person
import kr.kennysoft.ledgermemo.person.PersonRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class EntryService(
    private val entryRepository: EntryRepository,
    private val personRepository: PersonRepository,
    private val tagRepository: TagRepository,
    private val parser: LineParser,
    private val clock: Clock,
) {

    fun parse(text: String?): ParsedLine =
        parser.parse(text.orEmpty(), dictionary(), LocalDate.now(clock), LocalTime.now(clock))

    fun search(
        status: EntryStatus?,
        from: LocalDate?,
        to: LocalDate?,
        q: String?,
        personId: Long?,
        pageable: Pageable,
    ): Page<Entry> = entryRepository.search(status, from, to, q?.takeIf { it.isNotBlank() }, personId, pageable)

    fun get(id: Long): Entry = entryRepository.findWithItemsById(id)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "entry $id 없음") }

    fun recent(): List<Entry> = entryRepository.findTop3ByOrderByCreatedAtDesc()

    /**
     * 원문을 파싱해 저장한다. 사용자가 칩에서 고친 값이 있으면 그쪽이 우선한다.
     *
     * 첨부만 있는 기록도 유효하므로(DESIGN.md 1.3) [EntryCreateRequest.attachmentOnly] 가
     * true 면 원문 없이 통과시킨다. 첨부는 생성 직후 별도 요청으로 붙는다.
     */
    @Transactional
    fun create(request: EntryCreateRequest): Entry {
        if (request.rawText.isNullOrBlank() && !request.attachmentOnly) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "원문과 첨부 중 하나는 있어야 한다")
        }

        val parsed = parse(request.rawText)
        val entry = Entry(
            occurredOn = request.occurredOn ?: parsed.occurredOn,
            occurredAt = request.occurredAt ?: parsed.occurredAt,
            rawText = request.rawText?.takeIf { it.isNotBlank() },
            place = request.place ?: parsed.place,
            totalAmount = request.totalAmount ?: parsed.totalAmount,
            categoryHint = request.categoryHint,
            paymentHint = request.paymentHint,
            headcount = request.headcount ?: parsed.headcount,
            uncertain = parsed.uncertain,
            memo = request.memo,
        )
        applyParsed(entry, parsed)
        return entryRepository.save(entry)
    }

    @Transactional
    fun patch(id: Long, request: EntryPatchRequest): Entry {
        val entry = get(id)
        request.occurredOn?.let { entry.occurredOn = it }
        request.occurredAt?.let { entry.occurredAt = it }
        request.totalAmount?.let { entry.totalAmount = it }
        request.headcount?.let { entry.headcount = it }
        request.uncertain?.let { entry.uncertain = it }
        // 문자열 필드는 빈 값을 "지움"으로 받는다. 필드를 아예 보내지 않은 것(null)과
        // 화면에서 비운 것("")을 구분해야 상세 화면에서 값을 지울 수 있다.
        request.place?.let { entry.place = it.ifBlank { null } }
        request.categoryHint?.let { entry.categoryHint = it.ifBlank { null } }
        request.paymentHint?.let { entry.paymentHint = it.ifBlank { null } }
        request.memo?.let { entry.memo = it.ifBlank { null } }
        request.items?.let { items ->
            entry.replaceItems(items.mapIndexed { index, it ->
                EntryItem(entry, index, it.name, it.qty, it.unitPrice, it.amount)
            })
            // 품목을 고쳤으면 합계도 다시 계산한다. 사용자가 총액을 함께 보냈으면 그쪽이 우선.
            if (request.totalAmount == null) {
                val amounts = items.mapNotNull { it.amount }
                entry.totalAmount = if (amounts.isEmpty()) null else amounts.sum()
            }
        }
        request.tags?.let { names ->
            val cleaned = names.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
            entry.tags.clear()
            entry.tags += findOrCreateTags(cleaned)
        }
        return entry
    }

    /** 자유 입력 필드의 자동완성 후보. 후보가 너무 많으면 고르기 어려워 상한을 둔다. */
    fun hints(): HintsResponse {
        val limit = PageRequest.of(0, HINT_LIMIT)
        return HintsResponse(
            categories = ArrayList(entryRepository.findCategoryHints(limit)),
            payments = ArrayList(entryRepository.findPaymentHints(limit)),
            tags = ArrayList(entryRepository.findTagNames(limit)),
        )
    }

    /**
     * 원문을 다시 파싱해 반영한다. 파서 규칙을 개선한 뒤 과거 기록에 소급 적용하는 용도다.
     *
     * 사용자가 손으로 고친 값까지 되돌리게 되므로, 화면에서 확인을 받은 뒤 호출해야 한다.
     */
    @Transactional
    fun reparse(id: Long): Entry {
        val entry = get(id)
        val rawText = entry.rawText
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "원문이 없어 재파싱할 수 없다")

        val parsed = parse(rawText)
        entry.occurredOn = parsed.occurredOn
        entry.occurredAt = parsed.occurredAt
        entry.place = parsed.place
        entry.totalAmount = parsed.totalAmount
        entry.headcount = parsed.headcount
        entry.uncertain = parsed.uncertain
        applyParsed(entry, parsed)
        return entry
    }

    @Transactional
    fun changeStatus(id: Long, status: EntryStatus): Entry {
        val entry = get(id)
        entry.status = status
        entry.doneAt = if (status == EntryStatus.DONE) Instant.now(clock) else null
        return entry
    }

    @Transactional
    fun delete(id: Long) {
        if (!entryRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "entry $id 없음")
        }
        entryRepository.deleteById(id)
    }

    /**
     * 여러 줄을 각각 하나의 기록으로 만든다. Keep 에 쌓인 미완료분 이관용이다.
     *
     * 한 줄이 실패해도 전체를 되돌리지 않는다. 수백 줄을 붙여넣었을 때 한 줄 때문에 전부
     * 날리는 편보다, 성공분을 남기고 실패한 원문을 돌려주어 손으로 처리하게 하는 편이 낫다.
     */
    @Transactional
    fun bulkImport(text: String): BulkImportResponse {
        val created = mutableListOf<Entry>()
        val failed = mutableListOf<FailedLine>()

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                runCatching { create(EntryCreateRequest(rawText = line)) }
                    .onSuccess { created += it }
                    .onFailure { failed += FailedLine(line, it.message ?: it::class.simpleName.orEmpty()) }
            }

        return BulkImportResponse(created.map { EntrySummaryResponse.from(it) }, failed)
    }

    /** 파싱 결과 중 자식 컬렉션(품목/사람/태그)을 반영한다. */
    private fun applyParsed(entry: Entry, parsed: ParsedLine) {
        entry.replaceItems(parsed.items.mapIndexed { index, item ->
            EntryItem(entry, index, item.name, item.qty, item.unitPrice, item.amount)
        })

        entry.persons.clear()
        parsed.personNames.forEach { name ->
            personRepository.findByName(name)?.let { person ->
                entry.persons += EntryPerson(entry, person, EntryPersonRole.ATTENDEE)
            }
        }

        entry.tags.clear()
        entry.tags += findOrCreateTags(parsed.tags)
    }

    /**
     * 태그 마스터를 재사용하고 없는 것만 만든다.
     *
     * cascade 로 맡기면 같은 이름 태그가 중복 저장되어 unique 제약에 걸린다.
     */
    private fun findOrCreateTags(names: List<String>): List<Tag> {
        if (names.isEmpty()) return emptyList()
        val existing = tagRepository.findByNameIn(names).associateBy { it.name }
        return names.map { name ->
            existing[name] ?: tagRepository.save(Tag(name))
        }
    }

    private fun dictionary(): PersonDictionary = PersonDictionary.of(personRepository.findByActiveTrue())

    /** 파서가 찾은 이름 중 사전에 없는 것은 만들지 않는다. 사람 등록은 명시적 행위로 둔다. */
    @Transactional
    fun createPerson(name: String, aliases: String?): Person =
        personRepository.findByName(name) ?: personRepository.save(Person(name, aliases))

    private companion object {
        const val HINT_LIMIT = 30
    }
}
