package kr.kennysoft.ledgermemo.attachment

import kr.kennysoft.ledgermemo.entry.AttachmentResponse
import kr.kennysoft.ledgermemo.entry.EntryService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

@RestController
class AttachmentController(
    private val entryService: EntryService,
    private val attachmentRepository: AttachmentRepository,
    private val storage: AttachmentStorage,
) {

    /**
     * 사진 업로드. 원본과 함께 클라이언트가 만든 썸네일을 받는다.
     *
     * 서버는 리사이즈를 하지 않으므로 썸네일이 없으면 목록에서도 원본을 쓰게 된다.
     * 클라이언트가 Canvas 로 만들어 함께 보내는 것을 전제한다 (DESIGN.md 1.4).
     */
    @PostMapping("/api/entries/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun upload(
        @PathVariable id: Long,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("thumb", required = false) thumb: MultipartFile?,
        @RequestParam("shotAt", required = false) shotAt: Instant?,
    ): AttachmentResponse {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "빈 파일")
        }
        val contentType = file.contentType
        if (contentType == null || !contentType.startsWith("image/")) {
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "이미지만 업로드할 수 있다")
        }

        val entry = entryService.get(id)
        val filePath = storage.store(file, entry.occurredOn)
        val thumbPath = thumb?.takeIf { !it.isEmpty }?.let { storage.store(it, entry.occurredOn, suffix = "_thumb") }

        val attachment = Attachment(
            entry = entry,
            filePath = filePath,
            thumbPath = thumbPath,
            contentType = contentType,
            bytes = file.size.toInt(),
            shotAt = shotAt,
        )
        entry.attachments += attachment
        attachmentRepository.save(attachment)

        return AttachmentResponse(requireNotNull(attachment.id), attachment.contentType, attachment.bytes, thumbPath != null)
    }

    @GetMapping("/api/attachments/{id}")
    fun download(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") thumb: Boolean,
    ): ResponseEntity<Resource> {
        val attachment = attachmentRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "attachment $id 없음") }

        // 썸네일을 요청했지만 없으면 원본으로 대체한다.
        val relative = attachment.thumbPath?.takeIf { thumb } ?: attachment.filePath
        if (!storage.exists(relative)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "파일이 없다: $relative")
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(attachment.contentType))
            // 내용이 바뀌지 않는 파일이라 오래 캐시해도 안전하다 (경로에 UUID 가 있다).
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
            .body(FileSystemResource(storage.read(relative)))
    }

    @DeleteMapping("/api/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun delete(@PathVariable id: Long) {
        val attachment = attachmentRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "attachment $id 없음") }
        // 파일부터 지우면 DB 삭제가 실패했을 때 깨진 참조가 남는다. 메타데이터를 먼저 지운다.
        attachmentRepository.delete(attachment)
        storage.delete(attachment.filePath)
        attachment.thumbPath?.let { storage.delete(it) }
    }
}
