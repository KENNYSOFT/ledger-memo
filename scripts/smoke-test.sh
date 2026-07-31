#!/bin/sh
# native 바이너리를 실제로 띄워 로그인부터 저장·조회까지 왕복한다.
#
# JVM 테스트로는 잡히지 않는 native 전용 결함을 배포 전에 걸러내는 것이 목적이다. 실제로
# kotlin.collections.EmptyList 메타데이터 누락 때문에 "태그 없는 저장"이 전부 500 이 되는
# 회귀가 배포 후에야 발견됐다 (DESIGN.md 7.1).
#
# 사용법: BINARY=build/native/nativeCompile/ledger-memo ./scripts/smoke-test.sh
set -eu

BINARY="${BINARY:-build/native/nativeCompile/ledger-memo}"
PORT="${PORT:-18080}"
BASE="http://127.0.0.1:$PORT"
COOKIES=$(mktemp)
LOG=$(mktemp)

USERNAME=smoke
PASSWORD=smoke-password

cleanup() {
  status=$?
  if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  # 실패했을 때만 로그를 남긴다. 성공 로그는 CI 출력을 채울 뿐이다.
  if [ "$status" -ne 0 ]; then
    echo "--- 애플리케이션 로그 ---" >&2
    cat "$LOG" >&2
  fi
  rm -f "$COOKIES" "$LOG"
}
trap cleanup EXIT

fail() {
  echo "스모크 테스트 실패: $1" >&2
  exit 1
}

# {noop} 은 해시 없이 평문을 비교한다. 스모크 테스트 전용이다.
SERVER_PORT="$PORT" \
LEDGER_AUTH_USERNAME="$USERNAME" \
LEDGER_AUTH_PASSWORD_HASH="{noop}$PASSWORD" \
LEDGER_REMEMBER_ME_KEY=smoke-key \
LEDGER_ATTACHMENT_ROOT="$(mktemp -d)" \
  "$BINARY" > "$LOG" 2>&1 &
APP_PID=$!

echo "기동 대기 (pid $APP_PID)..."
i=0
until curl -sf "$BASE/actuator/health" > /dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -gt 60 ] && fail "60초 안에 기동하지 못했다"
  kill -0 "$APP_PID" 2>/dev/null || fail "프로세스가 죽었다"
  sleep 1
done
echo "기동 완료"

# CSRF 쿠키를 받아 로그인한다 (CookieCsrfTokenRepository).
curl -sf -c "$COOKIES" "$BASE/login.html" > /dev/null || fail "로그인 페이지를 받지 못했다"
CSRF=$(awk '/XSRF-TOKEN/ { print $7 }' "$COOKIES" | head -1)
[ -n "$CSRF" ] || fail "CSRF 쿠키가 내려오지 않았다"

curl -sf -b "$COOKIES" -c "$COOKIES" -o /dev/null \
  --data-urlencode "username=$USERNAME" \
  --data-urlencode "password=$PASSWORD" \
  --data-urlencode "_csrf=$CSRF" \
  "$BASE/login" || fail "로그인 요청 자체가 실패했다"

# 로그인 실패도 302(/login.html?error)라서 curl 의 종료 코드로는 구분되지 않는다.
# 인증이 실제로 됐는지 API 로 확인해야 뒤쪽 실패를 오진하지 않는다.
#
# 이 요청에 -c 가 반드시 있어야 한다. Spring Security 는 로그인 성공 시 세션 고정 방어로
# 기존 CSRF 토큰을 폐기하고 새 토큰은 다음 요청에서 만들어 내려준다. 여기서 쿠키를 저장하지
# 않으면 폐기된 상태만 남아 뒤이은 POST 가 전부 403 이 된다.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" "$BASE/api/entries")
[ "$CODE" = "200" ] || fail "로그인 후에도 인증되지 않았다 (HTTP $CODE)"

CSRF=$(awk '/XSRF-TOKEN/ { print $7 }' "$COOKIES" | head -1)
[ -n "$CSRF" ] || fail "로그인 후 CSRF 쿠키가 없다"

# 태그가 없는 저장이 핵심 회귀 지점이다. 응답의 빈 태그 목록이 EmptyList 로 만들어지면
# native 에서 직렬화가 깨진다.
CREATED=$(curl -sf -b "$COOKIES" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"rawText":"원조해장촌 2인세트 4.5 소주2 1.0"}' \
  "$BASE/api/entries") || fail "저장이 실패했다 (native 직렬화 회귀 의심)"

