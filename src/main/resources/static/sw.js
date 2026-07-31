'use strict';

/**
 * PWA 서비스 워커.
 *
 * 앱 셸만 캐시하고 API 응답은 캐시하지 않는다. 개인 기록이라 로그아웃 뒤에도 남는 것을
 * 피해야 하고, 목록은 항상 최신이어야 하기 때문이다. 오프라인 입력은 서비스 워커가 아니라
 * app.js 의 localStorage 큐가 담당한다 (DESIGN.md 5).
 */

const CACHE = 'ledger-memo-v1';
const SHELL = ['/', '/app.js', '/manifest.webmanifest', '/icons/icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // API 와 로그인 흐름은 캐시를 거치지 않는다.
  if (request.method !== 'GET' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/login')) {
    return;
  }

  // 앱 셸은 네트워크 우선, 실패 시 캐시로 떨어진다. 배포 직후에도 최신을 받는다.
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached || caches.match('/'))),
  );
});
