// ═══════════════════════════════════════════════════════════════════════
//  모두의 교회 웹앱 — 안드로이드 앱과 같은 서버, 같은 데이터를 씁니다.
//  아이폰은 사파리에서 '홈 화면에 추가' 하면 앱처럼 씁니다.
// ═══════════════════════════════════════════════════════════════════════

const LS = {
  get: (k, d = null) => { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
  del: (k) => { try { localStorage.removeItem(k); } catch {} },
};

const DEFAULT_BIBLE = 'https://sum.su.or.kr:8888/bible/today';

const S = {
  token: LS.get('token'),
  me: null,
  stack: [],          // 뒤로가기용
  view: null,
  cells: [],
};

// ── 서버 호출 ────────────────────────────────────────────────────────────
// 읽기 전용 목록(나눔·출석표·기도제목·공지·주보 등)은 잠깐(1분) 기억해 둡니다.
// 그래서 다음에 누를 만한 화면의 데이터를 미리 받아 두면(prefetch) 눌렀을 때 바로 뜹니다.
//  · 같은 주소를 이미 받는 중이면 그 요청을 같이 기다립니다
//  · 무엇이든 저장·수정(POST)하면 기억해 둔 것을 비웁니다 (읽음 표시 등은 관련된 것만)
const CACHE_MS = 60 * 1000;
const CACHEABLE = ['/api/entries/feed', '/api/entries/person', '/api/entries/mine', '/api/entries/attendance',
  '/api/entries/comments', '/api/prayers', '/api/notices', '/api/bulletins', '/api/cells', '/api/push/config'];
const CACHE = new Map();     // url → { at, p: Promise<text> }
let cacheGen = 0;
function clearApiCache(match) {
  if (!match) { cacheGen++; CACHE.clear(); return; }
  for (const k of [...CACHE.keys()]) if (k.includes(match)) CACHE.delete(k);
}
function invalidateAfter(path) {
  if (path === '/api/notices/read') return clearApiCache('/api/notices');
  if (path === '/api/push/prefs') return clearApiCache('/api/push/config');
  if (['/api/push/register', '/api/push/unregister', '/api/notifications/read'].includes(path)) return;
  clearApiCache();
}
function apiUrl(path, params) {
  const url = new URL(path, location.origin);
  if (params) for (const [k, v] of Object.entries(params))
    if (v !== null && v !== undefined && v !== '') url.searchParams.set(k, v);
  return url;
}
async function fetchText(url, method, body) {
  const res = await fetch(url, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(S.token ? { Authorization: 'Bearer ' + S.token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) {
    let data = {}; try { data = text ? JSON.parse(text) : {}; } catch {}
    throw new Error(data.error || `서버에 연결하지 못했습니다 (${res.status})`);
  }
  return text;
}
function cachedText(url) {
  const key = url.toString();
  const hit = CACHE.get(key);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.p;
  const gen = cacheGen;
  const entry = { at: Date.now(), p: null };
  entry.p = fetchText(url, 'GET', null).then(
    (t) => { if (gen !== cacheGen && CACHE.get(key) === entry) CACHE.delete(key); return t; },
    (e) => { if (CACHE.get(key) === entry) CACHE.delete(key); throw e; });
  CACHE.set(key, entry);
  return entry.p;
}
async function api(path, { method = 'GET', body = null, params = null } = {}) {
  const url = apiUrl(path, params);
  let text;
  if (method === 'GET' && CACHEABLE.includes(path)) text = await cachedText(url);
  else {
    try { text = await fetchText(url, method, body); }
    finally { if (method !== 'GET') invalidateAfter(path); }
  }
  try { return text ? JSON.parse(text) : {}; } catch { return {}; }
}
/** 결과는 버리고 기억만 해 둡니다. 실패해도 조용히 넘어갑니다. */
function prefetch(path, params = null) {
  if (!S.token || !CACHEABLE.includes(path)) return;
  cachedText(apiUrl(path, params)).catch(() => {});
}

// ── 날짜 ────────────────────────────────────────────────────────────────
const pad = (n) => String(n).padStart(2, '0');
const todayStr = () => { const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`; };
function kdate(s) {
  if (!s) return '';
  const [y, m, d] = s.slice(0, 10).split('-').map(Number);
  const dow = ['일','월','화','수','목','금','토'][new Date(y, m-1, d).getDay()];
  return `${m}월 ${d}일 (${dow})`;
}
const kfull = (s) => { const [y,m,d] = (s||'').slice(0,10).split('-'); return `${+y}년 ${+m}월 ${+d}일`; };
const shortTime = (s) => (s || '').slice(5, 16).replace(/-/g, '/');
function shiftDate(s, n) {
  const d = new Date(s + 'T00:00:00'); d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;
}

// ── 화면 그리기 도우미 ────────────────────────────────────────────────────
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
const nl = (s) => esc(s).replace(/\n/g, '<br>');
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

// ── 시니어 모드 ───────────────────────────────────────────────────────────
//   켜면: 예전의 큰 글씨·큰 버튼 화면 (아래 탭 없음)
//   끄면: 아래 탭 다섯 개 (더보기 · 알림 · 홈 · 나눔광장 · 설정) — 기본값
const isSenior = () => LS.get('senior', '0') === '1';
const defaultFs = () => (isSenior() ? '1' : '0.85');   // 새 화면은 글씨 '가장 작게'가 기본
const TAB_VIEWS = ['more', 'alerts', 'home', 'feed', 'settings'];
const inTabs = () => !isSenior() && S.view && TAB_VIEWS.includes(S.view.view);

function head(title, { back = true, home = false, settings = false } = {}) {
  // 아래 탭이 있는 화면에서는 뒤로·홈·설정 단추가 필요 없습니다
  if (inTabs()) { back = false; home = false; settings = false; }
  return `<header>
    ${back ? '<button class="iconbtn" data-act="back">← 뒤로</button>' : ''}
    <h1>${esc(title)}</h1>
    ${settings ? '<button class="iconbtn" data-act="settings">설정</button>' : ''}
    ${home ? '<button class="iconbtn" data-act="home">홈</button>' : ''}
  </header>`;
}

const msg = (t, ok) => t ? `<div class="msg ${ok ? 'ok' : ''}">${nl(t)}</div>` : '';
const spin = (t = '불러오는 중입니다') => `<div class="spin">${esc(t)}…</div>`;
const empty = (t) => `<div class="empty">${nl(t)}</div>`;

function go(view, params = {}, replace = false) {
  if (!replace && S.view) S.stack.push(S.view);
  S.view = { view, params };
  render();
}
function back() {
  const prev = S.stack.pop();
  S.view = prev || { view: 'home', params: {} };
  render();
}
function goHome() { S.stack = []; S.view = { view: 'home', params: {} }; render(); }

function applyFont() {
  document.documentElement.style.setProperty('--fs', LS.get('fs', defaultFs()));
}

// ── 화면 등록소 ──────────────────────────────────────────────────────────
const VIEWS = {};

// 교회의 모든 셀에 속한 분(목사님 등)은 '우리 셀'과 '전체'가 같으므로 나누지 않습니다
function inAllCells() {
  const me = S.me, cells = S.cells || [];
  return !!me && cells.length > 0 && cells.every((c) => (me.cellIds || []).includes(c.id));
}
// '우리 셀 / 전체' 두 칸 — 모든 셀에 속한 분에게는 그리지 않습니다
function scopeToggle(all, leftLabel, rightLabel) {
  if (inAllCells()) return '';
  return `<div class="toggle">
    <button data-act="scope" data-v="0" class="${!all ? 'on' : ''}">${esc(leftLabel)}</button>
    <button data-act="scope" data-v="1" class="${all ? 'on' : ''}">${esc(rightLabel)}</button>
  </div>`;
}

// ── 셀 고르기: 가로 한 줄 칩, 옆으로 밀어서 ─────────────────────────────────
function chipsHtml(cells, picked, group) {
  if (!cells.length) return '<div class="meta" style="padding:6px 0">아직 만든 셀이 없습니다</div>';
  const allOn = cells.every((c) => picked.includes(c.id));
  return `<div class="chips" data-group="${group}">
    ${cells.length > 1 ? `<button type="button" class="chip ${allOn ? 'on' : ''}" data-chipall="${group}">전체</button>` : ''}
    ${cells.map((c) => `<button type="button" class="chip ${picked.includes(c.id) ? 'on' : ''}"
        data-chip="${group}" data-v="${c.id}">${esc(c.name)}</button>`).join('')}
  </div>`;
}
const chipsValue = (group) => $$(`[data-chip="${group}"].on`).map((b) => +b.dataset.v);
function syncChipAll(group) {
  const all = $(`[data-chipall="${group}"]`); if (!all) return;
  all.classList.toggle('on', $$(`[data-chip="${group}"]`).every((b) => b.classList.contains('on')));
}

// ── 사진 ────────────────────────────────────────────────────────────────
function photosHtml(images) {
  if (!images || !images.length) return '';
  return `<div class="photos ${images.length === 1 ? 'one' : ''}">${images.map((im) =>
    `<img src="${esc(im.url)}" alt="묵상 사진" loading="lazy" data-full="${esc(im.url)}">`).join('')}</div>`;
}
function openViewer(url) {
  const v = document.createElement('div');
  v.className = 'viewer';
  v.innerHTML = `<img src="${esc(url)}" alt="사진 크게 보기"><div>화면을 누르면 닫힙니다</div>`;
  v.onclick = () => v.remove();
  document.body.appendChild(v);
}

// 묵상 사진용으로 줄이기 — 긴 변 1600px, JPEG, 1MB 이하. 세로 사진이 눕지 않게 회전 정보 반영
async function shrinkForPost(file, maxSide = 1600, maxBytes = 1024 * 1024) {
  let bmp;
  try { bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }); }
  catch { bmp = await createImageBitmap(file); }
  const scale = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  const cv = document.createElement('canvas');
  cv.width = Math.round(bmp.width * scale); cv.height = Math.round(bmp.height * scale);
  const g = cv.getContext('2d');
  g.fillStyle = '#fff'; g.fillRect(0, 0, cv.width, cv.height);   // 투명 PNG 가 까맣게 되지 않게
  g.drawImage(bmp, 0, 0, cv.width, cv.height);
  let q = 0.82, blob;
  do {
    blob = await new Promise((r) => cv.toBlob(r, 'image/jpeg', q));
    q -= 0.08;
  } while (blob && blob.size > maxBytes && q > 0.5);
  return blob;
}

// ═══════════════════════════════════════════════════════════════════════
//  한 칸씩 차례로 적는 입력 틀
//   · 맨 위에 지금 적을 칸 하나만. '다음'(키보드 완료·엔터)을 누르면 값은
//     아래로 내려가고 맨 위가 다음 질문으로 바뀝니다.
//   · 입력칸을 새로 만들지 않고 글자용·숫자용 두 칸을 계속 다시 써서
//     키보드가 닫혔다 열리지 않습니다.
//   · 아래로 내려간 줄을 누르면 그 질문으로 돌아가 고칠 수 있습니다.
// ═══════════════════════════════════════════════════════════════════════
const SF = { steps: [], values: {}, cur: 0, finishLabel: '', onFinish: null, busy: false };

function stepFormHtml() {
  return `<div id="sf">
    <div class="meta" id="sf-prog"></div>
    <div class="step-q" id="sf-q"></div>
    <div class="hint" id="sf-hint" style="font-size:calc(15px*var(--fs));margin-bottom:12px"></div>
    <div id="sf-custom"></div>
    <input id="sf-text" class="step-in" autocomplete="off" enterkeyhint="next">
    <input id="sf-num" class="step-in" type="password" inputmode="numeric" pattern="[0-9]*"
      autocomplete="off" enterkeyhint="next" style="display:none">
    <div id="sf-err"></div>
    <div class="row" style="margin-top:12px">
      <button class="btn plain" id="sf-prev" style="flex:0.45">이전</button>
      <button class="btn" id="sf-next">다음</button>
    </div>
    <div id="sf-done" style="margin-top:24px"></div>
  </div>`;
}

function sfInput(step) { return step && (step.numeric ? $('#sf-num') : $('#sf-text')); }

function sfShow(focus = true) {
  const st = SF.steps[SF.cur];
  const last = SF.cur === SF.steps.length - 1;
  $('#sf-prog').textContent = `${SF.cur + 1} / ${SF.steps.length}`;
  $('#sf-q').textContent = st.question;
  $('#sf-hint').textContent = st.hint || '';
  $('#sf-err').innerHTML = '';
  const txt = $('#sf-text'), num = $('#sf-num');
  if (st.custom) {
    $('#sf-custom').innerHTML = st.custom(SF.values[st.key] || '');
    if (st.mount) st.mount();
    txt.style.display = 'none'; num.style.display = 'none';
  } else {
    $('#sf-custom').innerHTML = '';
    const inp = sfInput(st), other = st.numeric ? txt : num;
    inp.value = SF.values[st.key] || '';
    inp.maxLength = st.maxLen || 60;
    inp.type = st.password ? 'password' : (st.numeric ? 'password' : 'text');
    inp.setAttribute('enterkeyhint', last ? 'done' : 'next');
    inp.style.display = 'block';
    // 새 칸에 먼저 초점을 옮긴 뒤 다른 칸을 숨깁니다 (키보드가 닫히지 않게)
    if (focus) { try { inp.focus({ preventScroll: true }); inp.setSelectionRange(inp.value.length, inp.value.length); } catch {} }
    other.style.display = 'none';
  }
  $('#sf-prev').style.display = SF.cur > 0 ? 'block' : 'none';
  $('#sf-next').textContent = SF.busy ? '잠시만요…' : last ? SF.finishLabel
    : (st.optional && !(SF.values[st.key] || '').trim()) ? '건너뛰기' : '다음';
  $('#sf-next').disabled = SF.busy;
  $('#sf-done').innerHTML = SF.steps.slice(0, SF.cur).map((s2, i) => ({ s2, i })).reverse()
    .map(({ s2, i }) => {
      const v = SF.values[s2.key] || '';
      return `<div class="done-row" data-sfgo="${i}"><span class="k">${esc(s2.name)}</span>
        <span class="v">${v ? (s2.password ? '●'.repeat(v.length) : esc(v)) : '(비워 둠)'}</span>
        <span class="e">고치기</span></div>`;
    }).join('');
}

function sfError(t) { $('#sf-err').innerHTML = msg(t); }

function sfAdvance() {
  if (SF.busy) return;
  const st = SF.steps[SF.cur];
  const inp = st.custom ? null : sfInput(st);
  if (inp) SF.values[st.key] = inp.value;
  const v = (SF.values[st.key] || '').trim();
  if (!st.optional && !v) return sfError(`${st.name}을(를) 적어 주세요`);
  const bad = st.validate ? st.validate(v, SF.values) : null;
  if (bad) return sfError(bad);
  if (SF.cur === SF.steps.length - 1) return SF.onFinish(SF.values);
  SF.cur++;
  const nx = SF.steps[SF.cur];
  if (!(SF.values[nx.key] || '').trim() && nx.prefill) SF.values[nx.key] = nx.prefill(SF.values) || '';
  sfShow();
}

function stepFormMount({ steps, finishLabel, onFinish }) {
  Object.assign(SF, { steps, values: {}, cur: 0, finishLabel, onFinish, busy: false });
  const onKey = (e) => {
    if (e.key === 'Enter' || e.keyCode === 13) { e.preventDefault(); sfAdvance(); }
  };
  const onIn = (e) => {
    const st = SF.steps[SF.cur];
    if (st.numeric) e.target.value = e.target.value.replace(/\D/g, '').slice(0, st.maxLen || 6);
    SF.values[st.key] = e.target.value;
    $('#sf-err').innerHTML = '';
  };
  ['#sf-text', '#sf-num'].forEach((sel) => {
    $(sel).addEventListener('keydown', onKey);
    $(sel).addEventListener('input', onIn);
  });
  // 버튼을 눌러도 입력칸의 초점을 빼앗지 않게 (키보드 유지)
  ['#sf-next', '#sf-prev'].forEach((sel) => $(sel).addEventListener('mousedown', (e) => e.preventDefault()));
  $('#sf-next').onclick = () => sfAdvance();
  $('#sf-prev').onclick = () => {
    const st = SF.steps[SF.cur]; const inp = st.custom ? null : sfInput(st);
    if (inp) SF.values[st.key] = inp.value;
    if (SF.cur > 0) { SF.cur--; sfShow(); }
  };
  $('#sf-done').onclick = (e) => {
    const row = e.target.closest('[data-sfgo]'); if (!row || SF.busy) return;
    const st = SF.steps[SF.cur]; const inp = st.custom ? null : sfInput(st);
    if (inp) SF.values[st.key] = inp.value;
    SF.cur = +row.dataset.sfgo; sfShow();
  };
  sfShow(!steps[0].custom);
}
function sfBusy(b, err) {
  SF.busy = b; sfShow(false);
  if (err) sfError(err);
}

// ── 주소 찾기 (카카오 우편번호 · /postcode.html 을 창으로 띄움) ─────────────
function openAddressSearch(onPick) {
  const sh = document.createElement('div');
  sh.className = 'sheet';
  sh.innerHTML = `<header><h1>교회 주소 찾기</h1><button class="iconbtn" id="sh-close">닫기</button></header>
    <p class="hint" style="margin:0 16px 8px">도로명·건물 이름·지번으로 찾은 뒤 목록에서 눌러 주세요.</p>
    <iframe src="/postcode.html" title="주소 찾기"></iframe>`;
  const onMsg = (e) => {
    if (e.origin !== location.origin || e.data?.type !== 'woori-address') return;
    close(); onPick(e.data.data);
  };
  const close = () => { window.removeEventListener('message', onMsg); sh.remove(); };
  window.addEventListener('message', onMsg);
  document.body.appendChild(sh);
  $('#sh-close', sh).onclick = close;
}

// ── 알림 ────────────────────────────────────────────────────────────────
const b64ToBytes = (b64) => {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4);
  const raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
};

// 서비스워커가 서버에 물어볼 수 있게 토큰을 건네 줍니다
function tellWorkerToken() {
  navigator.serviceWorker?.ready
    .then((r) => r.active?.postMessage({ type: 'token', token: S.token }))
    .catch(() => {});
}

async function pushOn(vapidKey) {
  if (!('serviceWorker' in navigator) || !('PushManager' in window))
    throw new Error('이 브라우저는 알림을 지원하지 않습니다.');
  const perm = await Notification.requestPermission();
  if (perm !== 'granted') throw new Error('알림이 거부되어 있습니다. 브라우저 설정에서 허용해 주세요.');
  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) sub = await reg.pushManager.subscribe({
    userVisibleOnly: true, applicationServerKey: b64ToBytes(vapidKey),
  });
  await api('/api/push/register', { method: 'POST', body: { kind: 'web', token: sub.endpoint } });
  tellWorkerToken();
}

async function pushOff() {
  const reg = await navigator.serviceWorker?.ready;
  const sub = await reg?.pushManager.getSubscription();
  if (sub) {
    await api('/api/push/unregister', { method: 'POST', body: { token: sub.endpoint } }).catch(() => {});
    await sub.unsubscribe().catch(() => {});
  }
}

// 서버가 주는 me 에 편의 속성을 붙입니다
function setMe(me) {
  if (me) {
    me.canManage = me.role === 'owner' || me.role === 'admin';
    me.isOwner = me.role === 'owner';
    me.canPickCells = me.shareScope === 'all_cells';
  }
  S.me = me;
  return me;
}

// 홈 화면 데이터를 기기에 잠깐 저장해 두고, 다음에 열 때 곧바로 보여 줍니다.
// (보여 준 뒤 뒤에서 조용히 새로 받아 갱신합니다)
function saveBoot(b) {
  S.home = b.home; S.cells = b.cells || []; S.progress = b.progress;
  setMe(b.me);
  try { LS.set('boot', JSON.stringify({ ...b, at: Date.now() })); } catch {}
}
function loadBoot() {
  const raw = LS.get('boot'); if (!raw) return null;
  try {
    const b = JSON.parse(raw);
    if (Date.now() - (b.at || 0) > 12 * 3600 * 1000) return null;  // 반나절 지나면 버림
    return b;
  } catch { return null; }
}

async function render() {
  applyFont();
  const app = $('#app');
  const v = S.view;
  const fn = VIEWS[v.view];
  if (!fn) { app.innerHTML = head('오류') + empty('없는 화면입니다.'); return; }
  app.innerHTML = spin();
  try {
    app.innerHTML = await fn.html(v.params);
  } catch (e) {
    app.innerHTML = head(' ') + msg(e.message);
  }
  drawTabs();
  window.scrollTo(0, 0);
  if (fn.after) fn.after(v.params);
  prefetchNext();
}

// ── 아래 탭 막대 ─────────────────────────────────────────────────────────
const ICON = {
  apps: '<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>',
  bell: '<path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
  home: '<path d="M4 11.5 12 5l8 6.5"/><path d="M6.5 10v9.5h11V10"/><path d="M10 19.5v-5h4v5"/>',
  chat: '<path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4.5 3.5V16A2.5 2.5 0 0 1 4 13.5z"/>',
  menu: '<path d="M5 7h14M5 12h14M5 17h14"/>',
  book: '<path d="M4 5.5c2.5-1 5.5-1 8 .8v13c-2.5-1.8-5.5-1.8-8-.8z"/><path d="M20 5.5c-2.5-1-5.5-1-8 .8v13c2.5-1.8 5.5-1.8 8-.8z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
  video: '<rect x="3.5" y="6.5" width="12" height="11" rx="2"/><path d="m15.5 10.5 5-3v9l-5-3"/>',
  heart: '<path d="M12 19s-7-4.4-7-9.2A3.8 3.8 0 0 1 12 7.6a3.8 3.8 0 0 1 7 2.2C19 14.6 12 19 12 19z"/>',
  check: '<circle cx="12" cy="12" r="8.5"/><path d="m8.5 12.2 2.4 2.4 4.8-4.9"/>',
  pie: '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5V12l6 6M12 12l-7.4 4.2"/>',
  cal: '<rect x="4" y="5.5" width="16" height="14.5" rx="2"/><path d="M4 10h16M8.5 3.5v4M15.5 3.5v4"/>',
  search: '<circle cx="11" cy="11" r="6"/><path d="m15.5 15.5 4 4"/>',
  users: '<circle cx="9" cy="9" r="3.2"/><path d="M3.5 19c.6-3 2.8-4.6 5.5-4.6s4.9 1.6 5.5 4.6"/><circle cx="16.5" cy="8.5" r="2.6"/><path d="M15.5 13.6c2.5-.2 4.4 1.3 5 4.4"/>',
  mega: '<path d="M4 10v4h3l7 4V6L7 10z"/><path d="M17.5 9.5a3.5 3.5 0 0 1 0 5"/>',
  person: '<circle cx="10" cy="8.5" r="3.5"/><path d="M3.5 19.5c.7-3.4 3.3-5.3 6.5-5.3"/><path d="M17 13v6M14 16h6"/>',
  chev: '<path d="m9.5 6 6 6-6 6"/>',
};
const svg = (name, size = 24) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none"
  stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[name]}</svg>`;

function drawTabs() {
  let bar = $('#tabbar');
  if (!inTabs()) { if (bar) bar.remove(); document.body.classList.remove('tabs'); return; }
  document.body.classList.add('tabs');
  if (!bar) { bar = document.createElement('nav'); bar.id = 'tabbar'; document.body.appendChild(bar); }
  bar.classList.remove('mini'); lastY = 0;
  const cur = S.view.view;
  const dot = (S.home?.unreadNoticeCount || 0) > 0;
  const item = (v, icon, label, extra = '') => `<button class="tab ${cur === v ? 'on' : ''}" data-tab="${v}">
      <span class="ti">${svg(icon)}${extra}</span><span class="tl">${label}</span></button>`;
  bar.innerHTML = item('more', 'apps', '더보기') +
    item('alerts', 'bell', '알림', dot ? '<i class="tdot"></i>' : '') +
    `<button class="tab home ${cur === 'home' ? 'on' : ''}" data-tab="home"><span class="hc">${svg('home', 28)}</span></button>` +
    item('feed', 'chat', '나눔광장') + item('settings', 'menu', '설정');
  bar.onclick = (e) => {
    const b = e.target.closest('[data-tab]'); if (!b) return;
    S.stack = []; S.view = { view: b.dataset.tab, params: {} }; render();
  };
}

// ── 미리 가져오기 ────────────────────────────────────────────────────────
// 화면 이름 → 그 화면이 처음 열릴 때 부르는 주소 (각 화면의 api(...) 호출과 똑같아야 합니다)
const PREFETCH = {
  feed: (p = {}) => {
    const tab = p.tab || (inAllCells() ? 'all' : 'cell');
    if (tab === 'mine') return [['/api/entries/person', { memberId: S.me.id }]];
    return [['/api/entries/feed', { date: p.date || todayStr(), scope: tab }]];
  },
  write: (p = {}) => {
    const d = p.date || todayStr();
    return [['/api/entries/mine', { from: d, to: d }], ...(S.me.shareScope === 'all_cells' && !S.cells.length ? [['/api/cells']] : [])];
  },
  person: (p = {}) => [['/api/entries/person', { memberId: p.memberId, month: p.month || 'all', day: p.day || 'all' }]],
  attendance: (p = {}) => {
    const cellId = p.cellId !== undefined ? p.cellId : ((S.me.cellIds || [])[0] || 0);
    return [['/api/entries/attendance', { scope: cellId ? 'cell' : 'all', range: 'year', cellId: cellId || null }]];
  },
  prayer: (p = {}) => [['/api/prayers', { scope: (p.all || inAllCells()) ? 'all' : 'cell' }]],
  notice: () => [['/api/notices']],
  bulletin: () => [['/api/bulletins']],
  alerts: (p = {}) => [[(p.sub || 'notice') === 'bulletin' ? '/api/bulletins' : '/api/notices']],
};
function prefetchView(view, params) {
  try { const f = PREFETCH[view]; if (f && S.me) f(params || {}).forEach(([path, q]) => prefetch(path, q)); } catch {}
}
// 지금 화면에서 다음에 누를 만한 화면들
const NEXT = {
  home: () => { prefetchView('feed'); prefetchView('write'); prefetchView('person', { memberId: S.me.id });
    prefetchView('notice'); prefetchView('prayer'); },
  more: () => { prefetchView('prayer'); prefetchView('attendance'); },
  alerts: () => { prefetchView('notice'); prefetchView('bulletin'); },
  feed: () => { const p = S.view.params || {};
    prefetchView('attendance'); prefetchView('feed', { tab: 'mine' });
    if (p.tab !== 'mine') {
      prefetchView('feed', { date: shiftDate(p.date || todayStr(), -1), tab: p.tab });
      if (!inAllCells()) prefetchView('feed', { date: p.date, tab: p.tab === 'all' ? 'cell' : 'all' });
    } },
  settings: () => prefetch('/api/push/config'),
};
function prefetchNext() {
  const f = NEXT[S.view?.view];
  if (!f || !S.me) return;
  // 화면이 다 그려지고 한숨 돌린 뒤에
  (window.requestIdleCallback || ((cb) => setTimeout(cb, 120)))(() => { try { f(); } catch {} });
}
// 손가락이 닿는 순간(떼기 전) 그 버튼이 열 화면의 데이터를 먼저 받기 시작합니다
document.addEventListener('pointerdown', (e) => {
  const t = e.target.closest('[data-tab],[data-act="go"],[data-act="myrecord"],[data-act="tab"]');
  if (!t || !S.me) return;
  const d = t.dataset;
  if (d.tab || d.act === 'tab') prefetchView(d.tab || d.v);
  else if (d.act === 'myrecord') prefetchView('person', { memberId: S.me.id });
  else { let p = {}; try { p = JSON.parse(d.params || '{}'); } catch {} prefetchView(d.view, p); }
}, { passive: true });

// ── 아래 탭 막대: 아래로 읽으면 작게, 다시 올리면 크게 ─────────────────────────
let lastY = 0;
window.addEventListener('scroll', () => {
  const bar = $('#tabbar'); const y = window.scrollY;
  if (bar) {
    if (y < 24 || y < lastY - 6) bar.classList.remove('mini');
    else if (y > lastY + 6) bar.classList.add('mini');
  }
  if (Math.abs(y - lastY) > 6 || y < 24) lastY = y;
}, { passive: true });

// ── 버튼 누르는 맛 (모든 버튼) ────────────────────────────────────────────
//   누르는 순간: 지수 곡선(easeOutExpo)으로 '훅' 커지고
//   손을 떼면: 천천히 부드럽게 원래 크기로
//   큰 버튼은 조금, 작은 버튼은 많이 (커지는 양을 화면 픽셀로 맞춤)
const PRESSABLE = 'button, .tool, .menu, .chip, [data-act], [data-tab], img[data-full]';
const EXPO_OUT = 'cubic-bezier(0.16, 1, 0.3, 1)';
let pressed = null;
function pressScale(el) {
  const r = el.getBoundingClientRect();
  return 1 + Math.min(0.14, Math.max(0.03, 14 / Math.max(r.width, r.height, 1)));
}
function baseTransform(el) {
  if (el._bounce) { el._bounce.cancel(); el._bounce = null; }
  const t = getComputedStyle(el).transform;
  return t && t !== 'none' ? t + ' ' : '';
}
function pressDown(el) {
  if (el.disabled || el.closest('[disabled]')) return;
  const base = baseTransform(el), s = pressScale(el);
  el._press = { base, s, at: performance.now() };
  el._bounce = el.animate([{ transform: base + 'scale(1)' }, { transform: base + `scale(${s})` }],
    { duration: 280, easing: EXPO_OUT, fill: 'forwards' });
  pressed = el;
}
function pressUp(el) {
  if (!el || !el._press) return;
  const { base, s, at } = el._press; el._press = null;
  // 아주 짧게 '톡' 눌러도 끝까지 커졌다가 놓이도록
  const quick = performance.now() - at < 140;
  const from = quick ? base + `scale(${s})`
    : (getComputedStyle(el).transform === 'none' ? base + 'scale(1)' : getComputedStyle(el).transform);
  if (el._bounce) el._bounce.cancel();
  // 떼면 천천히 부드럽게 원래 크기로
  const a = el.animate([{ transform: from }, { transform: base + 'scale(1)' }],
    { duration: 420, easing: 'cubic-bezier(0.25, 0.1, 0.25, 1)', delay: quick ? 40 : 0, fill: 'backwards' });
  el._bounce = a;
  a.onfinish = () => { if (el._bounce === a) el._bounce = null; };
}
document.addEventListener('pointerdown', (e) => {
  if (e.button > 0) return;
  const el = e.target.closest(PRESSABLE);
  if (!el || el.matches('input, textarea, select') || el.closest('.sheet, .viewer')) return;
  pressDown(el);
}, { passive: true });
const release = () => { const el = pressed; pressed = null; pressUp(el); };
document.addEventListener('pointerup', release, { passive: true });
document.addEventListener('pointercancel', release, { passive: true });   // 스크롤이 시작되면 놓아 줌
window.addEventListener('blur', release);

// 전역 클릭 처리
document.addEventListener('click', async (e) => {
  const chip = e.target.closest('[data-chip]');
  if (chip) { chip.classList.toggle('on'); syncChipAll(chip.dataset.chip); chip.dispatchEvent(new Event('chipchange', { bubbles: true })); return; }
  const chipAll = e.target.closest('[data-chipall]');
  if (chipAll) {
    const g = chipAll.dataset.chipall; const on = !chipAll.classList.contains('on');
    $$(`[data-chip="${g}"]`).forEach((b) => b.classList.toggle('on', on));
    chipAll.classList.toggle('on', on);
    chipAll.dispatchEvent(new Event('chipchange', { bubbles: true }));
    return;
  }
  const full = e.target.closest('img[data-full]');
  if (full) return openViewer(full.dataset.full);
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const act = el.dataset.act;
  if (act === 'back') return back();
  if (act === 'home') return goHome();
  if (act === 'settings') return go('settings');
  if (act === 'go') return go(el.dataset.view, JSON.parse(el.dataset.params || '{}'));
  const fn = VIEWS[S.view.view];
  if (fn && fn.click) await fn.click(act, el, e);
});

// ═══════════════════════════════════════════════════════════════════════
//  교회 고르기
// ═══════════════════════════════════════════════════════════════════════
VIEWS.pick = {
  html() {
    const last = LS.get('lastChurchName');
    return head('우리 교회 찾기', { back: false }) + `
      <p class="meta">다니시는 교회를 찾아 주세요.</p>
      ${last ? `<div class="card">
        <div class="meta">지난번에 쓰신 교회</div>
        <div style="font-size:calc(21px*var(--fs));font-weight:700;margin:6px 0 12px">${esc(last)}</div>
        <button class="btn" data-act="lastchurch">이 교회로 들어가기</button>
      </div>` : ''}
      <label class="f"><span>교회 이름 또는 주소</span>
        <input id="q" autocomplete="off" placeholder="예) 은혜교회"></label>
      <div id="results"></div>
      <h2 class="sec">교회가 목록에 없나요?</h2>
      <button class="btn soft" data-act="register">우리 교회 가입하기
        <small>교회 정보 네 가지만 적으면 됩니다</small></button>`;
  },
  after() {
    let t;
    $('#q').addEventListener('input', (e) => {
      clearTimeout(t);
      const q = e.target.value.trim();
      if (!q) { $('#results').innerHTML = ''; return; }
      t = setTimeout(async () => {
        $('#results').innerHTML = spin('찾는 중입니다');
        try {
          const { churches } = await api('/api/churches/search', { params: { q } });
          $('#results').innerHTML = churches.length
            ? churches.map((c) => `<div class="card">
                <div style="font-size:calc(20px*var(--fs));font-weight:700">${esc(c.name)}</div>
                ${c.address ? `<div class="meta">${esc(c.address)}</div>` : ''}
                ${c.denomination ? `<div class="meta">${esc(c.denomination)}</div>` : ''}
                <div style="height:12px"></div>
                <button class="btn" data-act="choose" data-id="${c.id}" data-name="${esc(c.name)}">이 교회 선택</button>
              </div>`).join('')
            : empty('찾으시는 교회가 없습니다.\n아래에서 교회를 새로 등록할 수 있습니다.');
        } catch (err) { $('#results').innerHTML = msg(err.message); }
      }, 350);
    });
  },
  click(act, el) {
    if (act === 'choose') {
      LS.set('lastChurchId', el.dataset.id); LS.set('lastChurchName', el.dataset.name);
      go('login', { churchId: +el.dataset.id, churchName: el.dataset.name });
    }
    if (act === 'lastchurch')
      go('login', { churchId: +LS.get('lastChurchId'), churchName: LS.get('lastChurchName') });
    if (act === 'register') go('register');
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  교회 등록 신청
// ═══════════════════════════════════════════════════════════════════════
// 주소 찾기 → 교회 이름 → 교단 → 담임목사 성함 → 신청하시는 분 → 비밀번호, 한 칸씩.
// 주소를 맨 앞에 두면 그 뒤로는 키보드가 한 번도 닫히지 않습니다.
// 신청하신 분은 이 교회의 '관리자'가 되어 담임목사님과 같은 권한을 가집니다.
VIEWS.register = {
  html() {
    return head('우리 교회 등록') + `
      <p class="meta" style="margin-top:0">교회 정보를 확인한 뒤 승인해 드립니다.</p>
      ${stepFormHtml()}`;
  },
  after() {
    const addrHtml = (v) => `
      ${v ? `<div class="card"><div class="meta">찾은 주소</div>
        <div style="font-weight:600;font-size:calc(19px*var(--fs))">${esc(v)}</div></div>` : ''}
      <button class="btn soft" id="addr-open">${v ? '다시 찾기' : '주소 찾기<small>도로명이나 건물 이름으로 찾습니다</small>'}</button>
      <div id="addr-manual" style="margin-top:10px"></div>
      <button class="iconbtn" id="addr-type" style="width:100%;color:var(--muted)">검색이 잘 안 되면 직접 적기</button>`;
    const addrMount = () => {
      const pick = () => openAddressSearch((d) => {
        SF.values.address = d.address;
        if (!(SF.values.church || '').trim() && (d.building || '').includes('교회')) SF.values.church = d.building;
        sfShow(false); sfAdvance();
      });
      $('#addr-open').onclick = pick;
      $('#addr-type').onclick = () => {
        $('#addr-manual').innerHTML = `<input id="addr-in" placeholder="교회 주소" value="${esc(SF.values.address || '')}">`;
        const inp = $('#addr-in'); inp.focus();
        inp.oninput = () => { SF.values.address = inp.value; };
        inp.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); sfAdvance(); } };
      };
    };
    stepFormMount({
      finishLabel: '등록 신청하기',
      steps: [
        { key: 'address', name: '주소', question: '교회 주소를 찾아 주세요', custom: addrHtml, mount: addrMount },
        { key: 'church', name: '교회 이름', question: '교회 이름을 적어 주세요', hint: '예) 은혜교회' },
        { key: 'denom', name: '교단', question: '소속 교단을 적어 주세요', hint: '예) 예장합동, 예장통합, 기감, 기장, 기침 …' },
        { key: 'pastor', name: '담임목사', question: '담임목사님 성함을 적어 주세요', maxLen: 30 },
        { key: 'applicant', name: '신청하신 분', question: '신청하시는 분의 이름을 적어 주세요', maxLen: 30,
          hint: '이분이 교회 관리자가 됩니다. 목사님이 직접 하시면 목사님 성함 그대로 두세요.',
          prefill: (v) => v.pastor },
        { key: 'pin', name: '비밀번호', question: '들어올 때 쓸 숫자 6자리를 정해 주세요',
          numeric: true, password: true, maxLen: 6,
          validate: (v) => v.length !== 6 ? '숫자 6자리여야 합니다' : null },
        { key: 'pin2', name: '비밀번호 확인', question: '같은 숫자 6자리를 한 번 더 적어 주세요',
          numeric: true, password: true, maxLen: 6,
          validate: (v, all) => v !== all.pin ? '위에서 정한 비밀번호와 다릅니다' : null },
      ],
      onFinish: async (v) => {
        sfBusy(true);
        try {
          await api('/api/churches/register', { method: 'POST', body: {
            churchName: v.church.trim(), address: v.address.trim(), denomination: v.denom.trim(),
            pastorName: v.pastor.trim(), applicantName: v.applicant.trim(), pinNumber: v.pin,
          }});
          $('#app').innerHTML = head('신청 완료') + `<div class="card">
            <div style="font-size:calc(22px*var(--fs));font-weight:700">등록 신청이 접수되었습니다</div>
            <p>교회 정보를 확인한 뒤 승인해 드립니다. 보통 1~2일 걸립니다.<br>
            승인되면 ${esc(v.applicant.trim())} 이름과 방금 정한 비밀번호로 들어오실 수 있습니다.</p>
            <p class="meta">신청하신 분은 이 교회의 관리자가 됩니다. 성도 가입 승인과 직분 지정을 맡아 주세요.</p>
          </div><button class="btn" data-act="home2">처음 화면으로</button>`;
          $('[data-act="home2"]').onclick = () => { S.stack = []; go('pick', {}, true); };
        } catch (e) { sfBusy(false, e.message); }
      },
    });
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  로그인 / 가입
// ═══════════════════════════════════════════════════════════════════════
VIEWS.login = {
  html(p) {
    const mode = p.mode || 'login';
    const lastName = LS.get('lastName', '');
    const tabs = `<div class="toggle">
        <button data-act="mode" data-v="login" class="${mode!=='signup'?'on':''}">로그인</button>
        <button data-act="mode" data-v="signup" class="${mode==='signup'?'on':''}">처음 왔어요</button>
      </div>`;
    // 가입 신청은 한 칸씩 (이름 → 비밀번호 → 비밀번호 확인)
    if (mode === 'signup') return head(p.churchName || '가입 신청') + tabs + `<div id="signup">${stepFormHtml()}</div>`;
    return head(p.churchName || '로그인') + tabs + `
      ${mode === 'email' ? `
        <label class="f"><span>이메일</span><input id="email" type="email" autocapitalize="off"></label>
        <label class="f"><span>비밀번호</span><input id="password" type="password"></label>
      ` : `
        <label class="f"><span>이름</span><input id="name" value="${esc(lastName)}"></label>
        <label class="f"><span>숫자 6자리 비밀번호</span>
          <input id="pin" type="password" inputmode="numeric" maxlength="6"></label>
        ${mode === 'signup' ? `<label class="f"><span>비밀번호 다시 입력</span>
          <input id="pin2" type="password" inputmode="numeric" maxlength="6"></label>` : ''}
      `}
      <div id="out"></div>
      <button class="btn" data-act="submit">${mode === 'signup' ? '가입 신청하기' : '들어가기'}</button>
      ${mode !== 'signup' ? `
        <div style="height:14px"></div>
        <button class="btn plain" data-act="emailmode">
          ${mode === 'email' ? '이름과 숫자 비밀번호로 들어가기' : '이메일을 등록해 두신 분은 이메일로 들어가기'}</button>
        <p class="hint" style="text-align:center;margin-top:14px">
          비밀번호를 잊으셨으면 목사님이나 교회 관리자께 말씀해 주세요.<br>새 비밀번호를 만들어 드립니다.</p>` : ''}`;
  },
  after(p) {
    if (p.mode !== 'signup') return;
    stepFormMount({
      finishLabel: '가입 신청하기',
      steps: [
        { key: 'name', name: '이름', question: '이름을 적어 주세요', hint: '교회에서 부르는 이름 그대로 적어 주세요', maxLen: 30 },
        { key: 'pin', name: '비밀번호', question: '들어올 때 쓸 숫자 6자리를 정해 주세요',
          numeric: true, password: true, maxLen: 6,
          validate: (v) => v.length !== 6 ? '숫자 6자리여야 합니다' : null },
        { key: 'pin2', name: '비밀번호 확인', question: '같은 숫자 6자리를 한 번 더 적어 주세요',
          numeric: true, password: true, maxLen: 6,
          validate: (v, all) => v !== all.pin ? '위에서 정한 비밀번호와 다릅니다' : null },
      ],
      onFinish: async (v) => {
        sfBusy(true);
        try {
          const r = await api('/api/members/signup', { method: 'POST',
            body: { churchId: p.churchId, name: v.name.trim(), pinNumber: v.pin } });
          LS.set('lastName', v.name.trim());
          $('#signup').innerHTML = `<div class="card"><div style="font-size:calc(20px*var(--fs));font-weight:700">가입 신청 완료</div>
            <p>${nl(r.message || '승인 후 이용할 수 있습니다.')}</p></div>
            <button class="btn" data-act="mode" data-v="login">확인</button>`;
        } catch (e) { sfBusy(false, e.message); }
      },
    });
  },
  async click(act, el) {
    const p = S.view.params;
    if (act === 'mode') { S.view.params.mode = el.dataset.v; return render(); }
    if (act === 'emailmode') { S.view.params.mode = p.mode === 'email' ? 'login' : 'email'; return render(); }
    if (act !== 'submit') return;
    const out = $('#out'); const v = (id) => ($('#' + id)?.value || '').trim();
    el.disabled = true; out.innerHTML = '';
    try {
      const body = p.mode === 'email'
        ? { email: v('email'), password: v('password') }
        : { churchId: p.churchId, name: v('name'), pinNumber: v('pin') };
      if (p.mode !== 'email' && v('pin').length !== 6) throw new Error('비밀번호는 숫자 6자리여야 합니다');
      const r = await api('/api/auth/login', { method: 'POST', body });
      S.token = r.token; LS.set('token', r.token); LS.del('boot');
      if (p.mode !== 'email') LS.set('lastName', v('name'));
      const b = await api('/api/bootstrap');
      saveBoot(b);
      S.stack = [];
      go(b.me.mustChangePin ? 'changepin' : 'home', {}, true);
    } catch (e) { out.innerHTML = msg(e.message); el.disabled = false; }
  },
};

VIEWS.changepin = {
  html() {
    return head('새 비밀번호 만들기', { back: false }) + `
      <div class="card">임시 비밀번호로 들어오셨습니다.<br>앞으로 쓰실 숫자 6자리를 새로 정해 주세요.</div>
      <label class="f"><span>새 비밀번호 (숫자 6자리)</span>
        <input id="pin" type="password" inputmode="numeric" maxlength="6"></label>
      <label class="f"><span>다시 한 번</span>
        <input id="pin2" type="password" inputmode="numeric" maxlength="6"></label>
      <div id="out"></div>
      <button class="btn" data-act="submit">이 비밀번호로 정하기</button>`;
  },
  async click(act, el) {
    if (act !== 'submit') return;
    const v = (id) => $('#' + id).value.trim(); const out = $('#out');
    if (v('pin').length !== 6) return out.innerHTML = msg('숫자 6자리여야 합니다');
    if (v('pin') !== v('pin2')) return out.innerHTML = msg('두 개가 서로 다릅니다');
    el.disabled = true;
    try {
      await api('/api/me/update', { method: 'POST', body: { newPin: v('pin') } });
      saveBoot(await api('/api/bootstrap'));
      goHome();
    } catch (e) { out.innerHTML = msg(e.message); el.disabled = false; }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  홈
// ═══════════════════════════════════════════════════════════════════════
function homeMarkup(b) {
  const me = b.me, home = b.home || {}, prog = b.progress;
  const q = prog ? prog.quarters[Math.floor((new Date().getMonth()) / 3)] : null;
  const notice = home.latestNotice;
  return head(me.churchName, { back: false, settings: true }) + `
    <div style="font-size:calc(23px*var(--fs));font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(me.name)} ${esc(me.title)}</div>
    <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:4px">
      ${me.cellName ? `<span class="pill">${esc(me.cellName)}</span>` : ''}
      ${me.role === 'owner' || me.role === 'admin' ? `<span class="pill grey">${ROLE_LABEL[me.role]}</span>` : ''}
      <span class="meta">${kfull(todayStr())}</span>
    </div>

    ${notice && notice.unread ? `<div class="card" style="background:var(--greenlight);border-color:var(--greenlight);cursor:pointer" data-act="go" data-view="notice">
      <div class="meta" style="color:var(--green)">새 공지</div>
      <div style="font-size:calc(19px*var(--fs));font-weight:600">${esc((notice.title || notice.body).slice(0,40))}</div>
    </div>` : '<div style="height:16px"></div>'}

    ${prog ? `<div class="card">
      <div style="font-weight:600;margin-bottom:8px">나의 묵상</div>
      <div style="display:flex;gap:20px">
        <div style="flex:1"><div class="meta">이번 분기</div>
          <div class="big">${q ? q.percent : 0}%</div>
          <div class="meta">${q ? q.done : 0} / ${q ? q.total : 0}일</div></div>
        <div style="flex:1"><div class="meta">올해</div>
          <div class="big">${prog.annual.percent}%</div>
          <div class="meta">${prog.annual.done} / ${prog.annual.total}일</div></div>
      </div>
      <div style="height:12px"></div>
      <button class="btn soft" data-act="myrecord" style="min-height:52px;font-size:calc(16px*var(--fs))">내 묵상기록 보기</button>
      ${home.wroteToday === false ? '<div class="meta" style="margin-top:10px">오늘 묵상은 아직 올리지 않으셨어요.</div>' : ''}
    </div>` : ''}

    <div style="height:8px"></div>
    <div class="row" style="margin-bottom:12px">
      <button class="btn soft" data-act="bible">묵상 본문 보기</button>
      <button class="btn soft" data-act="zoom">교회 줌 접속</button>
    </div>
    <div class="row" style="margin-bottom:12px">
      <button class="btn" data-act="go" data-view="write">묵상 나눔하기</button>
      <button class="btn" data-act="go" data-view="feed">묵상 나눔광장</button>
    </div>
    <div class="row" style="margin-bottom:12px">
      <button class="btn plain" data-act="go" data-view="notice">공지사항
        ${home.unreadNoticeCount ? `<small>새 글 ${home.unreadNoticeCount}개</small>` : ''}</button>
      <button class="btn plain" data-act="go" data-view="bulletin">이번 주 주보
        ${home.latestBulletin ? `<small>${kdate(home.latestBulletin.week_of)}</small>` : ''}</button>
    </div>
    <div class="row" style="margin-bottom:12px">
      <button class="btn plain" data-act="go" data-view="prayer">기도제목 나눔</button>
      <button class="btn plain" data-act="go" data-view="roulette">돌림판</button>
    </div>
    ${(me.role === 'leader' || me.canManage) ? `<div style="margin-bottom:12px">
      <button class="btn plain" data-act="go" data-view="cellstatus">우리 셀 현황 보기</button></div>` : ''}
    ${(home.pendingApprovals ? `<div style="margin-bottom:12px">
      <button class="btn soft" data-act="go" data-view="members">가입 신청 ${home.pendingApprovals}건
        <small>승인을 기다리고 있습니다</small></button></div>` : '')}
    <div id="out"></div>
    <div style="height:20px"></div>`;
}

VIEWS.home = {
  async html() {
    const mk = (b) => (isSenior() ? homeMarkup(b) : modernHome(b));
    const cached = loadBoot();
    if (cached) {
      saveBoot(cached);
      setTimeout(async () => {                 // 뒤에서 조용히 갱신
        try {
          const fresh = await api('/api/bootstrap');
          saveBoot(fresh);
          if (S.view.view === 'home') { $('#app').innerHTML = mk(fresh); drawTabs(); }
        } catch {}
      }, 0);
      return mk(cached);
    }
    const b = await api('/api/bootstrap');
    saveBoot(b);
    return mk(b);
  },
  async click(act, el) {
    const out = $('#out');
    if (act === 'bible') window.open(LS.get('bibleUrl', DEFAULT_BIBLE), '_blank');
    if (act === 'myrecord') return go('person', { memberId: S.me.id });
    if (act === 'tab') { S.stack = []; S.view = { view: el.dataset.v, params: {} }; return render(); }
    if (act === 'zoom') {
      const url = S.home?.zoomUrl || S.me.zoomUrl;
      if (!url) out.innerHTML = msg(S.me.isOwner
        ? '아직 줌 링크가 없습니다. 설정 → 교회 관리 → 교회 줌 링크에서 등록해 주세요.'
        : '아직 줌 링크가 등록되지 않았습니다. 교회 관리자께 말씀해 주세요.');
      else window.open(url, '_blank');
    }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  묵상 나눔하기
// ═══════════════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════════════
//  새 화면 — 홈
// ═══════════════════════════════════════════════════════════════════════
function modernHome(b) {
  const me = b.me, home = b.home || {}, prog = b.progress;
  const q = prog ? prog.quarters[Math.floor((new Date().getMonth()) / 3)] : null;
  const notice = home.latestNotice;
  const wrote = home.wroteToday === true;
  const stat = (label, pct, sub) => `<div class="stat"><div class="meta">${label}</div>
    <div class="big">${pct}%</div><div class="bar2"><i style="width:${Math.min(100, pct)}%"></i></div>
    <div class="meta">${sub}</div></div>`;
  return head(me.churchName) + `
    <div class="hello">
      <div style="flex:1;min-width:0">
        <div class="meta">${kfull(todayStr())}</div>
        <div class="hname">${esc(me.name)} ${esc(me.title)}</div>
        <div style="display:flex;gap:6px;margin-top:6px">
          ${me.cellName ? `<span class="pill">${esc(me.cellName)}</span>` : ''}
          ${me.role === 'owner' || me.role === 'admin' ? `<span class="pill grey">${ROLE_LABEL[me.role]}</span>` : ''}
        </div>
      </div>
      <button class="avatar" data-act="tab" data-v="settings">${esc(me.name.slice(0, 1))}</button>
    </div>

    ${notice && notice.unread ? `<button class="soft" data-act="tab" data-v="alerts">${svg('mega', 22)}
      <span><small>새 공지</small>${esc((notice.title || notice.body).slice(0, 40))}</span>${svg('chev', 20)}</button>` : ''}
    ${home.pendingApprovals ? `<button class="soft" data-act="go" data-view="members">${svg('person', 22)}
      <span><small>가입 신청</small>${home.pendingApprovals}건이 승인을 기다리고 있어요</span>${svg('chev', 20)}</button>` : ''}

    ${prog ? `<div class="rcard warm">
      <div class="rhead">${svg('sun', 22)}<b>나의 묵상</b><span style="flex:1"></span>
        <button class="lnk" data-act="myrecord">내 기록 ›</button></div>
      <div style="display:flex;gap:12px">
        ${stat('이번 분기', q ? q.percent : 0, `${q ? q.done : 0} / ${q ? q.total : 0}일`)}
        ${stat('올해', prog.annual.percent, `${prog.annual.done} / ${prog.annual.total}일`)}
      </div></div>` : ''}

    <div class="rcard warm">
      <div class="rhead">${svg('check', 22)}<b>오늘의 실천</b></div>
      <div class="abox">
        <span class="circ">${svg('book', 28)}</span>
        <div class="atitle">${wrote ? '오늘 묵상을 나눴어요' : '오늘의 묵상을 남겨볼까요?'}</div>
        <button class="pbtn" data-act="go" data-view="write" data-params='${wrote ? JSON.stringify({ date: todayStr(), edit: true }) : '{}'}'>
          ${wrote ? '오늘 묵상 고치기' : '묵상 나누기'} ›</button>
      </div>
      <div class="abox">
        <span class="circ">${svg('heart', 28)}</span>
        <div class="atitle">함께 기도할 제목이 있나요?</div>
        <button class="pbtn" data-act="go" data-view="prayer">기도제목 나누기 ›</button>
      </div>
    </div>
    <div style="height:20px"></div>`;
}

// ═══════════════════════════════════════════════════════════════════════
//  새 화면 — 알림 (공지사항 · 주보)
// ═══════════════════════════════════════════════════════════════════════
VIEWS.alerts = {
  async html(p) {
    const sub = p.sub || 'notice'; S.view.params = { sub };
    const header = `<div class="toggle">
      <button data-act="sub" data-v="notice" class="${sub === 'notice' ? 'on' : ''}">공지사항</button>
      <button data-act="sub" data-v="bulletin" class="${sub === 'bulletin' ? 'on' : ''}">주보</button></div>`;
    return VIEWS[sub].html({ title: '알림', header });
  },
  after(p) { const f = VIEWS[p.sub || 'notice'].after; if (f) f.call(VIEWS[p.sub || 'notice'], {}); },
  async click(act, el, e) {
    if (act === 'sub') return go('alerts', { sub: el.dataset.v }, true);
    const v = VIEWS[S.view.params.sub || 'notice'];
    return v.click.call(v, act, el, e);
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  새 화면 — 더보기 (기타 기능)
// ═══════════════════════════════════════════════════════════════════════
VIEWS.more = {
  html() {
    const me = S.me;
    const tool = (icon, title, sub, attrs) => `<button class="tool" ${attrs}>
      <span class="bub">${svg(icon)}</span><b>${title}</b><small>${sub}</small></button>`;
    return head('더보기') + `
      <h3 class="shead" style="margin-top:4px">추가 기능</h3>
      <div class="tools">
        ${tool('pie', '돌림판', '점심 메뉴 정하기', 'data-act="go" data-view="roulette"')}
        ${tool('video', '교회 줌', '바로 입장', 'data-act="zoom"')}
      </div>
      <div id="out"></div>
      <h3 class="shead">묵상 · 기도</h3>
      <div class="tools">
        ${tool('heart', '기도제목', '함께 기도하기', 'data-act="go" data-view="prayer"')}
        ${tool('cal', '묵상 출석표', '1년 잔디 보기', 'data-act="go" data-view="attendance"')}
        ${tool('search', '묵상 찾기', '말씀으로 찾기', 'data-act="go" data-view="search"')}
        ${tool('book', '묵상 본문', '오늘의 말씀', 'data-act="bible"')}
        ${me.role === 'leader' || me.canManage ? tool('users', '우리 구역 현황', '오늘 누가 나눴는지', 'data-act="go" data-view="cellstatus"') : ''}
      </div>
      `;
  },
  click(act) { return VIEWS.home.click(act, document.body); },
};

// ═══════════════════════════════════════════════════════════════════════
//  돌림판 — 항목을 직접 적고 돌립니다. 만든 돌림판은 이 기기에 저장됩니다.
// ═══════════════════════════════════════════════════════════════════════
const DEFAULT_WHEEL = { name: '점심 메뉴', items: ['김치찌개', '된장찌개', '제육볶음', '칼국수', '돈까스', '비빔밥'] };
const SLICE = ['#1F4D3A', '#E8DCCB', '#5E8C73', '#F3ECE2', '#A8675C', '#D5E2D6'];
const SLICE_TEXT = ['#fff', '#2B2622', '#fff', '#2B2622', '#fff', '#2B2622'];
const loadWheels = () => { try { return JSON.parse(LS.get('wheels', '[]')) || []; } catch { return []; } };
const saveWheels = (list) => LS.set('wheels', JSON.stringify(list));

function wheelSvg(items) {
  const n = items.length, R = 150, C = 160;
  if (!n) return `<svg viewBox="0 0 320 320"><circle cx="${C}" cy="${C}" r="${R}" fill="#F2ECE3"/></svg>`;
  const seg = 360 / n; let out = '';
  const pt = (deg, r) => { const a = (deg - 90) * Math.PI / 180; return [C + r * Math.cos(a), C + r * Math.sin(a)]; };
  items.forEach((label, i) => {
    let ci = i % SLICE.length;
    if (i === n - 1 && n % SLICE.length === 1 && n > 1) ci = 2;   // 첫 칸과 마지막 칸 색이 겹치지 않게
    const a0 = i * seg, a1 = (i + 1) * seg;
    const [x0, y0] = pt(a0, R), [x1, y1] = pt(a1, R);
    out += n === 1 ? `<circle cx="${C}" cy="${C}" r="${R}" fill="${SLICE[ci]}"/>`
      : `<path d="M${C},${C} L${x0},${y0} A${R},${R} 0 ${seg > 180 ? 1 : 0} 1 ${x1},${y1} Z" fill="${SLICE[ci]}" stroke="#FFFDFA" stroke-width="2"/>`;
    const mid = a0 + seg / 2; const [tx, ty] = pt(mid, R * 0.66);
    out += `<text x="${tx}" y="${ty}" fill="${SLICE_TEXT[ci]}" font-size="15" font-weight="600" text-anchor="middle"
      dominant-baseline="middle" transform="rotate(${mid} ${tx} ${ty})">${esc(label.slice(0, 8))}</text>`;
  });
  return `<svg viewBox="0 0 320 320">${out}<circle cx="${C}" cy="${C}" r="${R}" fill="none" stroke="#1F4D3A" stroke-width="4"/></svg>`;
}

VIEWS.roulette = {
  html() {
    if (!S.wheel) {
      const last = loadWheels().find((w) => w.name === '__last__');
      S.wheel = last ? { name: last.items[0], items: last.items.slice(1) } : { ...DEFAULT_WHEEL, items: [...DEFAULT_WHEEL.items] };
    }
    S.spin = S.spin || 0;
    return head('돌림판') + `
      <div class="wheelbox">
        <div class="needle"></div>
        <div class="wheel" id="wheel" style="transform:rotate(${S.spin}deg)">${wheelSvg(S.wheel.items)}</div>
        <button class="spinbtn" data-act="spin">돌리기</button>
      </div>
      <div id="result"></div>
      <div id="out"></div>
      <h2 class="sec">항목</h2>
      <label class="f"><span>돌림판 이름</span><input id="wname" maxlength="20" value="${esc(S.wheel.name)}"></label>
      <div id="witems"></div>
      <div style="display:flex;gap:8px;margin:8px 0 14px">
        <input id="wnew" maxlength="20" placeholder="새 항목 (적고 엔터)" enterkeyhint="done" style="flex:1">
        <button class="btn" data-act="additem" style="width:auto;min-height:0;padding:12px 18px">추가</button>
      </div>
      <button class="btn soft" data-act="savewheel">이 돌림판 저장하기</button>
      <h2 class="sec">저장한 돌림판</h2>
      <div id="wsaved"></div>`;
  },
  after() {
    this.drawItems(); this.drawSaved();
    $('#wname').oninput = (e) => { S.wheel.name = e.target.value; };
    $('#wnew').onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); this.addItem(); } };
  },
  addItem() {
    const inp = $('#wnew'); const t = inp.value.trim();
    if (t && S.wheel.items.length < 16) { S.wheel.items.push(t); this.redraw(); }
    inp.value = ''; inp.focus();
  },
  redraw() { $('#wheel').innerHTML = wheelSvg(S.wheel.items); this.drawItems(); },
  drawItems() {
    $('#witems').innerHTML = S.wheel.items.map((it, i) => `<div class="witem">
      <i style="background:${SLICE[i % SLICE.length]}"></i><span>${esc(it)}</span>
      <button data-act="rmitem" data-i="${i}">빼기</button></div>`).join('');
  },
  drawSaved() {
    const list = loadWheels().filter((w) => w.name !== '__last__');
    const shown = [...(list.some((w) => w.name === DEFAULT_WHEEL.name) ? [] : [DEFAULT_WHEEL]), ...list];
    $('#wsaved').innerHTML = (list.length ? '' : '<p class="meta">아직 저장한 돌림판이 없습니다.</p>') +
      shown.map((w) => `<div class="card wsaved" data-act="loadwheel" data-name="${esc(w.name)}">
        <div style="flex:1;min-width:0"><b>${esc(w.name)}</b><div class="meta ell">${esc(w.items.join(', '))}</div></div>
        ${list.some((x) => x.name === w.name) ? `<button data-act="delwheel" data-name="${esc(w.name)}">지움</button>` : ''}
      </div>`).join('');
  },
  click(act, el, e) {
    const out = $('#out'); out.innerHTML = '';
    if (act === 'additem') return this.addItem();
    if (act === 'rmitem') { if (!S.spinning) { S.wheel.items.splice(+el.dataset.i, 1); this.redraw(); } return; }
    if (act === 'savewheel') {
      if (S.wheel.items.length < 2) return out.innerHTML = msg('항목을 두 개 이상 적어 주세요');
      const name = (S.wheel.name || '').trim() || '내 돌림판';
      saveWheels([...loadWheels().filter((w) => w.name !== name), { name, items: [...S.wheel.items] }]);
      out.innerHTML = msg('저장했습니다.', true); return this.drawSaved();
    }
    if (act === 'delwheel') {
      e?.stopPropagation();
      saveWheels(loadWheels().filter((w) => w.name !== el.dataset.name)); return this.drawSaved();
    }
    if (act === 'loadwheel') {
      if (S.spinning) return;
      const w = [DEFAULT_WHEEL, ...loadWheels()].reverse().find((x) => x.name === el.dataset.name); if (!w) return;
      S.wheel = { name: w.name, items: [...w.items] }; $('#wname').value = w.name; $('#result').innerHTML = '';
      return this.redraw();
    }
    if (act === 'spin') {
      if (S.spinning) return;
      const items = S.wheel.items;
      if (items.length < 2) return out.innerHTML = msg('항목을 두 개 이상 적어 주세요');
      S.spinning = true; $('#result').innerHTML = '';
      saveWheels([...loadWheels().filter((w) => w.name !== '__last__'),
        { name: '__last__', items: [S.wheel.name, ...items] }]);
      S.spin += 360 * (5 + Math.floor(Math.random() * 3)) + Math.random() * 360;
      const wh = $('#wheel');
      wh.style.transition = 'transform 3.8s cubic-bezier(.15,.85,.25,1)';
      wh.style.transform = `rotate(${S.spin}deg)`;
      setTimeout(() => {
        S.spinning = false;
        const seg = 360 / items.length;
        const at = (360 - (S.spin % 360)) % 360;   // 바늘(맨 위) 아래에 있는 원래 각도
        const pick = items[Math.min(items.length - 1, Math.floor(at / seg))];
        const box = $('#result');
        if (box) box.innerHTML = `<div class="rcard" style="background:var(--greenlight);text-align:center">
          <div class="meta">오늘은</div><div class="big">${esc(pick)}</div></div>`;
      }, 3900);
    }
  },
};

const MAX_PHOTOS = 4;

// p.edit 가 있으면 그 날짜에 쓴 글을 고치는 화면 (날짜는 못 바꿈)
VIEWS.write = {
  async html(p) {
    const date = p.date || todayStr();
    S.view.params.date = date;
    const me = S.me;
    const canPick = me.shareScope === 'all_cells';
    if (canPick && !S.cells.length) S.cells = (await api('/api/cells')).cells;
    const mine = (await api('/api/entries/mine', { params: { from: date, to: date } })).entries[0];
    S.editing = !!mine;
    S.photos = mine ? (mine.images || []) : [];
    S.uploading = 0;
    const draftKey = 'draft:' + date;
    const draft = mine ? null : JSON.parse(LS.get(draftKey, 'null'));
    const title = mine ? (mine.title || '') : (draft?.title || '');
    const body  = mine ? mine.body : (draft?.body || '');
    const isPublic = mine ? mine.is_public === 1 : true;
    const picked = mine && mine.cellIds?.length ? mine.cellIds : (me.cellId ? [me.cellId] : []);
    const smallBtn = 'style="min-height:52px;font-size:calc(16px*var(--fs))"';
    return head(p.edit ? '묵상 고치기' : '묵상 나눔하기') + `
      <div class="card">
        <div class="meta">날짜</div>
        <div style="font-size:calc(21px*var(--fs));font-weight:700;margin:4px 0 ${p.edit ? 0 : 12}px">${kfull(date)}</div>
        ${p.edit ? '' : `<div class="row">
          <button class="btn plain" data-act="day" data-n="-1" ${smallBtn}>하루 앞</button>
          <button class="btn plain" data-act="today" ${smallBtn}>오늘</button>
          <button class="btn plain" data-act="day" data-n="1" ${smallBtn}>하루 뒤</button>
        </div>`}
      </div>
      ${mine && !p.edit ? msg('이 날짜에 쓰신 글을 불러왔습니다. 고치면 그대로 바뀝니다.', true)
             : (draft ? msg('쓰시다 만 글이 있어 그대로 불러왔습니다.', true) : '')}
      <label class="f"><span>제목 (없어도 됩니다)</span><input id="title" value="${esc(title)}"></label>
      <label class="f"><span>오늘 묵상한 내용 (사진만 올려도 됩니다)</span><textarea id="body">${esc(body)}</textarea></label>

      <div class="card">
        <div style="display:flex;align-items:center"><b style="flex:1">사진</b>
          <span class="meta" id="pcount"></span></div>
        <div class="thumbs" id="thumbs"></div>
        <button class="btn soft" data-act="addphoto" id="addphoto">갤러리에서 사진 넣기<small>자동으로 줄여서 올립니다</small></button>
        <input type="file" id="photofile" accept="image/*" multiple style="display:none">
      </div>

      <div class="card">
        <label class="cb"><input type="checkbox" id="pub" ${isPublic ? 'checked' : ''}>
          <span><b>함께 나누기</b><br><span class="meta">끄면 출석표에는 남지만 글은 나만 봅니다</span></span></label>
      </div>
      ${canPick && S.cells.length ? `<h2 class="sec">어느 셀에 나눌까요</h2>
        ${chipsHtml(S.cells, picked, 'wcell')}
        <p class="meta" id="wcellnote" style="margin-top:4px"></p>`
        : (me.cellName ? `<p class="meta">${esc(me.cellName)} 안에서 나눠집니다.</p>` : '')}
      <div id="out"></div>
      <button class="btn" data-act="save" id="savebtn">${mine ? '고친 내용 저장' : '올리기'}</button>`;
  },
  after(p) {
    // 이미 올린 글을 고칠 때는 임시저장하지 않습니다
    if (!S.editing) {
      const save = () => LS.set('draft:' + p.date,
        JSON.stringify({ title: $('#title').value, body: $('#body').value }));
      let t;
      ['#title', '#body'].forEach((s2) => $(s2).addEventListener('input', () => {
        clearTimeout(t); t = setTimeout(save, 1200);
      }));
    }
    const note = () => {
      const n = $('#wcellnote'); if (!n) return;
      const k = chipsValue('wcell').length;
      n.textContent = !k ? '고르지 않으면 내가 속한 셀에 나눠집니다'
        : k === S.cells.length ? '모든 셀에 나눠집니다' : `${k}개 셀에 나눠집니다`;
    };
    note(); document.querySelector('.chips')?.addEventListener('chipchange', note);
    this.drawPhotos();
    $('#photofile').onchange = async (e) => {
      const files = [...e.target.files].slice(0, MAX_PHOTOS - S.photos.length - S.uploading);
      e.target.value = '';
      if (!files.length) return;
      for (const f of files) {
        S.uploading++; this.drawPhotos();
        (async () => {
          try {
            const blob = await shrinkForPost(f);
            const res = await fetch('/api/entries/image', { method: 'POST',
              headers: { 'Content-Type': 'image/jpeg', Authorization: 'Bearer ' + S.token }, body: blob });
            const j = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(j.error || '사진을 올리지 못했습니다');
            if (S.photos.length < MAX_PHOTOS) S.photos.push({ id: j.id, url: j.url });
          } catch (err) { $('#out').innerHTML = msg(err.message || '사진을 읽지 못했습니다'); }
          S.uploading--; this.drawPhotos();
        })();
      }
    };
  },
  drawPhotos() {
    const box = $('#thumbs'); if (!box) return;
    box.innerHTML = S.photos.map((ph) => `<div class="thumb"><img src="${esc(ph.url)}" alt="넣은 사진">
        <button type="button" data-act="rmphoto" data-id="${ph.id}">빼기</button></div>`).join('') +
      Array.from({ length: S.uploading }, () => '<div class="thumb wait">올리는 중…</div>').join('');
    box.style.display = S.photos.length || S.uploading ? 'flex' : 'none';
    $('#pcount').textContent = `${S.photos.length} / ${MAX_PHOTOS}`;
    $('#addphoto').disabled = S.photos.length + S.uploading >= MAX_PHOTOS;
    const sb = $('#savebtn');
    if (sb) { sb.disabled = S.uploading > 0; sb.textContent = S.uploading > 0 ? '사진 올리는 중…' : (S.editing ? '고친 내용 저장' : '올리기'); }
  },
  async click(act, el) {
    const p = S.view.params;
    if (act === 'day') return go('write', { date: shiftDate(p.date, +el.dataset.n) }, true);
    if (act === 'today') return go('write', { date: todayStr() }, true);
    if (act === 'addphoto') return $('#photofile').click();
    if (act === 'rmphoto') { S.photos = S.photos.filter((x) => x.id !== +el.dataset.id); return this.drawPhotos(); }
    if (act !== 'save') return;
    const out = $('#out');
    const body = $('#body').value.trim();
    // 사진을 넣었으면 글은 없어도 됩니다
    if (!body && !S.photos.length) return out.innerHTML = msg('묵상한 내용을 적거나 사진을 넣어 주세요');
    const picked = $('[data-chip="wcell"]') ? chipsValue('wcell') : [];
    const allCells = picked.length > 0 && picked.length === S.cells.length;
    el.disabled = true;
    try {
      await api('/api/entries/save', { method: 'POST', body: {
        date: p.date, title: $('#title').value.trim(), body,
        isPublic: $('#pub').checked,
        cellIds: !allCells && picked.length ? picked : null, allCells,
        imageIds: S.photos.map((x) => x.id),
      }});
      LS.del('draft:' + p.date);
      // 새 글은 홈으로, 고친 글은 보던 화면으로
      if (p.edit) back(); else goHome();
    } catch (e) { out.innerHTML = msg(e.message); el.disabled = false; }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  나눔 글 한 칸 (좋아요·댓글·공유)
// ═══════════════════════════════════════════════════════════════════════
function entryCard(e, { showAuthor = true } = {}) {
  const mine = e.member_id === S.me.id;
  return `<div class="card" data-entry="${e.id}">
    ${showAuthor ? `<div style="display:flex;align-items:center;gap:8px">
      <span class="link" data-act="person" data-id="${e.member_id}"
        style="font-weight:600">${esc(e.member_name)} ${esc(e.member_title || '')}</span>
      ${e.cell_name ? `<span class="pill">${esc(e.cell_name)}</span>` : ''}
    </div>` : ''}
    <div class="meta">${kdate(e.date)}${e.is_public === 0 ? ' · <span class="pill grey">나만 보기</span>' : ''}</div>
    ${e.title ? `<div style="font-size:calc(20px*var(--fs));font-weight:700;margin-top:8px">${esc(e.title)}</div>` : ''}
    ${e.body ? `<div style="margin-top:8px">${nl(e.body)}</div>` : ''}
    ${photosHtml(e.images)}
    <div class="bar">
      <button data-act="like" data-id="${e.id}" class="${e.liked ? 'on' : ''}">
        ♡ <span class="lc">${e.like_count || 0}</span></button>
      <button data-act="comments" data-id="${e.id}">댓글 <span class="cc">${e.comment_count || 0}</span></button>
      <span class="sp"></span>
      <button data-act="share" data-id="${e.id}">공유</button>
      ${mine ? `<button data-act="edit" data-date="${esc(e.date)}" style="color:var(--green);font-weight:600">고치기</button>
        <button data-act="del" data-id="${e.id}" style="color:var(--danger)">지움</button>` : ''}
    </div>
    <div class="cbox" style="display:none"></div>
  </div>`;
}

async function loadComments(card, id) {
  const box = $('.cbox', card);
  box.innerHTML = spin();
  const r = await api('/api/entries/comments', { params: { entryId: id } });
  box.innerHTML = r.comments.map((c) => `<div class="cmt" style="padding:6px 0">
      <div class="meta" style="color:var(--green);font-weight:600">${esc(c.member_name)} ${esc(c.member_title||'')}</div>
      <div>${nl(c.body)}${(c.member_id === r.myId || S.me.canManage)
        ? ` <button data-act="delc" data-id="${c.id}" style="border:none;background:none;color:var(--muted);cursor:pointer">지움</button>` : ''}</div>
    </div>`).join('') + `
    <div style="display:flex;gap:8px;margin-top:8px">
      <input class="cin" placeholder="댓글 달기" style="flex:1">
      <button class="btn soft" data-act="addc" data-id="${id}" style="width:auto;min-height:0;padding:10px 16px">남기기</button>
    </div>`;
  $('.cc', card).textContent = r.comments.length;
}

async function entryClick(act, el, list) {
  const id = +el.dataset.id;
  const card = el.closest('[data-entry]');
  if (act === 'person') return go('person', { memberId: id });
  if (act === 'edit') return go('write', { date: el.dataset.date, edit: true });
  if (act === 'like') {
    const r = await api('/api/entries/like', { method: 'POST', body: { entryId: id } });
    el.classList.toggle('on', r.liked); $('.lc', el).textContent = r.count; return true;
  }
  if (act === 'comments') {
    const box = $('.cbox', card);
    if (box.style.display === 'block') { box.style.display = 'none'; return true; }
    box.style.display = 'block';
    await loadComments(card, id);
    return true;
  }
  if (act === 'addc') {
    const inp = $('.cin', card); const t = inp.value.trim(); if (!t) return true;
    await api('/api/entries/comment', { method: 'POST', body: { entryId: id, body: t } });
    await loadComments(card, id);
    return true;
  }
  if (act === 'delc') {
    await api('/api/entries/comment/delete', { method: 'POST', body: { id } });
    await loadComments(card, +card.dataset.entry);
    return true;
  }
  if (act === 'share') {
    const e = (list || []).find((x) => x.id === id); if (!e) return true;
    const who = e.member_name || S.me.name;
    const text = `${S.me.churchName} 묵상 나눔\n${kfull(e.date)} · ${who}\n\n` +
      (e.title ? e.title + '\n\n' : '') + e.body;
    // 내가 쓴 공개 글은 '링크'로 — 교회 밖 사람도 사진까지 그 글 하나만 볼 수 있습니다.
    let payload = { title: '묵상 나눔', text };
    if (e.member_id === S.me.id && e.is_public !== 0) {
      try {
        const r = await api('/api/entries/share', { method: 'POST', body: { entryId: id } });
        payload = { title: `${S.me.churchName} 묵상 나눔`,
          text: `${S.me.churchName} 묵상 나눔 · ${e.title || who + '님의 묵상'}`, url: r.url };
      } catch {}
    }
    if (navigator.share) { try { await navigator.share(payload); } catch {} }
    else {
      await navigator.clipboard?.writeText(payload.url ? `${payload.text}\n${payload.url}` : text);
      alert(payload.url ? '링크를 복사했습니다. 붙여넣어 보내세요.' : '내용을 복사했습니다. 붙여넣어 보내세요.');
    }
    return true;
  }
  if (act === 'del') {
    if (!confirm('이 글을 지울까요?\n지우면 출석표의 초록 점도 함께 사라집니다.')) return true;
    await api('/api/entries/delete', { method: 'POST', body: { id } });
    render(); return true;
  }
  return false;
}

// ═══════════════════════════════════════════════════════════════════════
//  오늘의 나눔
// ═══════════════════════════════════════════════════════════════════════
// cell = 우리 구역 나눔 / all = 전체 나눔 / mine = 나의 나눔 모아보기
VIEWS.feed = {
  async html(p) {
    const merged = inAllCells();
    const tab = p.tab || (merged ? 'all' : 'cell');
    const date = p.date || todayStr();
    S.view.params = { date, tab };
    let entries, total = 0;
    if (tab === 'mine') {
      const d = await api('/api/entries/person', { params: { memberId: S.me.id } });
      entries = d.entries; total = d.total;
    } else entries = (await api('/api/entries/feed', { params: { date, scope: tab } })).entries;
    S.list = entries;
    const tabs = [...(merged ? [] : [['cell', S.me.cellName || '우리 구역']]), ['all', '전체 나눔'], ['mine', '나의 나눔']];
    return head(tab === 'mine' ? '나의 나눔' : '오늘의 나눔', { home: true }) + `
      <div class="toggle">${tabs.map(([k, l]) =>
        `<button data-act="tab" data-v="${k}" class="${tab === k ? 'on' : ''}"
          style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(l)}</button>`).join('')}</div>
      ${tab === 'mine' ? `<div class="card"><div class="meta">지금까지 나눈 묵상</div><div class="big">${total}회</div></div>` : `
      <div class="datenav">
        <button data-act="day" data-n="-1" aria-label="앞날">‹</button>
        <div class="d">${kdate(date)}</div>
        <button data-act="day" data-n="1" aria-label="다음날" ${date >= todayStr() ? 'disabled' : ''}>›</button>
      </div>
      <div class="row" style="margin-bottom:18px">
        <button class="btn soft" data-act="go" data-view="attendance">출석표 보기</button>
        <button class="btn soft" data-act="go" data-view="search">묵상 찾기</button>
      </div>`}
      ${entries.length ? entries.map((e) => entryCard(e, { showAuthor: tab !== 'mine' })).join('')
        : empty(tab === 'mine' ? '아직 나눈 묵상이 없습니다.' : '아직 이 날 나눈 묵상이 없습니다.')}`;
  },
  async click(act, el) {
    const p = S.view.params;
    if (act === 'tab') return go('feed', { date: p.date, tab: el.dataset.v }, true);
    if (act === 'day') {
      const d = shiftDate(p.date, +el.dataset.n);
      if (d > todayStr()) return;
      return go('feed', { date: d, tab: p.tab }, true);
    }
    await entryClick(act, el, S.list);
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  묵상 출석표 — 최근 1년, 이번 달이 맨 오른쪽 (일요일 시작 · 가로 흐름)
//  처음엔 이번 달이 보이고, 왼쪽으로 밀면 지난 달들이 나옵니다.
// ═══════════════════════════════════════════════════════════════════════
function monthBlock(year, month, done, showYear) {
  const first = new Date(year, month - 1, 1);
  const len = new Date(year, month, 0).getDate();
  const lead = first.getDay();                 // 0 = 일요일
  const weeks = Math.ceil((lead + len) / 7);
  const t = todayStr();
  let html = `<div class="month"><b>${showYear ? `${String(year).slice(2)}년 ` : ''}${month}월</b><div class="weeks">`;
  for (let w = 0; w < weeks; w++) {
    html += '<div class="week">';
    for (let d = 0; d < 7; d++) {
      const day = w * 7 + d - lead + 1;
      if (day < 1 || day > len) { html += '<span class="dot none"></span>'; continue; }
      const ds = `${year}-${pad(month)}-${pad(day)}`;
      html += `<span class="dot ${done.has(ds) ? 'done' : (ds > t ? 'none' : '')}"></span>`;
    }
    html += '</div>';
  }
  return html + '</div></div>';
}

function yearGrass(done) {
  const now = new Date();
  const list = [];
  for (let i = 11; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    list.push([d.getFullYear(), d.getMonth() + 1]);
  }
  return `<div class="grass">${list.map(([y, m], i) => monthBlock(y, m, done, i === 0 || m === 1)).join('')}</div>`;
}

VIEWS.attendance = {
  async html(p) {
    // 구역 하나씩 골라 봅니다. 처음엔 내 첫 구역. 0 = 전체
    const mine = S.me.cellIds || [];
    const cellId = p.cellId !== undefined ? p.cellId : (mine[0] || 0);
    S.view.params = { cellId };
    const cells = [...(S.cells || [])].sort((a, b) =>
      (mine.includes(a.id) ? 0 : 1) - (mine.includes(b.id) ? 0 : 1) || a.name.localeCompare(b.name));
    const d = await api('/api/entries/attendance', { params: {
      scope: cellId ? 'cell' : 'all', range: 'year', cellId: cellId || null } });
    return head('묵상 출석표', { home: true }) + `
      ${cells.length ? `<div class="chips" style="margin-bottom:10px">
        ${cells.map((c) => `<button type="button" class="chip ${c.id === cellId ? 'on' : ''}" data-act="pickcell" data-id="${c.id}">${esc(c.name)}</button>`).join('')}
        <button type="button" class="chip ${!cellId ? 'on' : ''}" data-act="pickcell" data-id="0">전체</button>
      </div>` : ''}
      <div class="legend"><span class="dot done"></span>나눈 날
        <span style="width:12px"></span><span class="dot"></span>안 한 날</div>
      <p class="hint" style="margin-top:4px">옆으로 밀면 지난 달이 나옵니다. 이름을 누르면 그분 묵상기록으로 갑니다.</p>
      ${d.people.length ? d.people.map((pp) => {
        const done = new Set(pp.dates);
        return `<div class="card">
          <div style="display:flex;align-items:center;gap:8px">
            <span class="link" data-act="person" data-id="${pp.id}" style="font-weight:600">${esc(pp.name)} ${esc(pp.title||'')}</span>
            ${pp.cell_name ? `<span class="pill">${esc(pp.cell_name)}</span>` : ''}
            <span class="sp" style="flex:1"></span>
            <span class="pill">1년 ${pp.dates.length}회</span>
          </div>
          <div style="height:12px"></div>
          ${yearGrass(done)}
        </div>`;
      }).join('') : empty('보여 드릴 사람이 없습니다.')}`;
  },
  after() {
    // 이번 달(맨 오른쪽)이 먼저 보이게
    $$('.grass').forEach((g) => { g.scrollLeft = g.scrollWidth; });
  },
  click(act, el) {
    const p = S.view.params;
    if (act === 'pickcell') return go('attendance', { cellId: +el.dataset.id }, true);
    if (act === 'person') return go('person', { memberId: +el.dataset.id });
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  한 사람의 묵상기록
// ═══════════════════════════════════════════════════════════════════════
VIEWS.person = {
  async html(p) {
    const month = p.month || 'all', day = p.day || 'all';
    S.view.params = { ...p, month, day };
    const d = await api('/api/entries/person', { params: { memberId: p.memberId, month, day } });
    S.list = d.entries;
    const opt = (v, cur, label) => `<option value="${v}" ${v === cur ? 'selected' : ''}>${label}</option>`;
    return head(`${d.person.name} ${d.person.title || ''}`, { home: true }) + `
      <div class="card"><div class="meta">지금까지 나눈 묵상</div><div class="big">${d.total}회</div></div>
      <h2 class="sec">추려 보기</h2>
      <div class="row" style="margin-bottom:16px">
        <select id="m">${opt('all', month, '월 전체')}${Array.from({length:12},(_,i)=>opt(String(i+1),month,`${i+1}월`)).join('')}</select>
        <select id="d">${opt('all', day, '일 전체')}${Array.from({length:31},(_,i)=>opt(String(i+1),day,`${i+1}일`)).join('')}</select>
      </div>
      ${d.entries.length ? d.entries.map((e) => entryCard(e, { showAuthor: false })).join('')
                         : empty('해당하는 묵상이 없습니다.')}`;
  },
  after(p) {
    const ch = () => go('person', { ...p, month: $('#m').value, day: $('#d').value }, true);
    $('#m').onchange = ch; $('#d').onchange = ch;
  },
  async click(act, el) { await entryClick(act, el, S.list); },
};

// ═══════════════════════════════════════════════════════════════════════
//  묵상 찾기
// ═══════════════════════════════════════════════════════════════════════
VIEWS.search = {
  html(p) {
    const all = !!p.all || inAllCells();
    return head('묵상 찾기', { home: true }) + `
      <label class="f"><span>찾을 말 (두 글자 이상)</span>
        <input id="q" value="${esc(p.q || '')}" autocomplete="off"></label>
      ${scopeToggle(all, S.me.cellName || '우리 셀', '전체에서 찾기')}
      <div id="results"></div>`;
  },
  after(p) {
    const run = async () => {
      const q = $('#q').value.trim();
      if (q.length < 2) { $('#results').innerHTML = ''; return; }
      $('#results').innerHTML = spin('찾는 중입니다');
      try {
        const { entries } = await api('/api/entries/search',
          { params: { q, scope: (p.all || inAllCells()) ? 'all' : 'cell' } });
        S.list = entries;
        $('#results').innerHTML = entries.length
          ? entries.map((e) => entryCard(e)).join('') : empty('찾으시는 묵상이 없습니다.');
      } catch (e) { $('#results').innerHTML = msg(e.message); }
    };
    let t; $('#q').addEventListener('input', () => { clearTimeout(t); t = setTimeout(run, 400); });
    if (p.q) run();
  },
  async click(act, el) {
    if (act === 'scope')
      return go('search', { q: $('#q').value, all: el.dataset.v === '1' }, true);
    await entryClick(act, el, S.list);
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  기도제목
// ═══════════════════════════════════════════════════════════════════════
VIEWS.prayer = {
  async html(p) {
    S.view.params = { all: !!p.all };
    const all = !!p.all || inAllCells();
    const me = S.me;
    if (me.shareScope === 'all_cells' && !S.cells.length) S.cells = (await api('/api/cells')).cells;
    const { prayers } = await api('/api/prayers', { params: { scope: all ? 'all' : 'cell' } });
    return head('기도제목', { home: true }) + `
      ${scopeToggle(all, me.cellName || '우리 셀', '전체 보기')}
      <div class="card">
        <label class="f"><textarea id="pbody" style="min-height:100px" placeholder="함께 기도할 제목"></textarea></label>
        ${me.shareScope === 'all_cells' && S.cells.length ? `
          <div class="meta">나눌 셀</div>
          ${chipsHtml(S.cells, me.cellId ? [me.cellId] : [], 'pcell')}` : ''}
        <button class="btn" data-act="add">기도제목 올리기</button>
      </div>
      <div id="out"></div>
      ${prayers.length ? prayers.map((p2) => `<div class="card">
        <div style="display:flex;align-items:center;gap:8px">
          <b>${esc(p2.member_name)} ${esc(p2.member_title||'')}</b>
          ${p2.answered ? '<span class="pill">응답받음</span>' : ''}
          <span style="flex:1"></span><span class="meta">${shortTime(p2.created_at)}</span>
        </div>
        <div style="margin-top:8px">${nl(p2.body)}</div>
        <div class="bar">
          <button data-act="amen" data-id="${p2.id}" class="${p2.amened?'on':''}">
            기도했어요${p2.amened?' ✓':''} <span class="ac">${p2.amen_count}</span></button>
          <span class="sp"></span>
          ${p2.member_id === S.me.id ? `
            <button data-act="answered" data-id="${p2.id}" data-v="${p2.answered?0:1}">
              ${p2.answered ? '응답 표시 지우기' : '응답받았어요'}</button>
            <button data-act="delp" data-id="${p2.id}" style="color:var(--danger)">지움</button>` : ''}
        </div>
      </div>`).join('') : empty('아직 올라온 기도제목이 없습니다.')}`;
  },
  async click(act, el) {
    const id = +el.dataset.id;
    if (act === 'scope') return go('prayer', { all: el.dataset.v === '1' }, true);
    if (act === 'add') {
      const t = $('#pbody').value.trim(); if (!t) return;
      const picked = $('[data-chip="pcell"]') ? chipsValue('pcell') : [];
      const allCells = picked.length > 0 && picked.length === S.cells.length;
      const cellIds = allCells ? null : picked;
      try {
        await api('/api/prayers/save', { method: 'POST',
          body: { body: t, cellIds: cellIds && cellIds.length ? cellIds : null, allCells } });
        render();
      } catch (e) { $('#out').innerHTML = msg(e.message); }
    }
    if (act === 'amen') {
      const r = await api('/api/prayers/amen', { method: 'POST', body: { id } });
      el.classList.toggle('on', r.liked); $('.ac', el).textContent = r.count;
    }
    if (act === 'answered') {
      await api('/api/prayers/answered', { method: 'POST', body: { id, answered: el.dataset.v === '1' } });
      render();
    }
    if (act === 'delp') {
      if (!confirm('기도제목을 지울까요?')) return;
      await api('/api/prayers/delete', { method: 'POST', body: { id } }); render();
    }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  공지사항
// ═══════════════════════════════════════════════════════════════════════
VIEWS.notice = {
  async html(p = {}) {
    const d = await api('/api/notices');
    d.notices.filter((n) => n.read_flag === 0)
      .forEach((n) => api('/api/notices/read', { method: 'POST', body: { id: n.id } }).catch(() => {}));
    return head(p.title || '공지사항', { home: true }) + (p.header || '') + `
      ${d.canEdit ? `<div class="card">
        <input id="ntitle" placeholder="제목" style="margin-bottom:10px">
        <textarea id="nbody" style="min-height:120px" placeholder="내용"></textarea>
        <label class="cb"><input type="checkbox" id="npin">맨 위에 고정</label>
        <button class="btn" data-act="add">공지 올리기</button>
      </div>` : ''}
      <div id="out"></div>
      ${d.notices.length ? d.notices.map((n) => `<div class="card">
        <div style="display:flex;align-items:center;gap:6px">
          ${n.pinned ? '<span class="pill">고정</span>' : ''}
          ${n.read_flag === 0 ? '<span class="pill red">새 글</span>' : ''}
          <span style="flex:1"></span><span class="meta">${shortTime(n.created_at)}</span>
        </div>
        ${n.title ? `<div style="font-size:calc(20px*var(--fs));font-weight:700;margin-top:8px">${esc(n.title)}</div>` : ''}
        <div style="margin-top:8px">${nl(n.body)}</div>
        <div class="bar"><span class="meta">${esc(n.author_name||'')} · ${n.read_count}명 읽음</span>
          <span class="sp"></span>
          ${d.canEdit ? `<button data-act="deln" data-id="${n.id}" style="color:var(--danger)">지움</button>` : ''}</div>
      </div>`).join('') : empty('아직 올라온 공지가 없습니다.')}`;
  },
  async click(act, el) {
    if (act === 'add') {
      const body = $('#nbody').value.trim();
      if (!body) return $('#out').innerHTML = msg('내용을 적어 주세요');
      try {
        await api('/api/notices/save', { method: 'POST', body: {
          title: $('#ntitle').value.trim(), body, pinned: $('#npin').checked } });
        render();
      } catch (e) { $('#out').innerHTML = msg(e.message); }
    }
    if (act === 'deln') {
      if (!confirm('공지를 지울까요?')) return;
      await api('/api/notices/delete', { method: 'POST', body: { id: +el.dataset.id } }); render();
    }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  주보 (사진 자동 축소 후 업로드)
// ═══════════════════════════════════════════════════════════════════════
function thisSunday() {
  const d = new Date(); d.setDate(d.getDate() - d.getDay());
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;
}
const humanSize = (b) => b >= 1048576 ? (b/1048576).toFixed(1) + 'MB'
  : b >= 1024 ? Math.round(b/1024) + 'KB' : b + 'B';

async function shrinkImage(file, maxSide = 2200, maxBytes = 4 * 1024 * 1024) {
  if (!file.type.startsWith('image/')) return { blob: file, type: file.type };
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  if (scale === 1 && file.size <= maxBytes) return { blob: file, type: file.type };
  const cv = document.createElement('canvas');
  cv.width = Math.round(bmp.width * scale); cv.height = Math.round(bmp.height * scale);
  cv.getContext('2d').drawImage(bmp, 0, 0, cv.width, cv.height);
  let q = 0.9, blob;
  do {
    blob = await new Promise((r) => cv.toBlob(r, 'image/jpeg', q));
    q -= 0.1;
  } while (blob && blob.size > maxBytes && q > 0.45);
  return { blob, type: 'image/jpeg' };
}

VIEWS.bulletin = {
  async html(p = {}) {
    const d = await api('/api/bulletins');
    S.bulletins = d.bulletins;
    const row = (b) => `<div class="card">
      <div style="display:flex;align-items:center">
        <div style="flex:1">
          <div style="font-weight:600">${kfull(b.week_of)}</div>
          <div class="meta">${b.mime.includes('pdf') ? 'PDF' : '사진'} · ${humanSize(b.size)}${b.uploader ? ' · ' + esc(b.uploader) : ''}</div>
        </div>
        ${d.canEdit ? `<button data-act="delb" data-id="${b.id}" style="border:none;background:none;color:var(--danger);cursor:pointer">지움</button>` : ''}
      </div>
      <div style="height:10px"></div>
      <button class="btn soft" data-act="open" data-id="${b.id}" data-pdf="${b.mime.includes('pdf')?1:0}">주보 보기</button>
      <div class="view"></div>
    </div>`;
    return head(p.title || '주보', { home: true }) + (p.header || '') + `
      ${d.canEdit ? `<button class="btn" data-act="pick">주보 올리기
          <small>사진은 자동으로 줄여서 올립니다 (최대 10MB)</small></button>
        <input type="file" id="file" accept="image/png,image/jpeg,image/webp,application/pdf" style="display:none">
        <div style="height:16px"></div>` : ''}
      <div id="out"></div>
      ${d.bulletins.length ? `<h2 class="sec" style="margin-top:8px">이번 주 주보</h2>${row(d.bulletins[0])}
        ${d.bulletins.length > 1 ? `<h2 class="sec">지난 주보 보관함</h2>${d.bulletins.slice(1).map(row).join('')}` : ''}`
      : empty('아직 올라온 주보가 없습니다.')}`;
  },
  after() {
    const f = $('#file'); if (!f) return;
    f.onchange = async () => {
      const file = f.files[0]; if (!file) return;
      const out = $('#out');
      out.innerHTML = spin('올리는 중입니다');
      try {
        const { blob, type } = await shrinkImage(file);
        if (blob.size > 10 * 1024 * 1024)
          throw new Error(`파일이 너무 큽니다 (${humanSize(blob.size)}). 10MB 이하로 줄여 주세요.`);
        const week = thisSunday();
        const url = `/api/bulletins/upload?weekOf=${week}&title=${encodeURIComponent(week + ' 주보')}`;
        const res = await fetch(url, { method: 'POST',
          headers: { 'Content-Type': type, Authorization: 'Bearer ' + S.token }, body: blob });
        const j = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(j.error || '올리지 못했습니다');
        render();
      } catch (e) { out.innerHTML = msg(e.message); }
    };
  },
  async click(act, el) {
    const id = +el.dataset.id;
    if (act === 'pick') return $('#file').click();
    if (act === 'delb') {
      if (!confirm('주보를 지울까요?')) return;
      await api('/api/bulletins/delete', { method: 'POST', body: { id } }); return render();
    }
    if (act === 'open') {
      const box = $('.view', el.closest('.card'));
      box.innerHTML = spin('주보를 여는 중입니다');
      const res = await fetch(`/api/bulletins/${id}/file`, { headers: { Authorization: 'Bearer ' + S.token } });
      if (!res.ok) { box.innerHTML = msg('주보를 불러오지 못했습니다'); return; }
      const url = URL.createObjectURL(await res.blob());
      box.innerHTML = el.dataset.pdf === '1'
        ? `<div style="height:10px"></div><a class="btn soft" href="${url}" target="_blank"
             style="display:block;text-align:center;text-decoration:none;padding:16px">PDF 주보 열기</a>`
        : `<div style="height:10px"></div><img class="bulletin" src="${url}" alt="주보">`;
    }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  설정
// ═══════════════════════════════════════════════════════════════════════
// 설정 — 첫 화면은 분류만, 누르면 그 분류의 설정으로 들어갑니다
//   화면 · 글씨 / 알림 / 내 정보 / 교회 관리 / 앱 운영자 / 문의 · 신고
const SETTING_SECTIONS = {
  display: '화면 · 글씨', notify: '알림', account: '내 정보',
  church: '교회 관리', platform: '앱 운영자', help: '문의 · 신고',
};
const menuRow = (sec, title, sub) => `<button class="menu" data-act="go" data-view="setsec"
    data-params='${JSON.stringify({ sec })}'>
    <span style="flex:1"><b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</span>
    <span class="arr">›</span></button>`;

VIEWS.settings = {
  html() {
    const me = S.me;
    const pending = S.home?.pendingApprovals || 0;
    return head('설정', { home: true }) + `
      <div class="card">
        <div style="font-size:calc(21px*var(--fs));font-weight:700">${esc(me.name)} ${esc(me.title)}</div>
        <div class="meta">${esc(me.churchName)}${me.cellName ? ' · ' + esc(me.cellName) : ''}${me.role !== 'member' ? ' · ' + ROLE_LABEL[me.role] : ''}</div>
      </div>
      <button class="menu ${isSenior() ? 'on' : ''}" data-act="senior">
        <span style="flex:1"><b>시니어 모드</b><small>${isSenior() ? '큰 글씨·큰 버튼 화면을 쓰고 있습니다' : '켜면 큰 글씨·큰 버튼으로 단순하게 보입니다'}</small></span>
        <span class="sw ${isSenior() ? 'on' : ''}"><i></i></span></button>
      <h2 class="sec">나에게 맞추기</h2>
      ${menuRow('display', '화면 · 글씨', '글씨 크기, 묵상 본문 링크')}
      ${menuRow('notify', '알림', '새 나눔·공지 알림 받기')}
      ${menuRow('account', '내 정보', '이름 · 비밀번호, 묵상 기록 내려받기')}
      ${me.canManage || me.isPlatformAdmin ? '<h2 class="sec">관리</h2>' : ''}
      ${me.canManage ? menuRow('church', '교회 관리',
        (pending ? `가입 신청 ${pending}건 · ` : '') + '성도 · 셀 · 직분' + (me.isOwner ? ' · 줌 링크' : '')) : ''}
      ${me.isPlatformAdmin ? menuRow('platform', '앱 운영자', '교회 등록 심사, 건의함') : ''}
      <h2 class="sec">도움</h2>
      ${menuRow('help', '문의 · 신고', '불편한 점, 부적절한 글 알리기')}
      <div style="height:20px"></div>
      <button class="btn danger" data-act="logout">로그아웃</button>`;
  },
  async click(act) {
    if (act === 'logout') await logout();
    if (act === 'senior') {
      // 모드를 바꾸면 글씨 크기도 그 모드의 기본으로 (켜기 → 보통, 끄기 → 가장 작게)
      LS.set('senior', isSenior() ? '0' : '1');
      LS.set('fs', defaultFs());
      applyFont(); render();
    }
  },
};

async function logout() {
  await api('/api/auth/logout', { method: 'POST' }).catch(() => {});
  S.token = null; S.me = null; LS.del('token'); LS.del('boot'); S.stack = [];
  go('pick', {}, true);
}

VIEWS.setsec = {
  html(p) {
    const me = S.me; const sec = p.sec;
    const title = SETTING_SECTIONS[sec] || '설정';
    const btn = (view, label, sub, extra = '') => `<button class="btn plain" data-act="go" data-view="${view}"
      style="margin-bottom:10px" ${extra}>${label}${sub ? `<small>${sub}</small>` : ''}</button>`;
    let body = '';
    if (sec === 'display') body = `
      <h2 class="sec" style="margin-top:4px">글씨 크기</h2>
      <div class="card">
        <div>이 글씨가 잘 보이시나요?</div>
        <input type="range" id="fs" min="0.85" max="1.6" step="0.15" value="${LS.get('fs', defaultFs())}"
          style="width:100%;padding:0;margin-top:12px">
        <div style="display:flex"><span class="meta">작게</span><span style="flex:1"></span><span class="meta">크게</span></div>
      </div>
      <h2 class="sec">묵상 본문</h2>
      ${btn('bible', '묵상 본문 링크 변경', '이 기기에서만 바뀝니다')}`;
    else if (sec === 'notify') body = `<div class="card" id="pushbox">${spin('알림 설정을 읽는 중입니다')}</div>`;
    else if (sec === 'account') body = `
      ${btn('profile', '이름 · 비밀번호 바꾸기')}
      <button class="btn plain" data-act="export" data-scope="mine" style="margin-bottom:10px">내 묵상 기록 전체 내려받기<small>글 파일로 저장됩니다</small></button>
      <div id="out"></div>
      <p class="meta" style="margin-top:20px">${esc(me.churchName)} · ${esc(me.name)} ${esc(me.title)}<br>
        소속 셀: ${esc(me.cellName || '없음')}<br>
        권한: ${ROLE_LABEL[me.role] || '성도'} ·
        나눔 범위: ${me.shareScope === 'all_cells' ? '셀 선택 가능' : '본인 셀만'}</p>
      <div style="height:12px"></div>
      <button class="btn danger" data-act="logout">로그아웃</button>`;
    else if (sec === 'church') {
      if (!me.canManage) body = empty('관리 권한이 있는 분만 볼 수 있습니다.');
      else {
        const pending = S.home?.pendingApprovals || 0;
        body = `
        <h2 class="sec" style="margin-top:4px">성도</h2>
        <button class="btn ${pending ? 'soft' : 'plain'}" data-act="go" data-view="members" style="margin-bottom:10px">교회 성도 / 셀 관리
          <small>${pending ? `가입 신청 ${pending}건이 기다리고 있습니다` : '가입 승인 · 직분 · 셀 · 권한'}</small></button>
        ${btn('audit', '직분 · 셀 변경 기록')}
        <button class="btn plain" data-act="export" data-scope="church" style="margin-bottom:10px">교회 전체 묵상 기록 내려받기</button>
        <div id="out"></div>
        ${me.isOwner ? `<h2 class="sec">교회 설정 (관리자)</h2>
          ${btn('zoom', '교회 줌 링크 설정')}
          ${btn('members', '나눔 범위 · 권한 주기', '성도 목록에서 사람마다 바꿉니다')}` : ''}
        <p class="hint" style="margin-top:16px">관리자는 담임목사님과 같은 모든 권한을 가집니다.
          목사님이 가입하시면 성도 목록에서 권한을 ‘관리자’로 바꿔 드리세요.</p>`;
      }
    }
    else if (sec === 'platform') body = me.isPlatformAdmin ? `
      ${btn('platform', '교회 등록 신청 심사')}
      ${btn('suggestions', '건의함 보기')}` : empty('앱 운영자만 볼 수 있습니다.');
    else body = `
      <p class="meta" style="margin-top:0">고쳤으면 하는 점, 불편한 점을 적어 주세요. 앱 만든 사람에게만 전달됩니다.</p>
      <p class="hint">부적절한 글·댓글, 아동 안전과 관련된 문제도 여기로 신고해 주시면 24시간 안에 확인합니다.</p>
      <label class="f"><textarea id="sug" style="min-height:140px" placeholder="적을 내용"></textarea></label>
      <button class="btn" data-act="suggest">보내기</button>
      <div id="out"></div>`;
    return head(title, { home: true }) + body;
  },
  async after(p) {
    if (p.sec === 'display') $('#fs').oninput = (e) => { LS.set('fs', e.target.value); applyFont(); };
    if (p.sec === 'notify') await drawPushBox();
  },
  async click(act, el) {
    const out = $('#out');
    if (act === 'logout') return logout();
    if (act === 'pushon' || act === 'pushoff') {
      const box = $('#pushbox');
      try {
        if (act === 'pushon') {
          const cfg = await api('/api/push/config');
          if (!cfg.vapidPublicKey) throw new Error('서버에 알림 설정이 아직 없습니다.');
          await pushOn(cfg.vapidPublicKey);
        } else await pushOff();
        await drawPushBox();
      } catch (e) { box.innerHTML = msg(e.message) + box.innerHTML; }
      return;
    }
    if (act === 'pref') {
      const key = el.dataset.k;
      await api('/api/push/prefs', { method: 'POST', body: { [key]: el.checked } }).catch(() => {});
      return;
    }
    if (act === 'suggest') {
      const t = $('#sug').value.trim(); if (!t) return;
      try {
        await api('/api/suggestions', { method: 'POST', body: { body: t } });
        $('#sug').value = ''; out.innerHTML = msg('보내 주셔서 감사합니다.', true);
      } catch (e) { out.innerHTML = msg(e.message); }
    }
    if (act === 'export') {
      const scope = el.dataset.scope;
      out.innerHTML = spin('기록을 모으는 중입니다');
      try {
        const d = await api('/api/entries/export', { params: { scope } });
        const lines = [
          scope === 'church' ? `${d.churchName} 묵상 기록` : `${d.name} 묵상 기록`,
          `내려받은 날: ${kfull(todayStr())}`,
          `모두 ${d.entries.length}편`, '', '─'.repeat(30), '',
        ];
        for (const e of d.entries) {
          lines.push(kfull(e.date) + (e.member_name ? ` · ${e.member_name} ${e.member_title || ''}` : ''));
          if (e.title) lines.push(`[${e.title}]`);
          lines.push(e.body, '', '─'.repeat(30), '');
        }
        const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `묵상기록-${scope === 'church' ? d.churchName : d.name}-${todayStr()}.txt`;
        a.click();
        out.innerHTML = msg(`${d.entries.length}편을 파일로 저장했습니다.`, true);
      } catch (e) { out.innerHTML = msg(e.message); }
    }
  },
};

async function drawPushBox() {
  const box = $('#pushbox'); if (!box) return;
  let cfg;
  try { cfg = await api('/api/push/config'); }
  catch (e) { box.innerHTML = msg(e.message); return; }

  const supported = 'serviceWorker' in navigator && 'PushManager' in window;
  const standalone = window.matchMedia('(display-mode: standalone)').matches ||
    window.navigator.standalone === true;
  const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent);

  if (!supported) {
    box.innerHTML = `<div class="meta">이 브라우저는 알림을 지원하지 않습니다.</div>`;
    return;
  }
  if (isIOS && !standalone) {
    box.innerHTML = `<div>아이폰에서는 <b>홈 화면에 추가</b>한 뒤에만 알림을 받을 수 있습니다.</div>
      <div class="meta" style="margin-top:6px">사파리 아래 공유 버튼 → 홈 화면에 추가 → 그 아이콘으로 들어와서 다시 켜 주세요.</div>`;
    return;
  }

  const reg = await navigator.serviceWorker.ready.catch(() => null);
  const sub = reg ? await reg.pushManager.getSubscription() : null;
  const on = !!sub && cfg.registered;
  if (on) tellWorkerToken();

  box.innerHTML = `
    <div style="display:flex;align-items:center;gap:10px">
      <div style="flex:1"><b>${on ? '알림이 켜져 있습니다' : '알림 받기'}</b>
        <div class="meta">${on ? '새 나눔·공지가 오면 알려 드립니다' : '이 기기에서 알림을 받습니다'}</div></div>
    </div>
    <div style="height:10px"></div>
    <button class="btn ${on ? 'plain' : 'soft'}" data-act="${on ? 'pushoff' : 'pushon'}">
      ${on ? '이 기기에서 알림 끄기' : '알림 켜기'}</button>
    ${on ? `<div style="height:14px"></div>
      <label class="cb"><input type="checkbox" data-act="pref" data-k="share" ${cfg.notifyShare ? 'checked' : ''}>
        묵상 나눔 · 기도제목 · 댓글</label>
      <label class="cb"><input type="checkbox" data-act="pref" data-k="board" ${cfg.notifyBoard ? 'checked' : ''}>
        공지사항 · 주보</label>
      <label class="cb"><input type="checkbox" data-act="pref" data-k="admin" ${cfg.notifyAdmin ? 'checked' : ''}>
        가입 신청 · 내 정보 변경</label>` : ''}`;
}

VIEWS.profile = {
  html() {
    return head('개인정보 변경') + `
      <h2 class="sec" style="margin-top:12px">이름</h2>
      <label class="f"><input id="name" value="${esc(S.me.name)}"></label>
      <button class="btn soft" data-act="rename">이름 바꾸기</button>
      <h2 class="sec">비밀번호</h2>
      <label class="f"><span>지금 쓰는 비밀번호</span><input id="cur" type="password" inputmode="numeric" maxlength="6"></label>
      <label class="f"><span>새 비밀번호 (숫자 6자리)</span><input id="np" type="password" inputmode="numeric" maxlength="6"></label>
      <label class="f"><span>새 비밀번호 다시</span><input id="np2" type="password" inputmode="numeric" maxlength="6"></label>
      <button class="btn soft" data-act="repin">비밀번호 바꾸기</button>
      <div id="out"></div>`;
  },
  async click(act) {
    const out = $('#out'); const v = (id) => $('#' + id).value.trim();
    try {
      if (act === 'rename') {
        if (!v('name')) throw new Error('이름을 적어 주세요');
        await api('/api/me/update', { method: 'POST', body: { name: v('name') } });
        saveBoot(await api('/api/bootstrap')); LS.set('lastName', v('name'));
        out.innerHTML = msg('이름을 바꿨습니다.', true);
      }
      if (act === 'repin') {
        if (v('np').length !== 6) throw new Error('새 비밀번호는 숫자 6자리여야 합니다');
        if (v('np') !== v('np2')) throw new Error('새 비밀번호 두 개가 서로 다릅니다');
        await api('/api/me/update', { method: 'POST', body: { currentPin: v('cur'), newPin: v('np') } });
        $('#cur').value = $('#np').value = $('#np2').value = '';
        out.innerHTML = msg('비밀번호를 바꿨습니다.', true);
      }
    } catch (e) { out.innerHTML = msg(e.message); }
  },
};

VIEWS.bible = {
  html() {
    return head('묵상 본문 링크') + `
      <div class="card">‘묵상 본문 보기’를 눌렀을 때 열리는 주소입니다.<br>
        바꿔도 <b>이 기기에서만</b> 바뀌고, 교회 전체에는 영향을 주지 않습니다.</div>
      <label class="f"><span>주소</span><textarea id="url" style="min-height:90px">${esc(LS.get('bibleUrl', DEFAULT_BIBLE))}</textarea></label>
      <button class="btn" data-act="save" style="margin-bottom:10px">이 주소로 저장</button>
      <button class="btn plain" data-act="reset">기본 주소로 되돌리기</button>
      <div id="out"></div>
      <p class="hint">기본값: 성서유니온 오늘의 말씀<br>${esc(DEFAULT_BIBLE)}</p>`;
  },
  click(act) {
    if (act === 'save') { LS.set('bibleUrl', $('#url').value.trim() || DEFAULT_BIBLE); $('#out').innerHTML = msg('저장했습니다.', true); }
    if (act === 'reset') { LS.set('bibleUrl', DEFAULT_BIBLE); $('#url').value = DEFAULT_BIBLE; $('#out').innerHTML = msg('기본 주소로 되돌렸습니다.', true); }
  },
};

VIEWS.zoom = {
  html() {
    return head('교회 줌 링크') + `
      <div class="card">줌에서 ‘내 개인 회의실’ 또는 예약한 회의의 초대 링크를 그대로 붙여 넣어 주세요.<br><br>
        예) https://us02web.zoom.us/j/1234567890</div>
      <label class="f"><span>줌 링크</span><textarea id="url" style="min-height:90px">${esc(S.me.zoomUrl || '')}</textarea></label>
      <div id="out"></div>
      <button class="btn" data-act="save" style="margin-bottom:10px">저장하기</button>
      <button class="btn danger" data-act="clear">링크 지우기</button>`;
  },
  async click(act) {
    const out = $('#out');
    try {
      const url = act === 'clear' ? '' : $('#url').value.trim();
      await api('/api/settings/zoom', { method: 'POST', body: { zoomUrl: url } });
      saveBoot(await api('/api/bootstrap'));
      if (act === 'clear') $('#url').value = '';
      out.innerHTML = msg(act === 'clear' ? '지웠습니다.' : '저장했습니다. 이제 홈에서 바로 들어갈 수 있습니다.', true);
    } catch (e) { out.innerHTML = msg(e.message); }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  교회 성도 / 셀 관리
// ═══════════════════════════════════════════════════════════════════════
const TITLES = ['목사','사모','장로','안수집사','권사','집사','성도'];
// owner = 관리자 (교회를 등록한 분 · 담임목사님과 같은 전체 권한, 여러 명 가능)
const ROLE_LABEL = { owner:'관리자', admin:'부관리자', leader:'셀장', member:'성도' };
const ROLE_DESC = {
  owner: '담임목사님과 같은 모든 권한 (직분·권한 주기, 가입 승인, 줌 링크)',
  admin: '성도·셀 관리, 가입 승인, 공지·주보',
  leader: '우리 셀 현황 보기',
  member: '나눔·기도제목·댓글',
};

VIEWS.members = {
  async html(p) {
    const tab = p.tab || 'people'; S.view.params = { tab };
    const [m, c] = await Promise.all([api('/api/members'), api('/api/cells')]);
    S.members = m.members; S.cells = c.cells; S.canGrantRole = m.canGrantRole;
    const pending = m.members.filter((x) => x.status === 'pending');
    const cellOpts = (sel) => `<option value="">셀 없음</option>` +
      c.cells.map((x) => `<option value="${x.id}" ${x.id === sel ? 'selected' : ''}>${esc(x.name)}</option>`).join('');
    return head('교회 성도 / 셀 관리', { home: true }) + `
      <div class="toggle">
        <button data-act="tab" data-v="people" class="${tab==='people'?'on':''}">성도</button>
        <button data-act="tab" data-v="cells" class="${tab==='cells'?'on':''}">셀</button>
      </div>
      <div id="out"></div>
      ${tab === 'people' ? `
        ${pending.length ? `<h2 class="sec" style="margin-top:4px">승인 기다리는 분 ${pending.length}명</h2>
          ${pending.length > 1 ? `<button class="btn" data-act="approveall" style="margin-bottom:12px">
            ${pending.length}명 모두 승인<small>각자 고른 셀로 들어갑니다</small></button>` : ''}
          ${pending.map((x) => `<div class="card" style="padding:14px">
            <b style="font-size:calc(19px*var(--fs))">${esc(x.name)}</b>
            <span class="meta" style="margin-left:6px">신청 ${shortTime(x.created_at)}</span>
            <div style="height:8px"></div>
            ${chipsHtml(c.cells, [], 'ap' + x.id)}
            <div class="row" style="margin-top:8px">
              <button class="btn danger" data-act="approve" data-id="${x.id}" data-v="0" style="flex:1;min-height:52px">거절</button>
              <button class="btn" data-act="approve" data-id="${x.id}" data-v="1" style="flex:2;min-height:52px">승인</button>
            </div></div>`).join('')}` : ''}
        <h2 class="sec">성도 ${m.members.filter((x)=>x.status==='active').length}명</h2>
        ${m.members.filter((x) => x.status !== 'pending').map((x) => `
          <div class="card" data-m="${x.id}">
            <div style="display:flex;align-items:center;gap:8px">
              <div style="flex:1">
                <b>${esc(x.name)} ${esc(x.title)}${x.id === S.me.id ? ' (나)' : ''}</b>
                ${x.status === 'inactive' ? '<span class="pill grey">중지</span>' : ''}
                <div class="meta">${esc(x.cell_name || '셀 없음')}${x.role!=='member' ? ' · ' + ROLE_LABEL[x.role] : ''}${x.share_scope==='all_cells' ? ' · 셀 선택 가능' : ''}</div>
              </div>
              ${m.canEdit && (x.role !== 'owner' || S.me.isOwner)
                ? `<button class="link" data-act="edit" data-id="${x.id}" style="border:none;background:none;font-size:calc(16px*var(--fs))">바꾸기</button>` : ''}
            </div>
            <div class="edit" style="display:none"></div>
          </div>`).join('')}
      ` : `
        ${m.canEdit ? `<div class="card">
          <b>셀 새로 만들기</b><div style="height:10px"></div>
          <input id="newcell" placeholder="셀 이름"><div style="height:10px"></div>
          <button class="btn soft" data-act="addcell">만들기</button></div>` : ''}
        ${c.cells.length ? c.cells.map((x) => `<div class="card" data-c="${x.id}">
          <div style="display:flex;align-items:center">
            <div style="flex:1"><b>${esc(x.name)}</b>
              <div class="meta">${x.member_count}명 · ${x.leader_name ? '셀장 ' + esc(x.leader_name) : '셀장 없음'}</div></div>
            ${m.canEdit ? `<button class="link" data-act="editcell" data-id="${x.id}"
              style="border:none;background:none;font-size:calc(16px*var(--fs))">바꾸기</button>` : ''}
          </div>
          <div class="edit" style="display:none"></div>
        </div>`).join('') : empty('아직 만든 셀이 없습니다.')}
      `}`;
  },
  async click(act, el) {
    const out = $('#out'); const id = +el.dataset.id;
    if (act === 'tab') return go('members', { tab: el.dataset.v }, true);

    if (act === 'approveall') {
      const pend = S.members.filter((x) => x.status === 'pending');
      if (!confirm(`기다리는 ${pend.length}명을 한 번에 승인할까요?\n셀을 고른 분은 그 셀로, 안 고른 분은 셀 없이 들어갑니다.`)) return;
      el.disabled = true;
      try {
        const r = await api('/api/members/approve-batch', { method: 'POST', body: {
          items: pend.map((x) => ({ memberId: x.id, cellIds: chipsValue('ap' + x.id) })) } });
        await render(); $('#out').innerHTML = msg(`${r.count}명을 승인했습니다.`, true);
      } catch (e) { out.innerHTML = msg(e.message); el.disabled = false; }
      return;
    }
    if (act === 'approve') {
      const cellIds = chipsValue('ap' + id);
      try {
        await api('/api/members/approve', { method: 'POST',
          body: { memberId: id, approve: el.dataset.v === '1', cellIds } });
        render();
      } catch (e) { out.innerHTML = msg(e.message); } return;
    }
    if (act === 'addcell') {
      const n = $('#newcell').value.trim(); if (!n) return;
      try { await api('/api/cells/save', { method: 'POST', body: { name: n } }); render(); }
      catch (e) { out.innerHTML = msg(e.message); } return;
    }

    if (act === 'edit') {
      const x = S.members.find((v) => v.id === id);
      const box = $('.edit', el.closest('[data-m]'));
      if (box.style.display === 'block') { box.style.display = 'none'; return; }
      box.style.display = 'block';
      box.innerHTML = `<div style="height:14px"></div>
        <label class="f"><span>직분</span><select id="e-title">
          ${TITLES.map((t)=>`<option ${t===x.title?'selected':''}>${t}</option>`).join('')}</select></label>
        <div class="f"><span class="meta">셀 (여러 개 고를 수 있습니다 · 옆으로 밀기)</span>
          ${chipsHtml(S.cells, x.cellIds || [], 'ecell')}</div>
        ${S.canGrantRole ? `
          <label class="f"><span>권한 (관리자만 바꿀 수 있습니다)</span><select id="e-role">
            ${['member','leader','admin','owner'].map((r)=>`<option value="${r}" ${r===x.role?'selected':''}>${ROLE_LABEL[r]}</option>`).join('')}</select>
            <div class="hint" id="e-roledesc">${esc(ROLE_DESC[x.role] || '')}</div></label>
          <label class="f"><span>나눔 범위</span><select id="e-scope">
            <option value="own_cell" ${x.share_scope==='own_cell'?'selected':''}>본인 셀만</option>
            <option value="all_cells" ${x.share_scope==='all_cells'?'selected':''}>셀 선택 가능</option></select></label>` : ''}
        <label class="cb"><input type="checkbox" id="e-off" ${x.status==='inactive'?'checked':''}>사용 중지 (이사 등)</label>
        <div style="height:10px"></div>
        <div class="row"><button class="btn" data-act="savem" data-id="${id}">저장</button>
          <button class="btn danger" data-act="resetpin" data-id="${id}">비밀번호 초기화</button></div>`;
      const rs = $('#e-role');
      if (rs) rs.onchange = () => { $('#e-roledesc').textContent = ROLE_DESC[rs.value] || ''; };
      return;
    }
    if (act === 'savem') {
      const x = S.members.find((v) => v.id === id);
      const body = { memberId: id };
      const t = $('#e-title').value; if (t !== x.title) body.title = t;
      const picked = chipsValue('ecell');
      const before = x.cellIds || [];
      const same = picked.length === before.length && picked.every((v) => before.includes(v));
      if (!same) body.cellIds = picked;
      if (S.canGrantRole) {
        const r = $('#e-role').value; if (r !== x.role) body.role = r;
        const sc = $('#e-scope').value; if (sc !== x.share_scope) body.shareScope = sc;
      }
      const st = $('#e-off').checked ? 'inactive' : 'active';
      if (st !== x.status) body.status = st;
      try { await api('/api/members/update', { method: 'POST', body }); render(); }
      catch (e) { out.innerHTML = msg(e.message); } return;
    }
    if (act === 'resetpin') {
      const x = S.members.find((v) => v.id === id);
      if (!confirm(`${x.name}님의 비밀번호를 초기화할까요?`)) return;
      try {
        const r = await api('/api/members/reset-pin', { method: 'POST', body: { memberId: id } });
        alert(`${x.name}님 임시 비밀번호: ${r.tempPin}\n\n본인에게 알려 주세요. 들어가면 새로 정하게 됩니다.`);
        render();
      } catch (e) { out.innerHTML = msg(e.message); } return;
    }

    if (act === 'editcell') {
      const c = S.cells.find((v) => v.id === id);
      const box = $('.edit', el.closest('[data-c]'));
      if (box.style.display === 'block') { box.style.display = 'none'; return; }
      box.style.display = 'block';
      box.innerHTML = `<div style="height:14px"></div>
        <label class="f"><span>셀 이름</span><input id="c-name" value="${esc(c.name)}"></label>
        <label class="f"><span>셀장</span><select id="c-leader"><option value="">셀장 없음</option>
          ${S.members.filter((m)=>m.status==='active').map((m)=>
            `<option value="${m.id}" ${m.id===c.leader_id?'selected':''}>${esc(m.name)} ${esc(m.title)}</option>`).join('')}
        </select></label>
        <div class="row"><button class="btn" data-act="savec" data-id="${id}">저장</button>
          <button class="btn danger" data-act="delcell" data-id="${id}">이 셀 지우기</button></div>`;
      return;
    }
    if (act === 'savec') {
      const c = S.cells.find((v) => v.id === id);
      const name = $('#c-name').value.trim();
      const lv = $('#c-leader').value; const leader = lv ? +lv : null;
      try {
        if (name && name !== c.name) await api('/api/cells/save', { method: 'POST', body: { id, name } });
        if (leader !== c.leader_id) await api('/api/cells/leader', { method: 'POST', body: { cellId: id, memberId: leader } });
        render();
      } catch (e) { out.innerHTML = msg(e.message); } return;
    }
    if (act === 'delcell') {
      if (!confirm('이 셀을 지울까요?\n속한 분들은 «셀 없음» 이 됩니다. 묵상 글은 그대로 남습니다.')) return;
      try { await api('/api/cells/delete', { method: 'POST', body: { id } }); render(); }
      catch (e) { out.innerHTML = msg(e.message); }
    }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  가입 신청 승인 · 변경 기록 · 셀 현황 · 건의함 · 교회 심사
// ═══════════════════════════════════════════════════════════════════════
VIEWS.audit = {
  async html() {
    const { logs } = await api('/api/audit');
    return head('변경 기록') + `
      <p class="hint">누가 언제 무엇을 바꿨는지 남겨 둡니다. 관리자와 부관리자만 볼 수 있습니다.</p>
      ${logs.length ? logs.map((l) => `<div class="card">
        <div style="display:flex"><b>${esc(l.action)}</b><span style="flex:1"></span>
          <span class="meta">${shortTime(l.created_at)}</span></div>
        <div class="meta">${esc(l.actor_name||'?')}${l.target_name ? ' → ' + esc(l.target_name) : ''}
          ${l.detail ? '<br>' + esc(l.detail) : ''}</div>
      </div>`).join('') : empty('아직 기록이 없습니다.')}`;
  },
};

VIEWS.cellstatus = {
  async html(p) {
    const cellId = p.cellId || S.me.cellId;
    S.view.params = { cellId };
    let cells = [];
    if (S.me.canManage) cells = (await api('/api/cells')).cells;
    else if ((S.me.cellIds || []).length > 1)
      cells = (S.cells || []).filter((c) => S.me.cellIds.includes(c.id));
    const d = await api('/api/cells/status', { params: { cellId } });
    const notYet = d.people.filter((x) => !x.wrote_today);
    return head(d.cellName || '우리 셀 현황') + `
      ${cells.length > 1 ? `<label class="f"><span>셀 고르기</span><select id="pick">
        ${cells.map((c)=>`<option value="${c.id}" ${c.id===cellId?'selected':''}>${esc(c.name)}</option>`).join('')}
      </select></label>` : ''}
      <p class="meta">${kfull(d.date)} 기준</p>
      <div class="card"><div class="meta">오늘 묵상</div>
        <div class="big">${d.people.filter((x)=>x.wrote_today).length} / ${d.people.length}명</div>
        ${notYet.length ? `<div style="height:10px"></div>
          <button class="btn soft" data-act="nudge">아직 안 하신 분들 챙기기<small>메시지로 보내기</small></button>` : ''}
      </div>
      <h2 class="sec">셀원</h2>
      ${d.people.map((x) => `<div class="card">
        <div style="display:flex;align-items:center">
          <div style="flex:1"><b>${esc(x.name)} ${esc(x.title)}</b>
            <div class="meta">최근 7일 ${x.week_count}회${x.last_date ? ' · 마지막 ' + kdate(x.last_date) : ' · 아직 없음'}</div></div>
          <span class="pill ${x.wrote_today?'':'grey'}">${x.wrote_today ? '오늘 했어요' : '아직'}</span>
        </div></div>`).join('')}`;
  },
  after() { const s = $('#pick'); if (s) s.onchange = () => go('cellstatus', { cellId: +s.value }, true); },
  async click(act) {
    if (act !== 'nudge') return;
    const d = await api('/api/cells/status', { params: { cellId: S.view.params.cellId } });
    const names = d.people.filter((x) => !x.wrote_today).map((x) => x.name).join(', ');
    const text = `${d.cellName || ''} 오늘 묵상 나눔 아직 안 하신 분: ${names}\n함께 은혜 나눠요.`;
    if (navigator.share) { try { await navigator.share({ text }); } catch {} }
    else { await navigator.clipboard?.writeText(text); alert('내용을 복사했습니다.'); }
  },
};

VIEWS.suggestions = {
  async html() {
    const { suggestions } = await api('/api/suggestions');
    return head('건의함') + `<p class="hint">앱 전체에 들어온 건의입니다. 앱 운영자만 볼 수 있습니다.</p>` +
      (suggestions.length ? suggestions.map((s) => `<div class="card">
        <div style="display:flex;gap:8px"><b>${esc(s.name || '익명')}</b>
          ${s.church_name ? `<span class="pill">${esc(s.church_name)}</span>` : ''}
          <span style="flex:1"></span><span class="meta">${shortTime(s.created_at)}</span></div>
        <div style="margin-top:8px">${nl(s.body)}</div></div>`).join('')
        : empty('아직 들어온 건의가 없습니다.'));
  },
};

VIEWS.platform = {
  async html(p) {
    const status = p.status || 'pending'; S.view.params = { status };
    const { churches } = await api('/api/platform/churches', { params: { status } });
    const f = (k, v) => v ? `<div style="display:flex;gap:8px;padding:2px 0">
      <span class="meta" style="width:92px;flex:none">${k}</span><span>${esc(v)}</span></div>` : '';
    return head('교회 등록 심사') + `
      <div class="toggle">
        <button data-act="st" data-v="pending" class="${status==='pending'?'on':''}">심사 대기</button>
        <button data-act="st" data-v="approved" class="${status==='approved'?'on':''}">승인된 교회</button>
      </div>
      <p class="hint">교단 명부에서 교회와 담임목사 이름을 확인해 보세요.
        확인이 안 되면 승인을 미루고 전화로 물어보는 편이 안전합니다.</p>
      <div id="out"></div>
      ${churches.length ? churches.map((c) => `<div class="card">
        <div style="font-size:calc(20px*var(--fs));font-weight:700">${esc(c.name)}
          ${c.invited_by_name ? `<span class="pill">초대: ${esc(c.invited_by_name)}</span>` : ''}</div>
        <div style="height:8px"></div>
        ${f('주소', c.address)}${f('전화', c.phone)}${f('교단', c.denomination)}
        ${f('담임목사', c.pastor_name)}${f('신청하신 분', c.applicant_name)}${f('신학교/안수', c.seminary)}${f('홈페이지', c.homepage)}${f('남긴 말', c.note)}
        <div class="meta" style="margin-top:6px">신청 ${shortTime(c.created_at)} · 등록 인원 ${c.member_count}명</div>
        ${c.homepage ? `<div style="height:10px"></div>
          <a class="btn plain" href="${esc(c.homepage.startsWith('http') ? c.homepage : 'https://' + c.homepage)}"
             target="_blank" style="display:block;text-align:center;text-decoration:none;padding:14px">홈페이지 열어보기</a>` : ''}
        <div style="height:12px"></div>
        ${status === 'pending' ? `<div class="row">
          <button class="btn" data-act="approve" data-id="${c.id}">승인</button>
          <button class="btn danger" data-act="reject" data-id="${c.id}">거절</button></div>`
        : `<button class="btn danger" data-act="suspend" data-id="${c.id}">사용 중지시키기</button>`}
      </div>`).join('') : empty('해당하는 교회가 없습니다.')}`;
  },
  async click(act, el) {
    if (act === 'st') return go('platform', { status: el.dataset.v }, true);
    const id = +el.dataset.id;
    const map = { approve: 'approved', reject: 'rejected', suspend: 'suspended' };
    if (!map[act]) return;
    let reason = null;
    if (act === 'reject') {
      reason = prompt('내부 메모 (신청자에게는 알려지지 않습니다)') ;
      if (reason === null) return;
    }
    try { await api('/api/platform/churches/review', { method: 'POST', body: { churchId: id, status: map[act], reason } }); render(); }
    catch (e) { $('#out').innerHTML = msg(e.message); }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  시작
// ═══════════════════════════════════════════════════════════════════════
(async function start() {
  applyFont();
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').then(tellWorkerToken).catch(() => {});
    navigator.serviceWorker.addEventListener('message', (e) => {
      const link = e.data?.link;
      if (link && VIEWS[link]) go(link);
      else if (link === 'home') goHome();
    });
  }
  if (!S.token) { S.view = { view: 'pick', params: {} }; return render(); }

  // 저장해 둔 값이 있으면 기다리지 않고 홈부터 띄웁니다
  const cached = loadBoot();
  if (cached && !cached.me.mustChangePin) {
    saveBoot(cached);
    S.view = { view: 'home', params: {} };
    return render();
  }
  try {
    const b = await api('/api/bootstrap');
    saveBoot(b);
    S.view = { view: b.me.mustChangePin ? 'changepin' : 'home', params: {} };
  } catch {
    S.token = null; LS.del('token'); LS.del('boot');
    S.view = { view: 'pick', params: {} };
  }
  render();
})();
