-- 스키마 정의. DESIGN.md 3.1 참고.
-- 데이터베이스(ledger_memo)는 미리 생성되어 있고 JDBC URL 로 선택되므로
-- 마이그레이션에서는 스키마 prefix 를 쓰지 않는다 (환경별 DB 명 차이를 허용).

CREATE TABLE entry
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_on   DATE         NOT NULL,
    occurred_at   TIME         NULL,
    raw_text      TEXT         NULL,
    place         VARCHAR(200) NULL,
    total_amount  INT          NULL,
    category_hint VARCHAR(100) NULL,
    payment_hint  VARCHAR(100) NULL,
    headcount     INT          NULL,
    uncertain     BOOLEAN      NOT NULL DEFAULT FALSE,
    memo          TEXT         NULL,
    status        VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
    done_at       DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status_occurred_on_occurred_at (status, occurred_on, occurred_at),
    KEY idx_occurred_on_occurred_at (occurred_on, occurred_at)
) ENGINE = InnoDB;

CREATE TABLE entry_item
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    entry_id   BIGINT       NOT NULL,
    seq        INT          NOT NULL,
    name       VARCHAR(200) NOT NULL,
    qty        INT          NULL,
    unit_price INT          NULL,
    amount     INT          NULL,
    PRIMARY KEY (id),
    KEY idx_entry_id_seq (entry_id, seq),
    CONSTRAINT fk_entry_item_entry FOREIGN KEY (entry_id) REFERENCES entry (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE person
(
    id      BIGINT       NOT NULL AUTO_INCREMENT,
    name    VARCHAR(100) NOT NULL,
    aliases VARCHAR(500) NULL,
    active  BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB;

CREATE TABLE entry_person
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    entry_id     BIGINT      NOT NULL,
    person_id    BIGINT      NOT NULL,
    role         VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    share_amount INT         NULL,
    settled      BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_entry_id (entry_id),
    KEY idx_person_id_settled (person_id, settled),
    CONSTRAINT fk_entry_person_entry FOREIGN KEY (entry_id) REFERENCES entry (id) ON DELETE CASCADE,
    CONSTRAINT fk_entry_person_person FOREIGN KEY (person_id) REFERENCES person (id)
) ENGINE = InnoDB;

CREATE TABLE tag
(
    id   BIGINT       NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB;

CREATE TABLE entry_tag
(
    entry_id BIGINT NOT NULL,
    tag_id   BIGINT NOT NULL,
    PRIMARY KEY (entry_id, tag_id),
    KEY idx_tag_id (tag_id),
    CONSTRAINT fk_entry_tag_entry FOREIGN KEY (entry_id) REFERENCES entry (id) ON DELETE CASCADE,
    CONSTRAINT fk_entry_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id)
) ENGINE = InnoDB;

CREATE TABLE attachment
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    entry_id     BIGINT       NOT NULL,
    file_path    VARCHAR(300) NOT NULL,
    thumb_path   VARCHAR(300) NULL,
    content_type VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bytes        INT          NOT NULL,
    shot_at      DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_entry_id (entry_id),
    CONSTRAINT fk_attachment_entry FOREIGN KEY (entry_id) REFERENCES entry (id) ON DELETE CASCADE
) ENGINE = InnoDB;
