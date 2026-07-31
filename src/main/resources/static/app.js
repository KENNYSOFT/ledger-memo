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
  const doneButton = event.target.closest('[data-done]');
  const delButton = event.target.closest('[data-del]');

  if (doneButton) {
    // 버튼 라벨이 곧 다음 상태다. '되돌리기'가 보이면 지금은 완료 상태다.
    const next = doneButton.textContent.trim() === '되돌리기' ? 'OPEN' : 'DONE';
    await api(`/api/entries/${doneButton.dataset.done}/status`, json('PUT', { status: next }));
    await loadList();
    return;
  }
  if (delButton) {
    if (!confirm('삭제할까요?')) return;
    await api(`/api/entries/${delButton.dataset.del}`, { method: 'DELETE' });
    await loadList();
    return;
  }

  const card = event.target.closest('.card');
  if (card) openDetail(card.dataset.id);
}

// --- 상세 / 보강 -------------------------------------------------------------

let detailEntry = null;

async function openDetail(id) {
  try {
    detailEntry = await api(`/api/entries/${id}`);
    renderDetail();
    $('detail').hidden = false;
  } catch (error) {
    toast(`상세를 불러오지 못했습니다: ${error.message}`);
  }
}

function closeDetail() {
  $('detail').hidden = true;
  detailEntry = null;
}

function itemRow(item, index) {
  return `
    <div class="item-row" data-index="${index}">
      <input class="i-name" value="${escapeHtml(item.name)}" placeholder="품목">
      <input class="i-qty" value="${item.qty ?? ''}" inputmode="numeric" placeholder="수량">
      <input class="i-amount" value="${item.amount ?? ''}" inputmode="numeric" placeholder="금액">
      <button type="button" data-remove-item="${index}">×</button>
    </div>`;
}

function personRow(person) {
  const roles = [
    ['ATTENDEE', '동석'],
    ['DEBTOR', '받을 돈'],
    ['CREDITOR', '갚을 돈'],
  ];
  return `
    <div class="person-row" data-person="${person.id}">
      <span class="name">${escapeHtml(person.name)}</span>
      <select class="p-role">
        ${roles.map(([value, label]) =>
          `<option value="${value}"${person.role === value ? ' selected' : ''}>${label}</option>`).join('')}
      </select>
      <input class="share" type="number" inputmode="numeric" value="${person.shareAmount ?? ''}" placeholder="분담액">
      <label><input type="checkbox" class="p-settled"${person.settled ? ' checked' : ''}> 정산</label>
      <button type="button" class="icon" data-remove-person="${person.id}">삭제</button>
    </div>`;
}

function renderDetail() {
  const entry = detailEntry;
  $('detail-title').textContent = `${entry.occurredOn} ${entry.occurredAt ? entry.occurredAt.slice(0, 5) : ''}`;

  const photos = entry.attachments
    .map((a) => `<img src="/api/attachments/${a.id}?thumb=true" alt="첨부" data-attachment="${a.id}">`)
    .join('');

  $('detail-body').innerHTML = `
    ${entry.rawText ? `<div class="hint">원문: ${escapeHtml(entry.rawText)}</div>` : ''}

    <div class="grid2">
      <div class="field"><label>날짜</label><input type="date" id="d-date" value="${entry.occurredOn}"></div>
      <div class="field"><label>시각</label><input type="time" id="d-time" value="${entry.occurredAt ? entry.occurredAt.slice(0, 5) : ''}"></div>
    </div>
    <div class="field"><label>장소</label><input id="d-place" value="${escapeHtml(entry.place || '')}"></div>

    <div class="field"><label>품목</label><div id="d-items">${entry.items.map(itemRow).join('')}</div>
      <button type="button" class="ghost" id="d-add-item">품목 추가</button>
    </div>

    <div class="grid2">
      <div class="field"><label>합계 (비우면 품목 합)</label><input id="d-total" inputmode="numeric" value="${entry.totalAmount ?? ''}"></div>
      <div class="field"><label>인원</label><input id="d-headcount" inputmode="numeric" value="${entry.headcount ?? ''}"></div>
    </div>
    <div class="grid2">
      <div class="field"><label>카테고리</label><input id="d-category" value="${escapeHtml(entry.categoryHint || '')}" placeholder="식비, 교통 등"></div>
      <div class="field"><label>결제수단</label><input id="d-payment" value="${escapeHtml(entry.paymentHint || '')}" placeholder="카드, 현금 등"></div>
    </div>
    <div class="field"><label>메모</label><textarea id="d-memo" rows="2">${escapeHtml(entry.memo || '')}</textarea></div>

    <div class="field">
      <label>사람 / 정산</label>
      <div id="d-persons">${entry.persons.map(personRow).join('') || '<div class="hint">없음</div>'}</div>
      <div class="person-row">
        <select id="d-person-add" class="name"><option value="">사람 추가...</option></select>
      </div>
    </div>

    <div class="field">
      <label>사진</label>
      <div class="thumbs" id="d-photos">${photos || '<span class="hint">없음</span>'}</div>
      <button type="button" class="ghost" id="d-add-photo">사진 추가</button>
      <input type="file" id="d-photo-input" accept="image/*" capture="environment" multiple hidden>
    </div>

    <div class="row">
      <button class="ghost" id="d-reparse">원문 재파싱</button>
      <button class="primary" id="d-save">저장</button>
    </div>`;

  loadPersonOptions();
  bindDetailEvents();
}

