package kr.kennysoft.ledgermemo.person

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kr.kennysoft.ledgermemo.entry.EntryPersonResponse
import kr.kennysoft.ledgermemo.entry.EntryService
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class PersonController(
    private val personRepository: PersonRepository,
    private val entryPersonRepository: EntryPersonRepository,
    private val entryService: EntryService,
) {

    @GetMapping("/api/persons")
    fun list(): List<PersonResponse> =
        personRepository.findByActiveTrue().map { PersonResponse.from(it) }

    @PostMapping("/api/persons")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: PersonCreateRequest): PersonResponse =
        PersonResponse.from(entryService.createPerson(request.name, request.aliases))

    /** 사람별 미정산 합계. 본 가계부의 채권/채무 입력 시 참조한다. */
    @GetMapping("/api/settlements")
    fun settlements(): List<SettlementResponse> =
        entryPersonRepository.findUnsettledSummaries().map {
            SettlementResponse(
                personId = requireNotNull(it.person.id),
                personName = it.person.name,
                totalAmount = it.totalAmount,
                entryCount = it.entryCount,
            )
        }

    /** 거래에 사람을 붙인다. 파서가 못 잡은 동석자를 상세 화면에서 추가할 때 쓴다. */
    @PostMapping("/api/entries/{entryId}/persons")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun addPerson(
        @PathVariable entryId: Long,
        @Valid @RequestBody request: EntryPersonCreateRequest,
    ): EntryPersonResponse {
        val entry = entryService.get(entryId)
        val person = personRepository.findById(request.personId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "person ${request.personId} 없음") }

        if (entry.persons.any { it.person.id == person.id }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 추가된 사람이다")
        }

        val entryPerson = EntryPerson(entry, person, request.role, request.shareAmount)
        entry.persons += entryPerson
        entryPersonRepository.save(entryPerson)
        return EntryPersonResponse.from(entryPerson)
    }

    /** 역할/분담액/정산 여부를 부분 수정한다. */
    @PatchMapping("/api/entry-persons/{id}")
    @Transactional
    fun patch(@PathVariable id: Long, @RequestBody request: EntryPersonPatchRequest): EntryPersonResponse {
        val entryPerson = find(id)
        request.role?.let { entryPerson.role = it }
        request.shareAmount?.let { entryPerson.shareAmount = it }
        request.settled?.let { entryPerson.settled = it }
        return EntryPersonResponse.from(entryPerson)
    }

    /** 정산 완료 토글 (DESIGN.md 4). */
    @PutMapping("/api/entry-persons/{id}/settled")
    @Transactional
    fun markSettled(@PathVariable id: Long, @RequestBody request: SettledRequest): EntryPersonResponse {
        val entryPerson = find(id)
        entryPerson.settled = request.settled
        return EntryPersonResponse.from(entryPerson)
    }

    @DeleteMapping("/api/entry-persons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun delete(@PathVariable id: Long) {
        val entryPerson = find(id)
        // 컬렉션에서도 빼야 orphanRemoval 과 어긋나지 않는다.
        entryPerson.entry.persons.remove(entryPerson)
        entryPersonRepository.delete(entryPerson)
    }

    private fun find(id: Long): EntryPerson = entryPersonRepository.findById(id)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "entry-person $id 없음") }
}

data class PersonCreateRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Size(max = 500)
    val aliases: String? = null,
)

data class PersonResponse(val id: Long, val name: String, val aliases: String?) {
    companion object {
        fun from(person: Person) = PersonResponse(requireNotNull(person.id), person.name, person.aliases)
    }
}

data class EntryPersonCreateRequest(
    val personId: Long,
    val role: EntryPersonRole = EntryPersonRole.ATTENDEE,
    val shareAmount: Int? = null,
)

data class EntryPersonPatchRequest(
    val role: EntryPersonRole? = null,
    val shareAmount: Int? = null,
    val settled: Boolean? = null,
)

data class SettlementResponse(
    val personId: Long,
    val personName: String,
    val totalAmount: Long,
    val entryCount: Long,
)

data class SettledRequest(val settled: Boolean)
