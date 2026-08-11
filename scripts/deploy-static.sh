#!/bin/sh
# 화면(HTML/JS/CSS)만 서버에 올린다. native 재빌드도, 컨테이너 재생성도 필요 없다.
#
# 앱은 볼륨으로 마운트된 디렉토리를 정적 리소스 위치로 먼저 보므로(application.yml 의
# spring.web.resources.static-locations) 파일을 덮어쓰는 것만으로 반영된다.
#
# 사용법: ./scripts/deploy-static.sh [ssh호스트]
set -eu

HOST="${1:-${LEDGER_SSH_HOST:-ampere}}"
REMOTE_DIR="${LEDGER_REMOTE_STATIC:-~/ledger-memo/static}"
LOCAL_DIR="$(dirname "$0")/../src/main/resources/static"

[ -f "$LOCAL_DIR/index.html" ] || {
  echo "정적 리소스를 찾지 못했습니다: $LOCAL_DIR" >&2
  exit 1
}

echo "올릴 파일:"
find "$LOCAL_DIR" -type f | sed "s|$LOCAL_DIR/|  |"

# tar 파이프로 보낸다. rsync 가 없는 환경에서도 동작하고, 디렉토리 구조(icons/)를 유지한다.
# 원격에서 먼저 지우지 않는다 - 전송이 실패했을 때 화면이 사라지는 것보다 낫다.
tar czf - -C "$LOCAL_DIR" . \
  | ssh "$HOST" "mkdir -p $REMOTE_DIR && tar xzf - -C $REMOTE_DIR && ls -la $REMOTE_DIR"

echo
echo "완료. 브라우저를 새로 고치면 반영됩니다."
echo "서비스 워커가 앱 셸을 캐시하므로 폰에서는 한 번 더 새로 고쳐야 할 수 있습니다."
