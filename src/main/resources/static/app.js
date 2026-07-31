'use strict';

/**
 * 가계부 메모 프론트엔드.
 *
 * 빌드 도구 없이 동작하는 단일 스크립트다 (DESIGN.md 5). 파싱 규칙은 서버에만 있고
 * 여기서는 미리보기를 요청해 칩으로 보여주기만 한다.
 */

const PARSE_DEBOUNCE_MS = 300;
const QUEUE_KEY = 'ledger-memo.queue';
const THUMB_MAX_EDGE = 320;
const PHOTO_MAX_EDGE = 1600;
const PHOTO_QUALITY = 0.82;

/** 저장 버튼을 누르기 전까지 들고 있는 사진들. */
let pendingPhotos = [];
let parseTimer = null;
let lastParsed = null;

// --- 공통 -------------------------------------------------------------------

function $(id) {
  return document.getElementById(id);
}

function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.classList.add('show');
  setTimeout(() => el.classList.remove('show'), 1800);
}

/** Spring Security 가 내려준 CSRF 쿠키를 헤더로 되돌려준다. */
function csrfHeader() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) } : {};
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    ...options,
    headers: { ...(options.headers || {}), ...csrfHeader() },
  });
  if (response.status === 401) {
    location.href = '/login.html';
    throw new Error('unauthorized');
  }
  if (!response.ok) {
    throw new Error(`${options.method || 'GET'} ${path} → ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

function json(method, body) {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

function formatAmount(amount) {
  return amount == null ? '' : `${amount.toLocaleString('ko-KR')}원`;
}

function formatDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  const weekday = '일월화수목금토'[new Date(y, m - 1, d).getDay()];
  return `${m}/${d} (${weekday})`;
}

// --- 작성 화면 --------------------------------------------------------------

function renderChips(parsed) {
  const chips = [];
  if (parsed.place) chips.push({ text: parsed.place });
  parsed.items.forEach((item) => {
    const qty = item.qty ? ` x${item.qty}` : '';
    const amount = item.amount == null ? '' : ` ${formatAmount(item.amount)}`;
    chips.push({ text: `${item.name}${qty}${amount}` });
  });
  if (parsed.totalAmount != null) chips.push({ text: `합계 ${formatAmount(parsed.totalAmount)}`, cls: 'amount' });
  if (parsed.headcount) chips.push({ text: `${parsed.headcount}명` });
  parsed.personNames.forEach((name) => chips.push({ text: name }));
  parsed.tags.forEach((tag) => chips.push({ text: `#${tag}` }));
  if (parsed.uncertain) chips.push({ text: '불확실', cls: 'warn' });

  $('chips').innerHTML = chips
    .map((chip) => `<span class="chip ${chip.cls || ''}">${escapeHtml(chip.text)}</span>`)
    .join('');
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function schedulePreview() {
  clearTimeout(parseTimer);
  parseTimer = setTimeout(async () => {
    const text = $('line').value.trim();
    if (!text) {
      lastParsed = null;
      $('chips').innerHTML = '';
      return;
    }
    try {
      lastParsed = await api('/api/parse', json('POST', { text }));
      renderChips(lastParsed);
    } catch (error) {
      // 오프라인이면 미리보기만 포기한다. 저장은 큐로 처리된다.
      console.debug('parse 실패', error);
    }
  }, PARSE_DEBOUNCE_MS);
}

/**
 * 사진을 캔버스로 축소한다. 서버는 이미지 처리를 하지 않으므로 원본 축소와 썸네일 생성이
 * 모두 클라이언트 몫이다 (DESIGN.md 1.4).
 */
function resize(file, maxEdge, quality) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      const scale = Math.min(1, maxEdge / Math.max(image.width, image.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(image.width * scale);
      canvas.height = Math.round(image.height * scale);
      canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('toBlob 실패'))), 'image/jpeg', quality);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('이미지를 읽을 수 없습니다'));
    };
    image.src = url;
  });
}

