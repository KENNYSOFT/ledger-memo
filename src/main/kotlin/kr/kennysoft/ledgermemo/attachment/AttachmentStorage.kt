package kr.kennysoft.ledgermemo.attachment

import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * 첨부 파일의 물리 저장. 경로 규칙은 `{root}/{yyyy}/{MM}/{uuid}.{ext}` 다 (DESIGN.md 3.2).
 *
 * 서버는 이미지를 디코딩하지 않는다. 받은 바이트를 그대로 쓰기만 하므로 native image 에서
 * ImageIO 의존이 생기지 않는다 (DESIGN.md 1.4).
 */
@Component
class AttachmentStorage(
    private val properties: AttachmentProperties,
) {

    /** 저장 후 root 기준 상대 경로를 돌려준다. */
    fun store(file: MultipartFile, date: LocalDate, suffix: String = ""): String {
        val extension = EXTENSIONS[file.contentType] ?: DEFAULT_EXTENSION
        val relative = "%04d/%02d/%s%s.%s".format(date.year, date.monthValue, UUID.randomUUID(), suffix, extension)
        val target = resolve(relative)
        Files.createDirectories(target.parent)
        file.inputStream.use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
        return relative
    }

    fun read(relative: String): Path = resolve(relative)

    fun exists(relative: String): Boolean = Files.isRegularFile(resolve(relative))

    fun delete(relative: String) {
        Files.deleteIfExists(resolve(relative))
    }

    /**
     * root 밖으로 나가는 경로를 차단한다.
     *
     * 저장 경로는 UUID 로 만들어지므로 정상 흐름에서는 문제가 없지만, DB 값을 그대로
     * 파일 경로로 쓰는 자리라 방어를 남긴다.
     */
    private fun resolve(relative: String): Path {
        val base = properties.root.toAbsolutePath().normalize()
        val resolved = base.resolve(relative).normalize()
        require(resolved.startsWith(base)) { "저장 루트를 벗어난 경로: $relative" }
        return resolved
    }

    private companion object {
        val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
        )
        const val DEFAULT_EXTENSION = "bin"
    }
}
