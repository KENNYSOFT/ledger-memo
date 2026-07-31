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

**두 컨테이너 모두 `--network=host` 로 띄운다.** 이 서버의 기존 컨테이너(httpd, php-fpm)가
이미 같은 방식이라 맞추는 것이고, 사용자 정의 네트워크의 이름 기반 DNS 에 의존하지 않아
구성이 단순하다. 따로 만들 네트워크가 없다.

포트는 이렇게 나뉜다. rootless 라 httpd 가 80/443 을 직접 쓰지 못하고 firewalld 가
80/443 을 8080/8443 으로 포워딩하는 구조이므로, **8080 은 이미 httpd 차지다.**

| 포트 | 용도 |
|---|---|
| 8080 / 8443 | 기존 httpd |
| 8081 | ledger-memo |
| 3306 | MySQL |

앱과 DB 모두 `127.0.0.1` 에만 리슨시켜 외부에 노출하지 않는다.

## 2. 시크릿 파일

비밀번호를 `podman run -e` 로 넘기면 shell history 와 `ps` 에 남으므로 env 파일로만 다룬다.

> 🚨 **rootless podman 이면 파일 소유자가 컨테이너를 실행하는 사용자여야 한다.** root 소유
> 600 파일은 `--env-file` 에서 `permission denied` 로 실패한다. 아래는 `opc` 기준이다.

```sh
mkdir -p ~/.config/ledger-memo && chmod 700 ~/.config/ledger-memo

APP_PW=$(openssl rand -hex 24); ROOT_PW=$(openssl rand -hex 24)
cat > ~/.config/ledger-memo/mysql.env <<EOF
MYSQL_ROOT_PASSWORD=$ROOT_PW
MYSQL_DATABASE=ledger_memo
MYSQL_USER=ledger_memo
MYSQL_PASSWORD=$APP_PW
EOF
cat > ~/.config/ledger-memo/env <<EOF
SERVER_PORT=8081
SERVER_ADDRESS=127.0.0.1
LEDGER_DB_URL=jdbc:mysql://127.0.0.1:3306/ledger_memo?connectionTimeZone=UTC
LEDGER_DB_USER=ledger_memo
LEDGER_DB_PASSWORD=$APP_PW
EOF
chmod 600 ~/.config/ledger-memo/*.env; unset APP_PW ROOT_PW
```

암호를 셸 변수로 두 파일에 한 번에 넣어 **값이 어긋날 여지를 없앤다** (`MYSQL_PASSWORD` 와
`LEDGER_DB_PASSWORD` 는 반드시 같은 값이어야 한다).

> 🚨 **암호에 특수문자를 쓰지 말 것.** 세 군데에서 깨진다.
> - `#` — podman `--env-file` 파서가 주석으로 해석해 암호가 잘린다
> - `${` — Spring 이 주입된 값 안의 placeholder 를 다시 해석하려 한다
> - 백슬래시·따옴표 — MySQL entrypoint 의 계정 생성 SQL 이스케이프가 깨진다
>
> `openssl rand -hex 24` 는 영숫자 48자(192비트)로 충분하다.

## 3. MySQL 컨테이너

`MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` 를 주면 **첫 기동 때 DB 와 앱 계정이
자동 생성**된다. `CREATE DATABASE` / `CREATE USER` 를 따로 실행할 필요가 없다. 테이블은
앱이 처음 뜰 때 Flyway 가 만든다.

```sh
podman run -d --name ledger-mysql --network=host --restart=always \
  -v ledger-mysql-data:/var/lib/mysql \
  --env-file ~/.config/ledger-memo/mysql.env \
  docker.io/library/mysql:8.4 \
  --bind-address=127.0.0.1 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci
```

- 🚨 **`--bind-address=127.0.0.1` 이 외부 노출을 막는 유일한 장치다.** host 네트워크에는
  publish 개념이 없어 이걸 빼면 MySQL 이 인스턴스 공인 IP 에 그대로 열린다.
- 이미지 뒤의 인자는 `mysqld` 에 그대로 전달된다. 8.4 기본값도 utf8mb4 이지만, 한글 저장과
  정렬이 서버 설정에 좌우되므로 명시한다.
- 데이터는 named volume `ledger-mysql-data` 에 남아 컨테이너를 지워도 유지된다
  (실제 경로는 `podman volume inspect ledger-mysql-data`).

> 🚨 **암호를 바꿨으면 volume 을 지워야 반영된다.** mysql 이미지는 **첫 기동에만** 계정을
> 만든다. `ledger-mysql-data` 가 이미 초기화된 뒤에 env 를 바꿔도 무시되어 인증이 실패한다.
> ```sh
> podman rm -f ledger-mysql && podman volume rm ledger-mysql-data
> ```

