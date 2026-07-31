#!/bin/sh
# ledger-memo 백업 - DB 덤프와 첨부 디렉토리를 한 파일로 묶는다.
#
# cron 에서 무인 실행되므로 다음을 지킨다.
#   - 실패하면 즉시 멈춘다 (set -e). 깨진 백업을 성공으로 남기지 않는다.
#   - 비밀번호를 명령줄 인자로 두지 않는다. 컨테이너 안의 환경변수를 그대로 쓴다.
#   - 덤프를 먼저 임시 파일에 쓰고, 성공했을 때만 최종 이름으로 옮긴다.
set -eu

BACKUP_DIR="${LEDGER_BACKUP_DIR:-$HOME/backup/ledger-memo}"
ATTACHMENT_DIR="${LEDGER_ATTACHMENT_DIR:-$HOME/ledger-memo/att}"
KEEP_DAYS="${LEDGER_BACKUP_KEEP_DAYS:-30}"
STAMP=$(date +%Y%m%d-%H%M%S)
WORK="$BACKUP_DIR/.work-$STAMP"

mkdir -p "$BACKUP_DIR" "$WORK"
# 중간에 실패해도 작업 디렉토리를 남기지 않는다.
trap 'rm -rf "$WORK"' EXIT

# --single-transaction 으로 테이블을 잠그지 않고 일관된 스냅샷을 뜬다 (InnoDB).
podman exec ledger-mysql sh -c \
  'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines "$MYSQL_DATABASE"' \
  > "$WORK/ledger_memo.sql"

# 덤프가 비었으면 백업으로서 의미가 없다. 조용히 넘어가지 않는다.
if [ ! -s "$WORK/ledger_memo.sql" ]; then
  echo "덤프가 비어 있습니다. 백업을 중단합니다." >&2
  exit 1
fi

if [ -d "$ATTACHMENT_DIR" ]; then
  tar czf "$WORK/attachments.tar.gz" -C "$(dirname "$ATTACHMENT_DIR")" "$(basename "$ATTACHMENT_DIR")"
else
  echo "첨부 디렉토리가 없어 DB 만 백업합니다: $ATTACHMENT_DIR" >&2
fi

ARCHIVE="$BACKUP_DIR/ledger-memo-$STAMP.tar.gz"
tar czf "$ARCHIVE.tmp" -C "$WORK" .
mv "$ARCHIVE.tmp" "$ARCHIVE"

# 보관 기간이 지난 백업 정리. 방금 만든 것은 대상이 아니다.
find "$BACKUP_DIR" -maxdepth 1 -name 'ledger-memo-*.tar.gz' -type f -mtime "+$KEEP_DAYS" -delete

echo "백업 완료: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
