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

빌드 스캔은 CI 에서만 게시된다. 로컬에서 필요하면 `CI=true ./gradlew build`.

---

# 최초 셋업 (서버, 1회)

Podman 컨테이너 2개(MySQL + 앱)를 각각 실행한다. Compose 도 Quadlet 도 쓰지 않는다.

## 1. 네트워크

앱이 컨테이너 이름으로 DB 에 접근하므로 사용자 정의 네트워크가 필요하다 (Podman 기본
네트워크에서는 이름 기반 DNS 가 동작하지 않는다).

```sh
podman network create ledger
```

## 2. 시크릿 파일

비밀번호를 `podman run -e` 로 넘기면 shell history 와 `ps` 에 남으므로 env 파일로만 다룬다.

```sh
sudo mkdir -p /etc/ledger-memo && sudo chmod 700 /etc/ledger-memo

sudo tee /etc/ledger-memo/mysql.env >/dev/null <<'EOF'
MYSQL_ROOT_PASSWORD=
MYSQL_DATABASE=ledger_memo
MYSQL_USER=ledger_memo
MYSQL_PASSWORD=
EOF
sudo chmod 600 /etc/ledger-memo/mysql.env

sudo tee /etc/ledger-memo/env >/dev/null <<'EOF'
LEDGER_DB_URL=jdbc:mysql://ledger-mysql:3306/ledger_memo?connectionTimeZone=UTC
LEDGER_DB_USER=ledger_memo
LEDGER_DB_PASSWORD=
EOF
sudo chmod 600 /etc/ledger-memo/env
```

그 다음 편집기로 암호를 채운다. **`mysql.env` 의 `MYSQL_PASSWORD` 와 `env` 의
`LEDGER_DB_PASSWORD` 는 같은 값이어야 한다.**

```sh
sudo vi /etc/ledger-memo/mysql.env
sudo vi /etc/ledger-memo/env
```

## 3. MySQL 컨테이너

`MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` 를 주면 **첫 기동 때 DB 와 앱 계정이
자동 생성**된다. `CREATE DATABASE` / `CREATE USER` 를 따로 실행할 필요가 없다. 테이블은
앱이 처음 뜰 때 Flyway 가 만든다.

```sh
podman run -d --name ledger-mysql --network ledger --restart=always \
  -v ledger-mysql-data:/var/lib/mysql \
  --env-file /etc/ledger-memo/mysql.env \
  docker.io/library/mysql:8.4 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci
```

- **호스트 포트를 publish 하지 않는다.** 앱만 컨테이너 네트워크로 접근하면 되므로 `-p` 를
  주지 않아 외부 노출면을 만들지 않는다.
- 이미지 뒤의 인자는 `mysqld` 에 그대로 전달된다. 8.4 기본값도 utf8mb4 이지만, 한글 저장과
  정렬이 서버 설정에 좌우되므로 명시한다.
- 데이터는 named volume `ledger-mysql-data` 에 남아 컨테이너를 지워도 유지된다
  (실제 경로는 `podman volume inspect ledger-mysql-data`).

첫 기동은 DB 초기화 때문에 십수 초 걸린다. **준비 완료를 확인한 뒤 앱을 띄운다** (앱이 먼저
뜨면 접속 실패로 재시작을 반복한다).

```sh
podman logs ledger-mysql 2>&1 | grep -m1 "ready for connections"
```

접속을 직접 확인하려면 (암호는 프롬프트로 입력):

```sh
podman exec -it ledger-mysql mysql -u ledger_memo -p ledger_memo
```

## 4. 앱 컨테이너

```sh
sudo mkdir -p /var/lib/ledger-memo/att

podman run -d --name ledger-memo --network ledger --restart=always \
  -p 127.0.0.1:8080:8080 \
  -v /var/lib/ledger-memo/att:/data/att:Z \
  --env-file /etc/ledger-memo/env \
  ghcr.io/kennysoft/ledger-memo:latest
```

- `127.0.0.1` 에만 publish 해 앞단 httpd 만 접근할 수 있게 한다.
- 첨부 파일은 호스트 디렉토리에 두어 컨테이너 교체와 무관하게 남는다. SELinux 환경에서는
  `:Z` 가 필요하다.
- 이미지는 public 이라 `podman login` 없이 pull 된다.

## 5. 재부팅 자동 시작

**Podman 은 데몬이 없어 `--restart=always` 만으로는 호스트 재부팅 후 컨테이너가 뜨지 않는다**
(Docker 와 다른 지점). 다음을 한 번 켜두면 부팅 시 `always` 정책 컨테이너를 시작해 준다.

```sh
sudo systemctl enable --now podman-restart.service          # rootful
systemctl --user enable --now podman-restart.service        # rootless (+ loginctl enable-linger $USER)
```

## 6. SELinux (Oracle Linux 계열)

httpd 가 프록시로 바깥 연결을 맺는 것이 기본 정책에서 막혀 502 가 난다.

```sh
sudo setsebool -P httpd_can_network_connect 1
```

## 7. 검증

```sh
curl -s http://127.0.0.1:8080/api/ping
podman stats --no-stream ledger-memo
```

`{"status":"ok","entryCount":0}` 이 나오면 **DB 연결 + Flyway 마이그레이션 7개 테이블 + JPA
매핑 검증**이 모두 통과한 것이다. 실패하면 `podman logs ledger-memo` 를 먼저 본다.

그 뒤 httpd VirtualHost (DESIGN.md 7.2) 를 올리고 서브도메인 인증서를 발급한다.

---

## 재배포

`main` push → Actions 가 ARM64 runner 에서 native 빌드 → GHCR 이미지 push. 서버에서는
컨테이너를 교체한다.

```sh
podman pull ghcr.io/kennysoft/ledger-memo:latest
podman rm -f ledger-memo
podman run -d --name ledger-memo --network ledger --restart=always \
  -p 127.0.0.1:8080:8080 \
  -v /var/lib/ledger-memo/att:/data/att:Z \
  --env-file /etc/ledger-memo/env \
  ghcr.io/kennysoft/ledger-memo:latest
```

`podman auto-update` 는 systemd 로 관리되는 컨테이너만 대상이라 이 구성에서는 쓸 수 없다.
위 명령을 스크립트로 두고 호출한다 (DESIGN.md 7.5).

## 백업

DB 와 첨부 디렉토리 둘 다 대상이다.

```sh
podman exec ledger-mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" ledger_memo' > ledger_memo.sql
sudo tar czf ledger-memo-att.tar.gz -C /var/lib/ledger-memo att
```
