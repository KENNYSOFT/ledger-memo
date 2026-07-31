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
CSRF=$(awk '/XSRF-TOKEN/ { print $7 }' "$COOKIES")
[ -n "$CSRF" ] || fail "CSRF 쿠키가 내려오지 않았다"

curl -sf -b "$COOKIES" -c "$COOKIES" -o /dev/null \
  --data-urlencode "username=$USERNAME" \
  --data-urlencode "password=$PASSWORD" \
  --data-urlencode "_csrf=$CSRF" \
  "$BASE/login" || fail "로그인 요청 자체가 실패했다"

# 로그인 실패도 302(/login.html?error)라서 curl 의 종료 코드로는 구분되지 않는다.
# 인증이 실제로 됐는지 API 로 확인해야 뒤쪽 실패를 오진하지 않는다.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIES" "$BASE/api/entries")
[ "$CODE" = "200" ] || fail "로그인 후에도 인증되지 않았다 (HTTP $CODE)"

# 로그인 성공 시 세션이 새로 발급되면서 CSRF 토큰도 갱신된다.
CSRF=$(awk '/XSRF-TOKEN/ { print $7 }' "$COOKIES")
[ -n "$CSRF" ] || fail "로그인 후 CSRF 쿠키가 없다"

# 태그가 없는 저장이 핵심 회귀 지점이다. 응답의 빈 태그 목록이 EmptyList 로 만들어지면
# native 에서 직렬화가 깨진다.
CREATED=$(curl -sf -b "$COOKIES" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"rawText":"원조해장촌 2인세트 4.5 소주2 1.0"}' \
  "$BASE/api/entries") || fail "저장이 실패했다 (native 직렬화 회귀 의심)"

echo "$CREATED" | grep -q '"totalAmount":55000' || fail "파싱 결과가 다르다: $CREATED"
echo "$CREATED" | grep -q '"place":"원조해장촌"' || fail "장소가 다르다: $CREATED"

ID=$(echo "$CREATED" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
[ -n "$ID" ] || fail "생성된 id 를 읽지 못했다: $CREATED"

# 파싱 미리보기도 같은 직렬화 경로를 탄다.
curl -sf -b "$COOKIES" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"text":"택시 8100"}' "$BASE/api/parse" | grep -q '"totalAmount":8100' \
  || fail "파싱 미리보기가 실패했다"

# 목록·상세 조회
curl -sf -b "$COOKIES" "$BASE/api/entries" | grep -q '"content"' || fail "목록 조회가 실패했다"
curl -sf -b "$COOKIES" "$BASE/api/entries/$ID" | grep -q '"items"' || fail "상세 조회가 실패했다"
curl -sf -b "$COOKIES" "$BASE/api/settlements" > /dev/null || fail "정산 조회가 실패했다"

# 정리 (테스트 DB 에 흔적을 남기지 않는다)
curl -sf -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X DELETE "$BASE/api/entries/$ID" \
  || fail "삭제가 실패했다"

# 인증 없는 API 는 401 이어야 한다.
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/entries")
[ "$CODE" = "401" ] || fail "인증 없는 요청이 401 이 아니다: $CODE"

echo "스모크 테스트 통과"