echo "$CREATED" | grep -q '"totalAmount":55000' || fail "파싱 결과가 다르다: $CREATED"
echo "$CREATED" | grep -q '"place":"원조해장촌"' || fail "장소가 다르다: $CREATED"

# 응답에는 품목·첨부의 id 도 들어 있다. 맨 앞(entry 자신)만 집어야 한다 - greedy 매칭으로
# 마지막 id 를 잡으면 엉뚱한 대상을 조회하게 된다.
ID=$(echo "$CREATED" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -n "$ID" ] || fail "생성된 id 를 읽지 못했다: $CREATED"

# 파싱 미리보기도 같은 직렬화 경로를 탄다.
curl -sf -b "$COOKIES" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"text":"택시 8100"}' "$BASE/api/parse" | grep -q '"totalAmount":8100' \
  || fail "파싱 미리보기가 실패했다"

# 목록·상세 조회. 저장 응답과 달리 이쪽은 DB 에서 다시 읽은 엔티티를 직렬화하므로,
# lazy 컬렉션을 트랜잭션 밖에서 건드리면 여기서 500 이 된다 (DESIGN.md 7.1).
curl -sf -b "$COOKIES" "$BASE/api/entries" | grep -q '"content"' || fail "목록 조회가 실패했다"
curl -sf -b "$COOKIES" "$BASE/api/entries/recent" > /dev/null || fail "최근 목록 조회가 실패했다"
curl -sf -b "$COOKIES" "$BASE/api/entries/$ID" | grep -q '"items"' || fail "상세 조회가 실패했다"
curl -sf -b "$COOKIES" "$BASE/api/settlements" > /dev/null || fail "정산 조회가 실패했다"

# 상태 전환과 재파싱도 조회한 엔티티를 직렬화한다.
curl -sf -b "$COOKIES" -X PUT -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"status":"DONE"}' "$BASE/api/entries/$ID/status" | grep -q '"DONE"' \
  || fail "상태 전환이 실패했다"

curl -sf -b "$COOKIES" -X POST -H "X-XSRF-TOKEN: $CSRF" "$BASE/api/entries/$ID/reparse" \
  | grep -q '"원조해장촌"' || fail "재파싱이 실패했다"

# 힌트는 데이터가 없으면 빈 목록을 돌려준다. 빈 컬렉션 직렬화 경로를 함께 검증한다.
curl -sf -b "$COOKIES" "$BASE/api/hints" | grep -q '"categories"' || fail "힌트 조회가 실패했다"

# 태그를 붙이고 되읽어 PATCH 의 태그 교체가 동작하는지 확인한다.
curl -sf -b "$COOKIES" -X PATCH -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"tags":["회사","가족"],"categoryHint":"식비"}' "$BASE/api/entries/$ID" \
  | grep -q '"회사"' || fail "태그 수정이 반영되지 않았다"

# 빈 배열로 보내면 태그가 모두 떨어져야 한다 (빈 목록 직렬화 재확인).
curl -sf -b "$COOKIES" -X PATCH -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"tags":[]}' "$BASE/api/entries/$ID" | grep -q '"tags":\[\]' || fail "태그 비우기가 실패했다"

# 일괄 임포트 (실패 목록이 비어 있는 응답도 직렬화된다)
BULK=$(curl -sf -b "$COOKIES" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"text":"다이소 건전지 3000\n택시 7200"}' "$BASE/api/entries/bulk") \
  || fail "일괄 임포트가 실패했다"
BULK_IDS=$(echo "$BULK" | grep -o '"id":[0-9]*' | cut -d: -f2)
[ -n "$BULK_IDS" ] || fail "일괄 임포트 결과에 id 가 없다: $BULK"
for bulk_id in $BULK_IDS; do
  curl -sf -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X DELETE "$BASE/api/entries/$bulk_id" > /dev/null \
    || fail "임포트분 삭제가 실패했다 (id=$bulk_id)"
done

# 정리 (테스트 DB 에 흔적을 남기지 않는다)
curl -sf -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X DELETE "$BASE/api/entries/$ID" \
  || fail "삭제가 실패했다"

# 인증 없는 API 는 401 이어야 한다.
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/entries")
[ "$CODE" = "401" ] || fail "인증 없는 요청이 401 이 아니다: $CODE"

echo "스모크 테스트 통과"