async function loadPersonOptions() {
  try {
    const persons = await api('/api/persons');
    const used = new Set(detailEntry.persons.map((p) => p.personId));
    const select = $('d-person-add');
    persons.filter((p) => !used.has(p.id)).forEach((person) => {
      const option = document.createElement('option');
      option.value = person.id;
      option.textContent = person.name;
      select.appendChild(option);
    });
  } catch (error) {
    console.debug('사람 목록 실패', error);
  }
}

function bindDetailEvents() {
  $('d-add-item').onclick = () => {
    const container = $('d-items');
    container.insertAdjacentHTML('beforeend', itemRow({ name: '', qty: null, amount: null }, container.children.length));
  };
  $('d-items').onclick = (event) => {
    const button = event.target.closest('[data-remove-item]');
    if (button) button.closest('.item-row').remove();
  };
  $('d-save').onclick = saveDetail;
  $('d-reparse').onclick = reparseDetail;

  $('d-add-photo').onclick = () => $('d-photo-input').click();
  $('d-photo-input').onchange = async (event) => {
    const files = [...event.target.files];
    event.target.value = '';
    await addPhotos(files);
    // 상세에서는 곧바로 서버에 붙인다. 저장 버튼을 기다리지 않는다.
    try {
      await uploadPhotos(detailEntry.id);
      pendingPhotos = [];
      renderPendingThumbs();
      await openDetail(detailEntry.id);
      toast('사진을 추가했습니다');
    } catch (error) {
      toast(`사진 업로드 실패: ${error.message}`);
    }
  };

  $('d-persons').onchange = onPersonChange;
  $('d-persons').onclick = async (event) => {
    const button = event.target.closest('[data-remove-person]');
    if (!button) return;
    await api(`/api/entry-persons/${button.dataset.removePerson}`, { method: 'DELETE' });
    await openDetail(detailEntry.id);
  };
  $('d-person-add').onchange = async (event) => {
    const personId = Number(event.target.value);
    if (!personId) return;
    await api(`/api/entries/${detailEntry.id}/persons`, json('POST', { personId }));
    await openDetail(detailEntry.id);
  };
}

async function onPersonChange(event) {
  const row = event.target.closest('.person-row');
  if (!row || !row.dataset.person) return;

  const share = row.querySelector('.share').value.trim();
  await api(`/api/entry-persons/${row.dataset.person}`, json('PATCH', {
    role: row.querySelector('.p-role').value,
    shareAmount: share === '' ? null : Number(share),
    settled: row.querySelector('.p-settled').checked,
  }));
  toast('정산 정보를 저장했습니다');
}

function collectItems() {
  return [...$('d-items').querySelectorAll('.item-row')]
    .map((row) => ({
      name: row.querySelector('.i-name').value.trim(),
      qty: numberOrNull(row.querySelector('.i-qty').value),
      amount: numberOrNull(row.querySelector('.i-amount').value),
    }))
    .filter((item) => item.name !== '');
}

function numberOrNull(value) {
  const text = String(value).trim();
  return text === '' ? null : Number(text);
}

async function saveDetail() {
  // 문자열 필드는 빈 값도 그대로 보낸다. 서버가 ""를 "지움"으로 해석한다.
  const body = {
    occurredOn: $('d-date').value || null,
    occurredAt: $('d-time').value ? `${$('d-time').value}:00` : null,
    place: $('d-place').value.trim(),
    totalAmount: numberOrNull($('d-total').value),
    categoryHint: $('d-category').value.trim(),
    paymentHint: $('d-payment').value.trim(),
    headcount: numberOrNull($('d-headcount').value),
    memo: $('d-memo').value.trim(),
    items: collectItems(),
  };

  try {
    await api(`/api/entries/${detailEntry.id}`, json('PATCH', body));
    closeDetail();
    toast('저장했습니다');
    await loadList();
  } catch (error) {
    toast(`저장 실패: ${error.message}`);
  }
}

