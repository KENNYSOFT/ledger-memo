package kr.kennysoft.ledgermemo.entry

import kr.kennysoft.ledgermemo.attachment.AttachmentRepository
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

/**
 * 기록 관련 유스케이스.
 *
 * 🚨 **바깥으로는 엔티티가 아니라 DTO 를 돌려준다.** `open-in-view=false` 이므로 트랜잭션이
 * 끝나면 세션이 닫히고, 컨트롤러에서 lazy 컬렉션을 건드리면
 * `LazyInitializationException` 이 된다. 변환은 반드시 트랜잭션 경계 안에서 끝내야 한다.
 * (실측: 목록 조회가 전부 500 이었고, `@Transactional` 이 붙은 테스트는 세션이 열린 채라
 * 이 결함을 잡지 못했다.)
 */
@Service
@Transactional(readOnly = true)
class EntryService(
    private val entryRepository: EntryRepository,
    private val personRepository: PersonRepository,
    private val tagRepository: TagRepository,
    private val attachmentRepository: AttachmentRepository,
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
    ): Page<EntrySummaryResponse> {
        val page = entryRepository.search(status, from, to, q?.takeIf { it.isNotBlank() }, personId, pageable)
        val counts = attachmentCounts(page.content)
        return page.map { EntrySummaryResponse.from(it, counts[it.id] ?: 0) }
    }

    fun getDetail(id: Long): EntryDetailResponse = EntryDetailResponse.from(get(id))

    fun recent(): List<EntrySummaryResponse> = toSummaries(entryRepository.findTop3ByOrderByCreatedAtDesc())

    /**
     * 엔티티를 그대로 쓰는 내부 조회. 호출자가 트랜잭션 안에 있어야 한다.
     *
     * 첨부 업로드처럼 엔티티가 필요한 경로에서만 쓴다.
     */
    fun get(id: Long): Entry = entryRepository.findWithItemsById(id)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "entry $id 없음") }

    private fun toSummaries(entries: List<Entry>): List<EntrySummaryResponse> {
        val counts = attachmentCounts(entries)
        return entries.map { EntrySummaryResponse.from(it, counts[it.id] ?: 0) }
    }

    private fun attachmentCounts(entries: List<Entry>): Map<Long, Int> {
        val ids = entries.mapNotNull { it.id }
        if (ids.isEmpty()) return emptyMap()
        return attachmentRepository.countByEntryIds(ids).associate { it.entryId to it.count.toInt() }
    }

    /**
     * 원문을 파싱해 저장한다. 사용자가 칩에서 고친 값이 있으면 그쪽이 우선한다.
     *
     * 첨부만 있는 기록도 유효하므로(DESIGN.md 1.3) [EntryCreateRequest.attachmentOnly] 가
     * true 면 원문 없이 통과시킨다. 첨부는 생성 직후 별도 요청으로 붙는다.
     */
    @Transactional
    fun create(request: EntryCreateRequest): EntryDetailResponse =
        EntryDetailResponse.from(createEntity(request))

    /**
     * 저장한 엔티티를 그대로 돌려준다. 일괄 임포트처럼 같은 트랜잭션에서 이어 쓰는 경로용이다.
     */
    private fun createEntity(request: EntryCreateRequest): Entry {
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
            // 사용자가 적어 보낸 메모가 있으면 그쪽이 우선. 없으면 파서가 걸러낸 서술을 넣는다.
            memo = request.memo ?: parsed.memo,
        )
        applyParsed(entry, parsed)
        return entryRepository.save(entry)
    }

    @Transactional
    fun patch(id: Long, request: EntryPatchRequest): EntryDetailResponse {
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
        return EntryDetailResponse.from(entry)
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
    fun reparse(id: Long): EntryDetailResponse {
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
        entry.memo = parsed.memo
        applyParsed(entry, parsed)
        return EntryDetailResponse.from(entry)
    }

    @Transactional
    fun changeStatus(id: Long, status: EntryStatus): EntryDetailResponse {
        val entry = get(id)
        entry.status = status
        entry.doneAt = if (status == EntryStatus.DONE) Instant.now(clock) else null
        return EntryDetailResponse.from(entry)
    }

    @Transactional
    fun delete(id: Long) {
        if (!entryRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "entry $id 없음")
        }
        entryRepository.deleteById(id)
    }

    /**
     * 여러 줄을 기록으로 만든다. Keep 에 쌓인 미완료분 이관용이다.
     *
     * Keep 은 2단으로 적혀 있어 줄의 성격을 세 가지로 나눠 처리한다.
     * - **날짜만 있는 줄**(`6/12`)은 기록이 아니라 머리글이다. 아래 줄들이 물려받을 날짜로만 쓴다.
     * - **시각만 있는 줄**(`21:41 택시 6700`)은 독립된 기록이지만 날짜를 위에서 물려받는다.
     *   물려받지 않으면 임포트한 당일로 저장되어 날짜가 통째로 틀어진다.
     * - **날짜도 시각도 없는 줄**은 직전 기록의 하위 항목으로 합친다. 한 자리의 주문이 줄
     *   단위로 쪼개지면 합계도 사람도 흩어진다.
     *
     * 한 줄이 실패해도 전체를 되돌리지 않는다. 수백 줄을 붙여넣었을 때 한 줄 때문에 전부
     * 날리는 편보다, 성공분을 남기고 실패한 원문을 돌려주어 손으로 처리하게 하는 편이 낫다.
     */
    @Transactional
    fun bulkImport(text: String): BulkImportResponse {
        val created = mutableListOf<Entry>()
        val failed = mutableListOf<FailedLine>()
        var current: Entry? = null
        var currentDate: LocalDate? = null

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (parser.isDateOnly(line)) {
                    currentDate = parse(line).occurredOn
                    // 머리글은 하위 항목을 받을 기록이 아니다.
                    current = null
                    return@forEach
                }

                val parent = current
                if (parent != null && !parser.hasDateOrTime(line)) {
                    runCatching { appendLine(parent, line) }
                        .onFailure { failed += FailedLine(line, it.message ?: it::class.simpleName.orEmpty()) }
                    return@forEach
                }

                // 날짜가 적혀 있지 않으면 머리글이나 직전 기록의 날짜를 쓴다.
                val inherited = if (parser.hasDate(line)) null else currentDate
                runCatching { createEntity(EntryCreateRequest(rawText = line, occurredOn = inherited)) }
                    .onSuccess {
                        created += it
                        current = it
                        currentDate = it.occurredOn
                    }
                    .onFailure { failed += FailedLine(line, it.message ?: it::class.simpleName.orEmpty()) }
            }

        // 방금 만든 기록이라 첨부는 아직 없다.
        return BulkImportResponse(created.map { EntrySummaryResponse.from(it, 0) }, failed)
    }

    /**
     * 하위 항목 줄을 기존 기록에 덧붙인다.
     *
     * 원문은 줄바꿈으로 이어 붙여 원래의 상하관계를 알아볼 수 있게 남기고, 품목/사람은
     * 기존 것에 더한다. 합계는 품목 합으로 다시 계산하되, 부모가 "총 N" 이나 금액을 명시해
     * 두었으면 그 값을 지키지 않고 실제 품목 합을 쓴다 (하위 항목이 더 정확하다).
     */
    private fun appendLine(entry: Entry, line: String) {
        val parsed = parse(line)

        entry.rawText = listOfNotNull(entry.rawText?.takeIf { it.isNotBlank() }, line).joinToString("\n")
        if (parsed.uncertain) entry.uncertain = true
        if (entry.place == null) entry.place = parsed.place
        if (entry.headcount == null) entry.headcount = parsed.headcount
        parsed.memo?.let { memo ->
            entry.memo = listOfNotNull(entry.memo?.takeIf { it.isNotBlank() }, memo).joinToString("\n")
        }

        var seq = entry.items.size
        parsed.items.forEach { item ->
            entry.items += EntryItem(entry, seq++, item.name, item.qty, item.unitPrice, item.amount)
        }

        parsed.personNames.forEach { name ->
            personRepository.findByName(name)?.let { person ->
                if (entry.persons.none { it.person.id == person.id }) {
                    entry.persons += EntryPerson(entry, person, EntryPersonRole.ATTENDEE)
                }
            }
        }

        entry.tags += findOrCreateTags(parsed.tags.filter { tag -> entry.tags.none { it.name == tag } })

        val amounts = entry.items.mapNotNull { it.amount }
        if (amounts.isNotEmpty()) entry.totalAmount = amounts.sum()
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