async function addPhotos(files) {
  for (const file of files) {
    try {
      const [full, thumb] = await Promise.all([
        resize(file, PHOTO_MAX_EDGE, PHOTO_QUALITY),
        resize(file, THUMB_MAX_EDGE, 0.7),
      ]);
      pendingPhotos.push({ full, thumb, shotAt: new Date(file.lastModified).toISOString() });
    } catch (error) {
      toast(`사진을 처리하지 못했습니다: ${error.message}`);
    }
  }
  renderPendingThumbs();
}

function renderPendingThumbs() {
  const container = $('pending-thumbs');
  container.innerHTML = '';
  pendingPhotos.forEach((photo, index) => {
    const img = document.createElement('img');
    img.src = URL.createObjectURL(photo.thumb);
    img.title = '탭하면 제거';
    img.onclick = () => {
      pendingPhotos.splice(index, 1);
      renderPendingThumbs();
    };
    container.appendChild(img);
  });
}

async function uploadPhotos(entryId) {
  for (const photo of pendingPhotos) {
    const form = new FormData();
    form.append('file', photo.full, 'photo.jpg');
    form.append('thumb', photo.thumb, 'thumb.jpg');
    if (photo.shotAt) form.append('shotAt', photo.shotAt);
    await api(`/api/entries/${entryId}/attachments`, { method: 'POST', body: form });
  }
}

async function save() {
  const text = $('line').value.trim();
  if (!text && pendingPhotos.length === 0) {
    toast('한 줄 입력이나 사진 중 하나는 필요합니다');
    return;
  }

  $('btn-save').disabled = true;
  try {
    const entry = await api('/api/entries', json('POST', { rawText: text || null, attachmentOnly: !text }));
    await uploadPhotos(entry.id);
    resetForm();
    toast('저장했습니다');
    await loadRecent();
  } catch (error) {
    // 사진은 직렬화가 어려워 큐에 넣지 않는다. 텍스트만 보관하고 복귀 시 보낸다.
    if (!navigator.onLine && text) {
      enqueue(text);
      resetForm();
      toast('오프라인이라 대기열에 넣었습니다');
    } else {
      toast(`저장 실패: ${error.message}`);
    }
  } finally {
    $('btn-save').disabled = false;
  }
}

function resetForm() {
  $('line').value = '';
  $('chips').innerHTML = '';
  pendingPhotos = [];
  lastParsed = null;
  renderPendingThumbs();
}

// --- 오프라인 큐 ------------------------------------------------------------

function readQueue() {
  try {
    return JSON.parse(localStorage.getItem(QUEUE_KEY) || '[]');
  } catch {
    return [];
  }
}

function writeQueue(queue) {
  localStorage.setItem(QUEUE_KEY, JSON.stringify(queue));
  renderQueueBadge();
}

function enqueue(text) {
  const queue = readQueue();
  queue.push({ rawText: text, queuedAt: new Date().toISOString() });
  writeQueue(queue);
}

function renderQueueBadge() {
  const count = readQueue().length;
  const el = $('queued');
  el.hidden = count === 0;
  el.textContent = count === 0 ? '' : `대기 중인 입력 ${count}건 (온라인 복귀 시 자동 전송)`;
}

/**
 * 큐를 순서대로 비운다. 실패하면 그 항목을 남기고 멈춘다 (순서를 지키기 위해).
 */
async function flushQueue() {
  let queue = readQueue();
  if (queue.length === 0 || !navigator.onLine) return;

  while (queue.length > 0) {
    try {
      await api('/api/entries', json('POST', { rawText: queue[0].rawText }));
      queue = queue.slice(1);
      writeQueue(queue);
    } catch (error) {
      console.debug('큐 전송 실패', error);
      return;
    }
  }
  toast('대기열을 전송했습니다');
  await loadRecent();
}

// --- 목록 -------------------------------------------------------------------