첫 기동은 DB 초기화 때문에 십수 초 걸린다. **준비 완료를 확인한 뒤 앱을 띄운다** (앱이 먼저
뜨면 접속 실패로 재시작을 반복한다).

```sh
podman logs ledger-mysql 2>&1 | grep -m1 "ready for connections"
```

접속을 직접 확인하려면 (암호는 프롬프트로 입력):

```sh
podman exec -it ledger-mysql mysql -u ledger_memo -p ledger_memo
```

3306 이 비어 있는지 미리 확인하려면 (다른 DB 가 떠 있으면 충돌한다):

```sh
ss -lntp | grep 3306 || echo "3306 비어 있음"
```

## 4. 앱 컨테이너

```sh
mkdir -p ~/ledger-memo/att

podman run -d --name ledger-memo --network=host --restart=always \
  -v ~/ledger-memo/att:/data/att:Z \
  --env-file ~/.config/ledger-memo/env \
  ghcr.io/kennysoft/ledger-memo:latest
```

- 포트와 바인딩 주소는 **env 파일의 `SERVER_PORT` / `SERVER_ADDRESS`** 가 정한다 (Spring Boot
  가 환경변수를 그대로 받는다). 이미지 기본값은 8080 이지만 환경마다 이미지를 다시 만들 필요가
  없다.
- 🚨 **`SERVER_ADDRESS=127.0.0.1` 을 빼면 앱이 공인 IP 에 그대로 열린다.** host 네트워크에는
  publish 가 없어 바인딩 주소가 유일한 차단 수단이다.
- 첨부 파일은 호스트 디렉토리에 두어 컨테이너 교체와 무관하게 남는다. SELinux 환경에서는
  `:Z` 가 필요하다.
- 🚨 **rootless 에서는 컨테이너의 uid 0 이 호스트의 실행 사용자로 매핑된다.** 홈 아래에 두면
  소유자가 자연히 맞지만, `/var/lib` 같은 시스템 경로를 쓰면 실행 사용자로 `chown` 해야
  사진 저장이 permission denied 로 실패하지 않는다.
- 이미지는 public 이라 `podman login` 없이 pull 된다.

## 5. 재부팅 자동 시작

**Podman 은 데몬이 없어 `--restart=always` 만으로는 호스트 재부팅 후 컨테이너가 뜨지 않는다**
(Docker 와 다른 지점). 다음을 한 번 켜두면 부팅 시 `always` 정책 컨테이너를 시작해 준다.

```sh
sudo systemctl enable --now podman-restart.service          # rootful
systemctl --user enable --now podman-restart.service        # rootless (+ loginctl enable-linger $USER)
```

## 6. 검증

```sh
curl -s http://127.0.0.1:8081/api/ping
podman stats --no-stream ledger-memo
```

`{"status":"ok","entryCount":0}` 이 나오면 **DB 연결 + Flyway 마이그레이션 7개 테이블 + JPA
매핑 검증**이 모두 통과한 것이다. 실패하면 `podman logs ledger-memo` 를 먼저 본다.

## 7. 리버스 프록시

VirtualHost (DESIGN.md 7.2) 를 `/httpd-data/conf/` 의 설정에 추가하고 `svc-httpd` 를 재시작한다.
**기존 `*.kennysoft.kr` 와일드카드 인증서를 공유하므로 서브도메인 인증서 발급은 필요 없다.**

```sh
podman restart svc-httpd
curl -s https://memo.kennysoft.kr/api/ping
```

---

## 재배포

`main` push → Actions 가 ARM64 runner 에서 native 빌드 → GHCR 이미지 push. 서버에서는
컨테이너를 교체한다.

```sh
podman pull ghcr.io/kennysoft/ledger-memo:latest
podman rm -f ledger-memo
podman run -d --name ledger-memo --network=host --restart=always \
  -v ~/ledger-memo/att:/data/att:Z \
  --env-file ~/.config/ledger-memo/env \
  ghcr.io/kennysoft/ledger-memo:latest
```

`podman auto-update` 는 systemd 로 관리되는 컨테이너만 대상이라 이 구성에서는 쓸 수 없다.
위 명령을 스크립트로 두고 호출한다 (DESIGN.md 7.5).

## 백업

DB 와 첨부 디렉토리 둘 다 대상이다.

```sh
podman exec ledger-mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" ledger_memo' > ledger_memo.sql
tar czf ledger-memo-att.tar.gz -C ~/ledger-memo att
```