async function reparseDetail() {
  if (!confirm('손으로 고친 값이 원문 파싱 결과로 덮어써집니다. 계속할까요?')) return;
  try {
    await api(`/api/entries/${detailEntry.id}/reparse`, { method: 'POST' });
    await openDetail(detailEntry.id);
    toast('재파싱했습니다');
  } catch (error) {
    toast(`재파싱 실패: ${error.message}`);
  }
}

// --- 정산 -------------------------------------------------------------------

async function loadSettlements() {
  try {
    const rows = await api('/api/settlements');
    if (rows.length === 0) {
      $('settlements').innerHTML = '<div class="empty">미정산 내역이 없습니다</div>';
      return;
    }
    $('settlements').innerHTML = rows.map((row) => `
      <div class="card settle-card">
        <div>
          <div class="place">${escapeHtml(row.personName)}</div>
          <div class="meta"><span>${row.entryCount}건</span></div>
        </div>
        <div>
          <span class="amount">${formatAmount(row.totalAmount)}</span>
          <button class="icon" data-person-entries="${row.personId}">보기</button>
        </div>
      </div>`).join('');
  } catch (error) {
    $('settlements').innerHTML = `<div class="empty">불러오지 못했습니다: ${escapeHtml(error.message)}</div>`;
  }
}

async function onSettlementClick(event) {
  const button = event.target.closest('[data-person-entries]');
  if (!button) return;
  // 그 사람이 낀 기록만 목록 탭에서 보여준다.
  showTab('list');
  $('status').value = '';
  $('q').value = '';
  const result = await api(`/api/entries?personId=${button.dataset.personEntries}`);
  $('list').innerHTML = result.content.length
    ? result.content.map((entry) => entryCard(entry, { withActions: true })).join('')
    : '<div class="empty">기록이 없습니다</div>';
}

// --- 일괄 임포트 ------------------------------------------------------------

async function runImport() {
  const text = $('import-text').value.trim();
  if (!text) {
    toast('붙여넣은 내용이 없습니다');
    return;
  }

  $('btn-import').disabled = true;
  try {
    const result = await api('/api/entries/bulk', json('POST', { text }));
    const failed = result.failed
      .map((f) => `<div class="card failed"><div class="place">실패</div><div class="raw">${escapeHtml(f.text)}</div><div class="meta">${escapeHtml(f.reason)}</div></div>`)
      .join('');
    $('import-result').innerHTML =
      `<h2 class="date">${result.created.length}건 등록${result.failed.length ? `, ${result.failed.length}건 실패` : ''}</h2>` +
      result.created.map((entry) => entryCard(entry)).join('') + failed;

    // 성공분만 입력창에서 지운다. 실패한 줄은 남겨 다시 시도할 수 있게 한다.
    $('import-text').value = result.failed.map((f) => f.text).join('\n');
    await loadRecent();
  } catch (error) {
    toast(`임포트 실패: ${error.message}`);
  } finally {
    $('btn-import').disabled = false;
  }
}

// --- 초기화 -----------------------------------------------------------------

const TABS = ['write', 'list', 'settle', 'import'];

function showTab(name) {
  TABS.forEach((tab) => {
    $(`view-${tab}`).hidden = tab !== name;
    $(`tab-${tab}`).setAttribute('aria-selected', String(tab === name));
  });
  if (name === 'list') loadList();
  if (name === 'settle') loadSettlements();
}

/** 목록 검색용 debounce. 파싱 미리보기와 타이머를 공유하면 서로를 취소한다. */
let listTimer = null;

function init() {
  TABS.forEach((tab) => {
    $(`tab-${tab}`).onclick = () => showTab(tab);
  });

  $('line').addEventListener('input', schedulePreview);
  $('btn-save').onclick = save;
  $('btn-photo').onclick = () => $('photo').click();
  $('photo').onchange = (event) => {
    addPhotos([...event.target.files]);
    event.target.value = '';
  };

  $('list').addEventListener('click', onListClick);
  $('recent').addEventListener('click', (event) => {
    const card = event.target.closest('.card');
    if (card) openDetail(card.dataset.id);
  });
  $('q').addEventListener('input', () => {
    clearTimeout(listTimer);
    listTimer = setTimeout(loadList, PARSE_DEBOUNCE_MS);
  });
  $('status').onchange = loadList;

  $('settlements').addEventListener('click', onSettlementClick);
  $('btn-import').onclick = runImport;

  $('detail-close').onclick = closeDetail;
  $('detail').addEventListener('click', (event) => {
    // 바깥 어두운 영역을 누르면 닫는다.
    if (event.target.id === 'detail') closeDetail();
  });

  window.addEventListener('online', flushQueue);
  renderQueueBadge();
  flushQueue();
  loadRecent();

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch((error) => console.debug('sw 등록 실패', error));
  }
}

init();
