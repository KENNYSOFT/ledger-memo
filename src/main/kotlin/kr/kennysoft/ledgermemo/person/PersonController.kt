package kr.kennysoft.ledgermemo.person

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kr.kennysoft.ledgermemo.entry.EntryService
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
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

    @PutMapping("/api/entry-persons/{id}/settled")
    @Transactional
    fun markSettled(@PathVariable id: Long, @RequestBody request: SettledRequest): SettledResponse {
        val entryPerson = entryPersonRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "entry-person $id 없음") }
        entryPerson.settled = request.settled
        return SettledResponse(id, entryPerson.settled)
    }
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

data class SettlementResponse(
    val personId: Long,
    val personName: String,
    val totalAmount: Long,
    val entryCount: Long,
)

data class SettledRequest(val settled: Boolean)

data class SettledResponse(val id: Long, val settled: Boolean)