function entryCard(entry, options = {}) {
  const time = entry.occurredAt ? entry.occurredAt.slice(0, 5) : '';
  const attachments = entry.attachmentCount > 0 ? `사진 ${entry.attachmentCount}` : '';
  const actions = options.withActions
    ? `<div class="actions">
         <button class="done" data-done="${entry.id}">${entry.status === 'DONE' ? '되돌리기' : '완료'}</button>
         <button class="del" data-del="${entry.id}">삭제</button>
       </div>`
    : '';
  return `
    <div class="card" data-id="${entry.id}">
      <div class="top">
        <span class="place">${escapeHtml(entry.place || '(장소 없음)')}</span>
        <span class="amount">${formatAmount(entry.totalAmount)}</span>
      </div>
      ${entry.rawText ? `<div class="raw">${escapeHtml(entry.rawText)}</div>` : ''}
      <div class="meta">
        <span>${time}</span>
        ${entry.uncertain ? '<span>불확실</span>' : ''}
        ${attachments ? `<span>${attachments}</span>` : ''}
        ${entry.status === 'DONE' ? '<span>완료</span>' : ''}
      </div>
      ${actions}
    </div>`;
}

async function loadRecent() {
  try {
    const entries = await api('/api/entries/recent');
    $('recent').innerHTML = entries.length
      ? entries.map((entry) => entryCard(entry)).join('')
      : '<div class="empty">아직 없습니다</div>';
  } catch (error) {
    console.debug('최근 목록 실패', error);
  }
}

async function loadList() {
  const status = $('status').value;
  const q = $('q').value.trim();
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  if (q) params.set('q', q);

  try {
    const result = await api(`/api/entries?${params}`);
    const entries = result.content;
    if (entries.length === 0) {
      $('list').innerHTML = '<div class="empty">기록이 없습니다</div>';
      return;
    }

    // 날짜별로 묶어 보여준다. 서버가 이미 날짜 내림차순으로 준다.
    const groups = new Map();
    entries.forEach((entry) => {
      if (!groups.has(entry.occurredOn)) groups.set(entry.occurredOn, []);
      groups.get(entry.occurredOn).push(entry);
    });

    $('list').innerHTML = [...groups.entries()]
      .map(([date, items]) =>
        `<h2 class="date">${formatDate(date)}</h2>` +
        items.map((entry) => entryCard(entry, { withActions: true })).join(''))
      .join('');
  } catch (error) {
    $('list').innerHTML = `<div class="empty">불러오지 못했습니다: ${escapeHtml(error.message)}</div>`;
  }
}

async function onListClick(event) {
  const doneId = event.target.dataset.done;
  const delId = event.target.dataset.del;

  if (doneId) {
    const card = event.target.closest('.card');
    const isDone = card.textContent.includes('완료') && event.target.textContent === '되돌리기';
    await api(`/api/entries/${doneId}/status`, json('PUT', { status: isDone ? 'OPEN' : 'DONE' }));
    await loadList();
  } else if (delId) {
    if (!confirm('삭제할까요?')) return;
    await api(`/api/entries/${delId}`, { method: 'DELETE' });
    await loadList();
  }
}

// --- 초기화 -----------------------------------------------------------------

function showTab(name) {
  const write = name === 'write';
  $('view-write').hidden = !write;
  $('view-list').hidden = write;
  $('tab-write').setAttribute('aria-selected', String(write));
  $('tab-list').setAttribute('aria-selected', String(!write));
  if (!write) loadList();
}

function init() {
  $('tab-write').onclick = () => showTab('write');
  $('tab-list').onclick = () => showTab('list');
  $('line').addEventListener('input', schedulePreview);
  $('btn-save').onclick = save;
  $('btn-photo').onclick = () => $('photo').click();
  $('photo').onchange = (event) => {
    addPhotos([...event.target.files]);
    event.target.value = '';
  };
  $('list').addEventListener('click', onListClick);
  $('q').addEventListener('input', () => {
    clearTimeout(parseTimer);
    parseTimer = setTimeout(loadList, PARSE_DEBOUNCE_MS);
  });
  $('status').onchange = loadList;

  window.addEventListener('online', flushQueue);
  renderQueueBadge();
  flushQueue();
  loadRecent();

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch((error) => console.debug('sw 등록 실패', error));
  }
}

init();
