-- remember-me 영속 토큰 (DESIGN.md 6).
-- 컬럼 구조는 Spring Security 의 JdbcTokenRepositoryImpl 이 기대하는 형태로 고정되어 있어
-- 이름을 바꿀 수 없다.

CREATE TABLE persistent_logins
(
    username  VARCHAR(64) NOT NULL,
    series    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_used TIMESTAMP   NOT NULL,
    PRIMARY KEY (series)
) ENGINE = InnoDB;
