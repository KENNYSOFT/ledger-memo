# ledger-memo

가계부 분개 전 단계의 거래 초안을 폰에서 즉석 기록하는 개인용 웹서비스.

한 줄 입력 또는 영수증 사진 한 장으로 기록하고, 나중에 분개장으로 옮길 때 목록에서 참조한다.
Google Keep 자유 텍스트 메모를 대체하면서 데이터를 구조화하는 것이 목적이다.

설계 문서: [DESIGN.md](./DESIGN.md)

## 쓰는 법

한 줄로 적으면 서버가 파싱해 장소/품목/금액/사람으로 쪼갠다. **소수점은 만원 단위**라는 것만
알면 된다 (실제 Keep 메모에서 역산한 규칙 — DESIGN.md 2장).

```
원조해장촌 2인세트 4.5 소주2 1.0 맥주3 1.5     → 45,000 + 10,000 + 15,000 = 70,000
싸리골 해물파전2.3 지평2 1.0 콜라 0.2?          → 23,000 + 10,000 + 2,000 = 35,000 (불확실 표시)
택시 8100 3명                                  → 8,100 (3자리 이상은 원 단위), 인원 3
```

파싱이 틀려도 **원문은 그대로 보존**되므로 나중에 고치거나 재파싱할 수 있다. 영수증 사진만
찍어 저장해도 유효한 기록이다.

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

JDK 25 필요. 설정은 환경변수로 주입한다 (기본값이 없어 미주입 시 기동 실패).

```sh
export LEDGER_DB_URL='jdbc:mysql://127.0.0.1:3306/ledger_memo?connectionTimeZone=UTC'
export LEDGER_DB_USER='...'
export LEDGER_DB_PASSWORD='...'
export LEDGER_ATTACHMENT_ROOT="$PWD/.att"
export LEDGER_AUTH_USERNAME='dev'
export LEDGER_AUTH_PASSWORD_HASH='{noop}dev'   # 로컬 전용. 서버에는 Argon2id 해시를 쓴다
export LEDGER_REMEMBER_ME_KEY='local-dev-key'
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
LEDGER_ATTACHMENT_ROOT=/data/att
LEDGER_AUTH_USERNAME=kenny
LEDGER_REMEMBER_ME_KEY=$(openssl rand -hex 32)
EOF
chmod 600 ~/.config/ledger-memo/*.env; unset APP_PW ROOT_PW
```

로그인 비밀번호 해시는 아직 비어 있다. **평문을 파일이나 명령줄에 두지 않기 위해** 이미지에
내장된 생성 모드로 만든다 (입력이 화면에 찍히지 않고 `ps` 에도 남지 않는다).

```sh
podman run --rm -it ghcr.io/kennysoft/ledger-memo:latest --generate-password-hash
```

출력된 `{argon2}...` 한 줄을 env 파일에 추가한다.

```sh
printf 'LEDGER_AUTH_PASSWORD_HASH=%s\n' '<붙여넣기>' >> ~/.config/ledger-memo/env
```

> 🚨 **`LEDGER_REMEMBER_ME_KEY` 를 바꾸면 기존 자동 로그인이 전부 무효가 된다.** 폰에서 다시
> 로그인해야 하므로 한 번 정하면 유지한다.

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
curl -s http://127.0.0.1:8081/actuator/health
podman stats --no-stream ledger-memo
```

`{"status":"UP"}` 이 나오면 **DB 연결까지 정상**이다 (헬스체크가 커넥션을 확인한다). 실패하면
`podman logs ledger-memo` 를 먼저 본다.

> 🚨 **`/api/ping` 은 401 이 정상이다.** 인증 도입 후 API 는 모두 보호 대상이라, 401 이
> 돌아온다는 것은 인증 필터가 걸려 있다는 뜻이다. 배포 검증에는 위 헬스체크를 쓴다.

## 7. 리버스 프록시

VirtualHost (DESIGN.md 7.2) 를 `/httpd-data/conf/` 의 설정에 추가하고 `svc-httpd` 를 재시작한다.
**기존 `*.kennysoft.kr` 와일드카드 인증서를 공유하므로 서브도메인 인증서 발급은 필요 없다.**

```sh
podman restart svc-httpd
curl -s -o /dev/null -w '%{http_code}\n' https://memo.kennysoft.kr/login.html
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

DB 덤프와 첨부 디렉토리를 한 파일로 묶는 스크립트가 있다
([scripts/backup.sh](./scripts/backup.sh)). 비밀번호를 명령줄에 노출하지 않고 컨테이너
환경변수를 그대로 쓰며, 덤프가 비면 실패로 끝내 깨진 백업을 남기지 않는다.

```sh
./scripts/backup.sh
```

기본값은 `~/backup/ledger-memo` 에 보관하고 30일이 지난 파일을 지운다. 환경변수로 바꾼다.

| 변수 | 기본값 |
|---|---|
| `LEDGER_BACKUP_DIR` | `~/backup/ledger-memo` |
| `LEDGER_ATTACHMENT_DIR` | `~/ledger-memo/att` |
| `LEDGER_BACKUP_KEEP_DAYS` | `30` |

매일 새벽 4시에 돌리려면 (rootless podman 이므로 사용자 crontab 에 등록한다):

```sh
(crontab -l 2>/dev/null; echo "0 4 * * * $HOME/ledger-memo/scripts/backup.sh >> $HOME/backup/ledger-memo.log 2>&1") | crontab -
```

복원은 아카이브를 풀어 `ledger_memo.sql` 을 적용하고 `attachments.tar.gz` 를 첨부 경로에
되돌린다.

```sh
tar xzf ledger-memo-<타임스탬프>.tar.gz
podman exec -i ledger-mysql sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < ledger_memo.sql
```
