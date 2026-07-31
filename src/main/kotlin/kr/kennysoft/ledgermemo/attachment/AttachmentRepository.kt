package kr.kennysoft.ledgermemo.attachment

import org.springframework.data.jpa.repository.JpaRepository

interface AttachmentRepository : JpaRepository<Attachment, Long>
