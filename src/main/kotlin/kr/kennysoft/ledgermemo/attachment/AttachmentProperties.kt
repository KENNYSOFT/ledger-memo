package kr.kennysoft.ledgermemo.attachment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * 첨부 저장 설정.
 *
 * [root] 는 컨테이너에 마운트되는 호스트 디렉토리다. 파일은 여기에, 메타데이터만 DB 에
 * 둔다 (DESIGN.md 3.2).
 */
@ConfigurationProperties(prefix = "ledger.attachment")
data class AttachmentProperties(
    val root: Path,
)
