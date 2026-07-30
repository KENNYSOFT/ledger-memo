# ledger-memo

가계부 분개 전 단계의 거래 초안을 폰에서 즉석 기록하는 개인용 웹서비스.

한 줄 입력 또는 영수증 사진 한 장으로 기록하고, 나중에 분개장으로 옮길 때 목록에서 참조한다.
Google Keep 자유 텍스트 메모를 대체하면서 데이터를 구조화하는 것이 목적이다.

설계 문서: [DESIGN.md](./DESIGN.md)

## 스택

| 구분 | 사용 |
|---|---|
| 백엔드 | Kotlin 2.3.21, Spring Boot 4.1.0, Spring Data JPA (Hibernate 7.4), Flyway 12.4 |
| DB | MySQL 8.4 LTS |
| 런타임 | GraalVM native image (aarch64) |
| 프론트 | 빌드 없는 단일 HTML + vanilla JS + PWA |
| 배포 | GitHub Actions (ARM64 runner) → GHCR → Oracle Cloud A1, Podman |
| 앞단 | Apache httpd 리버스 프록시 |

## 로컬 실행

JDK 25 필요. DB 접속 정보는 환경변수로 주입한다 (기본값이 없어 미주입 시 기동 실패).

```sh
export LEDGER_DB_URL='jdbc:mysql://127.0.0.1:3306/ledger_memo?connectionTimeZone=UTC'
export LEDGER_DB_USER='...'
export LEDGER_DB_PASSWORD='...'
./gradlew bootRun
```

컴파일만 검증 (DB 불필요):

```sh
./gradlew classes testClasses
```

## 배포

`main` push → Actions 가 ARM64 runner 에서 native 빌드 → GHCR 이미지 push.
서버에서는 재생성 스크립트로 교체한다 (DESIGN.md 7.5).

```sh
podman pull ghcr.io/KENNYSOFT/ledger-memo:latest
podman rm -f ledger-memo
podman run -d --name ledger-memo --network ledger --restart=always \
  -p 127.0.0.1:8080:8080 \
  -v /var/lib/ledger-memo/att:/data/att:Z \
  --env-file /etc/ledger-memo/env \
  ghcr.io/KENNYSOFT/ledger-memo:latest
```

> Podman 은 데몬이 없어 `--restart=always` 만으로는 재부팅 시 컨테이너가 시작되지 않는다.
> `systemctl enable --now podman-restart.service` 를 한 번 켜둘 것.
