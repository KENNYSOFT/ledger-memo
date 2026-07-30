# native 바이너리는 CI 에서 빌드해 컨텍스트에 놓인 것을 담는다 (DESIGN.md 7.3).
#
# glibc 정렬: 바이너리는 동적 링크이므로 빌드 환경의 glibc 가 실행 환경보다 낮거나 같아야
# 한다. CI runner 가 ubuntu-24.04-arm 이므로 런타임도 ubuntu:24.04 로 맞춘다.
# Alpine 은 musl 이라 glibc 바이너리가 실행되지 않으므로 쓰지 않는다.
FROM ubuntu:24.04

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/att

COPY ledger-memo /app/ledger-memo

EXPOSE 8080

# 컨테이너 내부에서는 모든 인터페이스에 바인딩한다. 외부 노출 제한은 호스트에서
# podman -p 127.0.0.1:8080:8080 으로 처리한다.
ENTRYPOINT ["/app/ledger-memo"]
