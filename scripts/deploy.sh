#!/bin/sh
# ledger-memo 배포 - CI 통과를 확인하고 이미지와 화면을 함께 올린다.
#
# push 는 하지 않는다. 미push 커밋이 있으면 알리고 멈춘다 (무엇을 배포하는지가 분명해야 한다).
#
# 사용법:
#   ./scripts/deploy.sh              CI 대기 → 이미지 교체 → 화면 업로드 → 헬스체크
#   ./scripts/deploy.sh --static     화면만 (빌드/재생성 없음, 몇 초)
#   ./scripts/deploy.sh --no-wait    CI 가 이미 끝났다고 보고 대기를 건너뛴다
set -eu

HOST="${LEDGER_SSH_HOST:-ampere}"
REPO="${LEDGER_GH_REPO:-KENNYSOFT/ledger-memo}"
IMAGE="${LEDGER_IMAGE:-ghcr.io/kennysoft/ledger-memo:latest}"
CONTAINER="${LEDGER_CONTAINER:-ledger-memo}"
HEALTH_URL="${LEDGER_HEALTH_URL:-http://127.0.0.1:8081/actuator/health}"
SCRIPT_DIR="$(dirname "$0")"

STATIC_ONLY=0
WAIT_CI=1
for arg in "$@"; do
  case "$arg" in
    --static|--static-only) STATIC_ONLY=1 ;;
    --no-wait) WAIT_CI=0 ;;
    -h|--help)
      # 맨 위 주석 블록만 보여준다. 줄 번호를 박아두면 주석이 늘 때 코드가 섞여 나온다.
      awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
      exit 0
      ;;
    *) echo "알 수 없는 인자: $arg (--help 참고)" >&2; exit 1 ;;
  esac
done

fail() { echo "배포 중단: $1" >&2; exit 1; }

# --- 화면만 올리는 경로 -----------------------------------------------------
if [ "$STATIC_ONLY" -eq 1 ]; then
  exec "$SCRIPT_DIR/deploy-static.sh" "$HOST"
fi

# --- 무엇을 배포하는지 확인 -------------------------------------------------
git diff --quiet || echo "경고: 커밋하지 않은 변경이 있습니다 (배포에는 포함되지 않습니다)"

git fetch origin main --quiet
SHA=$(git rev-parse HEAD)
git merge-base --is-ancestor HEAD origin/main \
  || fail "HEAD 가 origin/main 에 없습니다. 먼저 push 하세요 (이 스크립트는 push 하지 않습니다)."

echo "배포 대상: $(git log -1 --format='%h %s')"

# --- CI 확인 ----------------------------------------------------------------
# gh 의 --jq 는 --arg 를 지원하지 않으므로 파이프로 넘긴다 (조용히 빈 결과가 되는 것을 방지).
run_state() {
  gh run list -R "$REPO" --limit 20 --json headSha,status,conclusion \
    | jq -r --arg sha "$SHA" \
        '[.[] | select(.headSha == $sha)] | if length == 0 then "none" else .[0] | "\(.status) \(.conclusion // "-")" end'
}

if [ "$WAIT_CI" -eq 1 ]; then
  echo "CI 확인 중..."
  i=0
  while :; do
    STATE=$(run_state)
    case "$STATE" in
      "completed success") echo "CI 통과"; break ;;
      "completed "*)       fail "CI 가 실패했습니다 ($STATE). gh run list -R $REPO 로 확인하세요." ;;
      none)                fail "이 커밋의 CI run 이 없습니다. 문서만 바뀐 커밋이면 --no-wait 를 쓰세요." ;;
      *)
        i=$((i + 1))
        [ "$i" -gt 60 ] && fail "CI 가 20분 안에 끝나지 않았습니다 (마지막 상태: $STATE)"
        printf '  %s (%d분 경과)\n' "$STATE" "$((i / 3))"
        sleep 20
        ;;
    esac
  done
fi

# --- 이미지 교체 ------------------------------------------------------------
# 원격에서 확장되어야 하는 값은 env 로 넘긴다. heredoc 은 quoted 라 로컬 확장이 없다.
echo "이미지 교체 중..."
ssh "$HOST" "IMAGE='$IMAGE' CONTAINER='$CONTAINER' sh -s" <<'REMOTE'
set -eu

BEFORE=$(podman image inspect "$IMAGE" --format '{{.Digest}}' 2>/dev/null || echo none)
podman pull -q "$IMAGE" >/dev/null
AFTER=$(podman image inspect "$IMAGE" --format '{{.Digest}}')
[ "$BEFORE" = "$AFTER" ] && echo "  이미지가 이전과 같습니다 (재생성은 계속합니다)"

# rm -f 는 rootless overlay 에서 스토리지 정리에 실패하는 일이 있다. 나눠서 시도한다.
if podman container exists "$CONTAINER"; then
  podman stop -t 10 "$CONTAINER" >/dev/null 2>&1 || true
  podman rm "$CONTAINER" >/dev/null 2>&1 || podman rm -f "$CONTAINER" >/dev/null 2>&1 || true
fi

# 컨테이너 레코드는 지워졌는데 스토리지에만 남은 상태 (DESIGN.md 7.6).
if podman ps -a --storage --format '{{.Names}}' 2>/dev/null | grep -qx "$CONTAINER"; then
  echo "  스토리지에 남은 레코드를 정리합니다"
  podman rm --storage -f "$CONTAINER" >/dev/null 2>&1 || {
    echo "  자동 정리에 실패했습니다. DESIGN.md 7.6 의 절차를 따르세요:" >&2
    echo "    podman unshare rm -rf <merged> 후 podman rm --storage -f $CONTAINER" >&2
    exit 1
  }
fi

mkdir -p ~/ledger-memo/att ~/ledger-memo/static
podman run -d --name "$CONTAINER" --network=host --restart=always \
  -v ~/ledger-memo/att:/data/att:Z \
  -v ~/ledger-memo/static:/data/static:Z \
  --env-file ~/.config/ledger-memo/env \
  "$IMAGE" >/dev/null
echo "  컨테이너 재생성 완료"
REMOTE

# --- 화면 업로드 ------------------------------------------------------------
# 🚨 볼륨이 classpath 보다 우선이므로, 이미지를 새로 올렸어도 볼륨의 옛 화면이 그대로 보인다.
# 이미지 배포와 화면 업로드는 항상 함께 해야 한다.
echo "화면 업로드 중..."
"$SCRIPT_DIR/deploy-static.sh" "$HOST" > /dev/null

# --- 헬스체크 ---------------------------------------------------------------
printf '기동 확인'
i=0
while :; do
  if ssh "$HOST" "curl -sf --max-time 3 '$HEALTH_URL'" > /dev/null 2>&1; then
    echo " → UP"
    break
  fi
  i=$((i + 1))
  [ "$i" -gt 30 ] && { echo; fail "기동을 확인하지 못했습니다. ssh $HOST 'podman logs $CONTAINER' 를 보세요."; }
  printf '.'
  sleep 2
done

echo
echo "배포 완료: $(git log -1 --format='%h %s')"
