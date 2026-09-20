// ═══════════════════════════════════════════════════════════════════════
//  우리교회 웹앱 — 안드로이드 앱과 같은 서버, 같은 데이터를 씁니다.
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
async function api(path, { method = 'GET', body = null, params = null } = {}) {
  const url = new URL(path, location.origin);
  if (params) for (const [k, v] of Object.entries(params))
    if (v !== null && v !== undefined && v !== '') url.searchParams.set(k, v);
  const res = await fetch(url, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(S.token ? { Authorization: 'Bearer ' + S.token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = {}; }
  if (!res.ok) throw new Error(data.error || `서버에 연결하지 못했습니다 (${res.status})`);
  return data;
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

function head(title, { back = true, home = false, settings = false } = {}) {
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
  document.documentElement.style.setProperty('--fs', LS.get('fs', '1'));
}

// ── 화면 등록소 ──────────────────────────────────────────────────────────
const VIEWS = {};

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
  window.scrollTo(0, 0);
  if (fn.after) fn.after(v.params);
}

// 전역 클릭 처리
document.addEventListener('click', async (e) => {
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
        <small>담임목사님이 교회를 새로 등록합니다</small></button>`;
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
VIEWS.register = {
  html() {
    const f = (id, label, extra = '', hint = '') =>
      `<label class="f"><span>${label}</span><input id="${id}" ${extra}>
       ${hint ? `<div class="hint">${hint}</div>` : ''}</label>`;
    return head('우리 교회 등록') + `
      <p class="meta">담임목사님이 직접 신청해 주세요. 교회 정보를 확인한 뒤 승인해 드립니다.</p>
      <h2 class="sec">교회 정보</h2>
      ${f('churchName', '교회 이름 *')}
      ${f('address', '교회 주소 *')}
      ${f('phone', '교회 대표전화 *', 'inputmode="tel"')}
      ${f('denomination', '소속 교단 *', '', '예) 예장합동, 예장통합, 기감, 기장, 기침 …')}
      ${f('homepage', '홈페이지 또는 교단 교회검색 주소', '', '교단 명부에서 확인되면 승인이 빨라집니다')}
      <h2 class="sec">담임목사님 정보</h2>
      ${f('pastorName', '담임목사 성함 *')}
      ${f('seminary', '졸업 신학교 / 안수 교단')}
      <h2 class="sec">로그인에 쓸 비밀번호</h2>
      ${f('pin', '숫자 6자리 *', 'type="password" inputmode="numeric" maxlength="6"')}
      ${f('pin2', '숫자 6자리 다시 입력 *', 'type="password" inputmode="numeric" maxlength="6"')}
      <h2 class="sec">관리자 로그인 (권장)</h2>
      <p class="hint">목사님 계정은 성도 명단 전체를 볼 수 있어 더 안전하게 지켜야 합니다.</p>
      ${f('email', '이메일', 'type="email" autocapitalize="off"')}
      ${f('password', '비밀번호 (8자 이상)', 'type="password"')}
      <h2 class="sec">초대 코드 (있는 경우)</h2>
      ${f('inviteCode', '초대 코드', 'autocapitalize="characters"')}
      <label class="f"><span>남기실 말씀</span><textarea id="note" style="min-height:90px"></textarea></label>
      <div id="out"></div>
      <button class="btn" data-act="submit">등록 신청하기</button>`;
  },
  async click(act, el) {
    if (act !== 'submit') return;
    const val = (id) => ($('#' + id).value || '').trim();
    const out = $('#out');
    if (val('pin').length !== 6) return out.innerHTML = msg('비밀번호는 숫자 6자리여야 합니다');
    if (val('pin') !== val('pin2')) return out.innerHTML = msg('비밀번호 두 개가 서로 다릅니다');
    if (val('email') && val('password').length < 8)
      return out.innerHTML = msg('관리자 비밀번호는 8자 이상이어야 합니다');
    el.disabled = true; out.innerHTML = spin('신청하는 중입니다');
    try {
      await api('/api/churches/register', { method: 'POST', body: {
        churchName: val('churchName'), address: val('address'), phone: val('phone'),
        denomination: val('denomination'), pastorName: val('pastorName'),
        seminary: val('seminary'), homepage: val('homepage'), note: val('note'),
        pinNumber: val('pin'),
        email: val('email') || null, password: val('password') || null,
        inviteCode: val('inviteCode') || null,
      }});
      $('#app').innerHTML = head('신청 완료') + `<div class="card">
        <div style="font-size:calc(22px*var(--fs));font-weight:700">등록 신청이 접수되었습니다</div>
        <p>교회 정보를 확인한 뒤 승인해 드립니다. 보통 1~2일 걸립니다.<br>
        승인되면 입력하신 이름과 비밀번호로 로그인할 수 있습니다.</p>
      </div><button class="btn" data-act="home2">처음 화면으로</button>`;
      $('[data-act="home2"]').onclick = () => { S.stack = []; go('pick', {}, true); };
    } catch (e) { out.innerHTML = msg(e.message); el.disabled = false; }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  로그인 / 가입
// ═══════════════════════════════════════════════════════════════════════
VIEWS.login = {
  html(p) {
    const mode = p.mode || 'login';
    const lastName = LS.get('lastName', '');
    return head(p.churchName || '로그인') + `
      <div class="toggle">
        <button data-act="mode" data-v="login" class="${mode==='login'?'on':''}">로그인</button>
        <button data-act="mode" data-v="signup" class="${mode==='signup'?'on':''}">처음 왔어요</button>
      </div>
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
          ${mode === 'email' ? '이름과 숫자 비밀번호로 들어가기' : '목사님·관리자는 이메일로 들어가기'}</button>
        <p class="hint" style="text-align:center;margin-top:14px">
          비밀번호를 잊으셨으면 목사님께 말씀해 주세요.<br>새 비밀번호를 만들어 드립니다.</p>` : ''}`;
  },
  async click(act, el) {
    const p = S.view.params;
    if (act === 'mode') { S.view.params.mode = el.dataset.v; return render(); }
    if (act === 'emailmode') { S.view.params.mode = p.mode === 'email' ? 'login' : 'email'; return render(); }
    if (act !== 'submit') return;
    const out = $('#out'); const v = (id) => ($('#' + id)?.value || '').trim();
    el.disabled = true; out.innerHTML = '';
    try {
      if (p.mode === 'signup') {
        if (v('pin').length !== 6) throw new Error('비밀번호는 숫자 6자리여야 합니다');
        if (v('pin') !== v('pin2')) throw new Error('비밀번호 두 개가 서로 다릅니다');
        const r = await api('/api/members/signup', { method: 'POST',
          body: { churchId: p.churchId, name: v('name'), pinNumber: v('pin') } });
        out.innerHTML = msg(r.message || '목사님 승인 후 이용할 수 있습니다.', true);
        el.disabled = false; return;
      }
      const body = p.mode === 'email'
        ? { email: v('email'), password: v('password') }
        : { churchId: p.churchId, name: v('name'), pinNumber: v('pin') };
      if (p.mode !== 'email' && v('pin').length !== 6) throw new Error('비밀번호는 숫자 6자리여야 합니다');
      const r = await api('/api/auth/login', { method: 'POST', body });
      S.token = r.token; LS.set('token', r.token);
      if (p.mode !== 'email') LS.set('lastName', v('name'));
      S.me = await api('/api/me');
      S.stack = [];
      go(S.me.mustChangePin ? 'changepin' : 'home', {}, true);
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
      S.me = await api('/api/me');
      goHome();
    } catch (e) { out.innerHTML = msg(e.message); el.disabled = false; }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  홈
// ═══════════════════════════════════════════════════════════════════════
VIEWS.home = {
  async html() {
    const me = S.me = await api('/api/me');
    const [home, prog] = await Promise.all([
      api('/api/home').catch(() => ({})),
      api('/api/entries/progress').catch(() => null),
    ]);
    S.home = home;
    const q = prog ? prog.quarters[Math.floor((new Date().getMonth()) / 3)] : null;
    const notice = home.latestNotice;
    return head(me.churchName, { back: false, settings: true }) + `
      <div style="display:flex;align-items:center;gap:10px">
        <div style="font-size:calc(23px*var(--fs));font-weight:700">${esc(me.name)} ${esc(me.title)}</div>
        ${me.cellName ? `<span class="pill">${esc(me.cellName)}</span>` : ''}
      </div>
      <div class="meta">${kfull(todayStr())}</div>

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
      <button class="btn plain" data-act="go" data-view="prayer">기도제목 나눔</button>
      <div id="out"></div>

      ${me.canManage ? `<h2 class="sec">관리</h2>
        ${home.pendingApprovals ? `<button class="btn soft" data-act="go" data-view="approvals" style="margin-bottom:10px">
          가입 신청 ${home.pendingApprovals}건<small>승인을 기다리고 있습니다</small></button>` : ''}
        <button class="btn plain" data-act="go" data-view="members" style="margin-bottom:10px">교회 성도 / 셀 관리</button>
        <button class="btn plain" data-act="go" data-view="suggestions" style="margin-bottom:10px">건의함 보기</button>` : ''}
      ${(me.role === 'leader' || me.canManage) ? `<button class="btn plain" data-act="go" data-view="cellstatus" style="margin-bottom:10px">우리 셀 현황 보기</button>` : ''}
      ${me.isPlatformAdmin ? `<button class="btn plain" data-act="go" data-view="platform" style="margin-bottom:10px">교회 등록 신청 심사</button>` : ''}

      <h2 class="sec">앱에 바라는 점</h2>
      <label class="f"><textarea id="sug" style="min-height:90px" placeholder="고쳤으면 하는 점을 적어 주세요"></textarea></label>
      <button class="btn plain" data-act="suggest">보내기</button>
      <div style="height:26px"></div>
      <button class="btn danger" data-act="logout">로그아웃</button>`;
  },
  async click(act, el) {
    const out = $('#out');
    if (act === 'bible') window.open(LS.get('bibleUrl', DEFAULT_BIBLE), '_blank');
    if (act === 'zoom') {
      const url = S.home?.zoomUrl || S.me.zoomUrl;
      if (!url) out.innerHTML = msg(S.me.isOwner
        ? '아직 줌 링크가 없습니다. 설정 → 교회 줌 링크에서 등록해 주세요.'
        : '아직 줌 링크가 등록되지 않았습니다. 목사님께 말씀해 주세요.');
      else window.open(url, '_blank');
    }
    if (act === 'suggest') {
      const t = $('#sug').value.trim(); if (!t) return;
      try { await api('/api/suggestions', { method: 'POST', body: { body: t } });
        $('#sug').value = ''; out.innerHTML = msg('보내 주셔서 감사합니다.', true);
      } catch (e) { out.innerHTML = msg(e.message); }
    }
    if (act === 'logout') {
      await api('/api/auth/logout', { method: 'POST' }).catch(() => {});
      S.token = null; S.me = null; LS.del('token'); S.stack = [];
      go('pick', {}, true);
    }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  묵상 나눔하기
// ═══════════════════════════════════════════════════════════════════════
VIEWS.write = {
  async html(p) {
    const date = p.date || todayStr();
    S.view.params.date = date;
    const me = S.me;
    const canPick = me.shareScope === 'all_cells';
    if (canPick && !S.cells.length) S.cells = (await api('/api/cells')).cells;
    const mine = (await api('/api/entries/mine', { params: { from: date, to: date } })).entries[0];
    const draftKey = 'draft:' + date;
    const draft = mine ? null : JSON.parse(LS.get(draftKey, 'null'));
    const title = mine ? (mine.title || '') : (draft?.title || '');
    const body  = mine ? mine.body : (draft?.body || '');
    const isPublic = mine ? mine.is_public === 1 : true;
    return head('묵상 나눔하기') + `
      <div class="card">
        <div class="meta">날짜</div>
        <div style="font-size:calc(21px*var(--fs));font-weight:700;margin:4px 0 12px">${kfull(date)}</div>
        <div class="row">
          <button class="btn plain" data-act="day" data-n="-1" style="min-height:52px;font-size:calc(16px*var(--fs))">하루 앞</button>
          <button class="btn plain" data-act="today" style="min-height:52px;font-size:calc(16px*var(--fs))">오늘</button>
          <button class="btn plain" data-act="day" data-n="1" style="min-height:52px;font-size:calc(16px*var(--fs))">하루 뒤</button>
        </div>
      </div>
      ${mine ? msg('이 날짜에 쓰신 글을 불러왔습니다. 고치면 그대로 바뀝니다.', true)
             : (draft ? msg('쓰시다 만 글이 있어 그대로 불러왔습니다.', true) : '')}
      <label class="f"><span>제목 (없어도 됩니다)</span><input id="title" value="${esc(title)}"></label>
      <label class="f"><span>오늘 묵상한 내용</span><textarea id="body">${esc(body)}</textarea></label>
      <div class="card">
        <label class="cb"><input type="checkbox" id="pub" ${isPublic ? 'checked' : ''}>
          <span><b>함께 나누기</b><br><span class="meta">끄면 출석표에는 남지만 글은 나만 봅니다</span></span></label>
      </div>
      ${canPick && S.cells.length ? `<h2 class="sec">어느 셀에 나눌까요</h2>
        <div class="card">
          <label class="cb"><input type="checkbox" id="allcells"><b>모든 셀에 나누기</b></label>
          <div id="cellbox">${S.cells.map((c) => `<label class="cb">
            <input type="checkbox" class="cellchk" value="${c.id}" ${c.id === me.cellId ? 'checked' : ''}>
            ${esc(c.name)} <span class="meta">${c.member_count}명</span></label>`).join('')}</div>
        </div>` : (me.cellName ? `<p class="meta">${esc(me.cellName)} 안에서 나눠집니다.</p>` : '')}
      <div id="out"></div>
      <button class="btn" data-act="save">올리기</button>`;
  },
  after(p) {
    const save = () => LS.set('draft:' + p.date,
      JSON.stringify({ title: $('#title').value, body: $('#body').value }));
    let t;
    ['#title', '#body'].forEach((s) => $(s).addEventListener('input', () => {
      clearTimeout(t); t = setTimeout(save, 1200);
    }));
    const all = $('#allcells');
    if (all) all.addEventListener('change', () => {
      $('#cellbox').style.display = all.checked ? 'none' : 'block';
    });
  },
  async click(act, el) {
    const p = S.view.params;
    if (act === 'day') return go('write', { date: shiftDate(p.date, +el.dataset.n) }, true);
    if (act === 'today') return go('write', { date: todayStr() }, true);
    if (act !== 'save') return;
    const out = $('#out');
    const body = $('#body').value.trim();
    if (!body) return out.innerHTML = msg('묵상한 내용을 적어 주세요');
    const allCells = $('#allcells')?.checked || false;
    const cellIds = allCells ? null : $$('.cellchk').filter((c) => c.checked).map((c) => +c.value);
    el.disabled = true;
    try {
      await api('/api/entries/save', { method: 'POST', body: {
        date: p.date, title: $('#title').value.trim(), body,
        isPublic: $('#pub').checked,
        cellIds: cellIds && cellIds.length ? cellIds : null, allCells,
      }});
      LS.del('draft:' + p.date);
      goHome();
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
    <div style="margin-top:8px">${nl(e.body)}</div>
    <div class="bar">
      <button data-act="like" data-id="${e.id}" class="${e.liked ? 'on' : ''}">
        ♡ <span class="lc">${e.like_count || 0}</span></button>
      <button data-act="comments" data-id="${e.id}">댓글 <span class="cc">${e.comment_count || 0}</span></button>
      <span class="sp"></span>
      <button data-act="share" data-id="${e.id}">공유</button>
      ${mine ? `<button data-act="del" data-id="${e.id}" style="color:var(--danger)">지움</button>` : ''}
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
    const text = `${S.me.churchName} 묵상 나눔\n${kfull(e.date)} · ${e.member_name}\n\n` +
      (e.title ? e.title + '\n\n' : '') + e.body;
    if (navigator.share) { try { await navigator.share({ title: '묵상 나눔', text }); } catch {} }
    else { await navigator.clipboard?.writeText(text); alert('내용을 복사했습니다. 붙여넣어 보내세요.'); }
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
VIEWS.feed = {
  async html(p) {
    const date = p.date || todayStr(); const all = !!p.all;
    S.view.params = { date, all };
    const { entries } = await api('/api/entries/feed', { params: { date, scope: all ? 'all' : 'cell' } });
    S.list = entries;
    return head('오늘의 나눔', { home: true }) + `
      <div class="toggle">
        <button data-act="scope" data-v="0" class="${!all?'on':''}">${esc(S.me.cellName || '우리 셀')} 나눔</button>
        <button data-act="scope" data-v="1" class="${all?'on':''}">전체 나눔 보기</button>
      </div>
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:14px">
        <button class="btn plain" data-act="day" data-n="-1" style="min-height:48px;font-size:calc(15px*var(--fs))">앞날</button>
        <div style="flex:1.4;text-align:center;font-weight:600">${kdate(date)}</div>
        <button class="btn plain" data-act="day" data-n="1" style="min-height:48px;font-size:calc(15px*var(--fs))">다음날</button>
      </div>
      <div class="row" style="margin-bottom:18px">
        <button class="btn soft" data-act="go" data-view="attendance">묵상 출석표 보기</button>
        <button class="btn soft" data-act="go" data-view="search">묵상 찾기</button>
      </div>
      ${entries.length ? entries.map((e) => entryCard(e)).join('') : empty('아직 이 날 나눈 묵상이 없습니다.')}`;
  },
  async click(act, el) {
    const p = S.view.params;
    if (act === 'scope') return go('feed', { date: p.date, all: el.dataset.v === '1' }, true);
    if (act === 'day') {
      const d = shiftDate(p.date, +el.dataset.n);
      if (d > todayStr()) return;
      return go('feed', { date: d, all: p.all }, true);
    }
    await entryClick(act, el, S.list);
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  묵상 출석표 (분기 단위 · 일요일 시작 · 가로 흐름)
// ═══════════════════════════════════════════════════════════════════════
function monthBlock(year, month, done) {
  const first = new Date(year, month - 1, 1);
  const len = new Date(year, month, 0).getDate();
  const lead = first.getDay();                 // 0 = 일요일
  const weeks = Math.ceil((lead + len) / 7);
  const t = todayStr();
  let html = `<div class="month"><b>${month}월</b><div class="weeks">`;
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

VIEWS.attendance = {
  async html(p) {
    const now = new Date();
    const year = p.year || now.getFullYear();
    const quarter = p.quarter || Math.floor(now.getMonth() / 3) + 1;
    const all = !!p.all;
    S.view.params = { year, quarter, all };
    const d = await api('/api/entries/attendance', { params: { scope: all ? 'all' : 'cell', year, quarter } });
    const startMonth = (quarter - 1) * 3 + 1;
    return head('묵상 출석표', { home: true }) + `
      <div class="toggle">
        <button data-act="scope" data-v="0" class="${!all?'on':''}">${esc(S.me.cellName || '우리 셀')}</button>
        <button data-act="scope" data-v="1" class="${all?'on':''}">전체 보기</button>
      </div>
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:14px">
        <button class="btn plain" data-act="q" data-n="-1" style="min-height:46px;font-size:calc(14px*var(--fs))">지난 분기</button>
        <div style="flex:1.2;text-align:center;font-weight:600">${year}년 ${quarter}분기</div>
        <button class="btn plain" data-act="q" data-n="1" style="min-height:46px;font-size:calc(14px*var(--fs))">다음 분기</button>
      </div>
      <p class="hint">초록 점은 그날 묵상을 나눈 날입니다. 이름을 누르면 그분 묵상기록으로 갑니다.</p>
      ${d.people.length ? d.people.map((pp) => {
        const done = new Set(pp.dates);
        return `<div class="card">
          <div style="display:flex;align-items:center;gap:8px">
            <span class="link" data-act="person" data-id="${pp.id}" style="font-weight:600">${esc(pp.name)} ${esc(pp.title||'')}</span>
            ${pp.cell_name ? `<span class="pill">${esc(pp.cell_name)}</span>` : ''}
            <span class="sp" style="flex:1"></span>
            <span class="pill">${pp.dates.length}회</span>
          </div>
          <div style="height:12px"></div>
          <div class="grass">${[0,1,2].map((o) => monthBlock(year, startMonth + o, done)).join('')}</div>
        </div>`;
      }).join('') : empty('보여 드릴 사람이 없습니다.')}`;
  },
  click(act, el) {
    const p = S.view.params;
    if (act === 'scope') return go('attendance', { ...p, all: el.dataset.v === '1' }, true);
    if (act === 'person') return go('person', { memberId: +el.dataset.id });
    if (act === 'q') {
      let { year, quarter } = p; const n = +el.dataset.n;
      const now = new Date();
      if (n > 0 && year === now.getFullYear() && quarter === Math.floor(now.getMonth()/3)+1) return;
      quarter += n;
      if (quarter < 1) { quarter = 4; year--; } if (quarter > 4) { quarter = 1; year++; }
      go('attendance', { ...p, year, quarter }, true);
    }
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
    const all = !!p.all;
    return head('묵상 찾기', { home: true }) + `
      <label class="f"><span>찾을 말 (두 글자 이상)</span>
        <input id="q" value="${esc(p.q || '')}" autocomplete="off"></label>
      <div class="toggle">
        <button data-act="scope" data-v="0" class="${!all?'on':''}">${esc(S.me.cellName || '우리 셀')}</button>
        <button data-act="scope" data-v="1" class="${all?'on':''}">전체에서 찾기</button>
      </div>
      <div id="results"></div>`;
  },
  after(p) {
    const run = async () => {
      const q = $('#q').value.trim();
      if (q.length < 2) { $('#results').innerHTML = ''; return; }
      $('#results').innerHTML = spin('찾는 중입니다');
      try {
        const { entries } = await api('/api/entries/search',
          { params: { q, scope: p.all ? 'all' : 'cell' } });
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
    const all = !!p.all; S.view.params = { all };
    const me = S.me;
    if (me.shareScope === 'all_cells' && !S.cells.length) S.cells = (await api('/api/cells')).cells;
    const { prayers } = await api('/api/prayers', { params: { scope: all ? 'all' : 'cell' } });
    return head('기도제목', { home: true }) + `
      <div class="toggle">
        <button data-act="scope" data-v="0" class="${!all?'on':''}">${esc(me.cellName || '우리 셀')}</button>
        <button data-act="scope" data-v="1" class="${all?'on':''}">전체 보기</button>
      </div>
      <div class="card">
        <label class="f"><textarea id="pbody" style="min-height:100px" placeholder="함께 기도할 제목"></textarea></label>
        ${me.shareScope === 'all_cells' && S.cells.length ? `
          <label class="cb"><input type="checkbox" id="pall"><b>모든 셀에 나누기</b></label>
          <div id="pcells">${S.cells.map((c)=>`<label class="cb">
            <input type="checkbox" class="pchk" value="${c.id}" ${c.id===me.cellId?'checked':''}>${esc(c.name)}</label>`).join('')}</div>` : ''}
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
  after() {
    const all = $('#pall');
    if (all) all.onchange = () => { $('#pcells').style.display = all.checked ? 'none' : 'block'; };
  },
  async click(act, el) {
    const id = +el.dataset.id;
    if (act === 'scope') return go('prayer', { all: el.dataset.v === '1' }, true);
    if (act === 'add') {
      const t = $('#pbody').value.trim(); if (!t) return;
      const allCells = $('#pall')?.checked || false;
      const cellIds = allCells ? null : $$('.pchk').filter((c) => c.checked).map((c) => +c.value);
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
  async html() {
    const d = await api('/api/notices');
    d.notices.filter((n) => n.read_flag === 0)
      .forEach((n) => api('/api/notices/read', { method: 'POST', body: { id: n.id } }).catch(() => {}));
    return head('공지사항', { home: true }) + `
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
  async html() {
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
    return head('주보', { home: true }) + `
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
VIEWS.settings = {
  async html() {
    const me = S.me;
    let invite = '';
    if (me.isOwner) invite = (await api('/api/settings/invite').catch(() => ({}))).inviteCode || '';
    return head('설정', { home: true }) + `
      <h2 class="sec" style="margin-top:12px">글씨 크기</h2>
      <div class="card">
        <div>이 글씨가 잘 보이시나요?</div>
        <input type="range" id="fs" min="0.85" max="1.6" step="0.15" value="${LS.get('fs','1')}"
          style="width:100%;padding:0;margin-top:12px">
        <div style="display:flex"><span class="meta">작게</span><span style="flex:1"></span><span class="meta">크게</span></div>
      </div>

      <h2 class="sec">내 정보</h2>
      <button class="btn plain" data-act="go" data-view="profile" style="margin-bottom:10px">개인정보 변경<small>이름 · 비밀번호</small></button>
      <button class="btn plain" data-act="go" data-view="bible" style="margin-bottom:10px">묵상 본문 링크 변경<small>이 기기에서만 바뀝니다</small></button>

      ${me.canManage ? `<h2 class="sec">교회 관리</h2>
        <button class="btn plain" data-act="go" data-view="members" style="margin-bottom:10px">교회 성도 / 셀 관리</button>
        <button class="btn plain" data-act="go" data-view="approvals" style="margin-bottom:10px">가입 신청 승인</button>
        <button class="btn plain" data-act="go" data-view="notice" style="margin-bottom:10px">공지사항 · 주보 올리기</button>
        <button class="btn plain" data-act="go" data-view="audit" style="margin-bottom:10px">직분 · 셀 변경 기록</button>` : ''}

      ${me.isOwner ? `<h2 class="sec">목사님 전용</h2>
        <button class="btn plain" data-act="go" data-view="zoom" style="margin-bottom:10px">교회 줌 링크 설정</button>
        <button class="btn plain" data-act="go" data-view="members" style="margin-bottom:10px">나눔 범위 설정<small>누가 어느 셀에 나눌 수 있는지</small></button>
        ${invite ? `<div class="card"><div class="meta">다른 교회 초대 코드</div>
          <div class="big">${esc(invite)}</div>
          <div class="meta">아는 교회에 이 코드를 알려 주시면 등록 심사가 빨라집니다.</div></div>` : ''}` : ''}

      <div style="height:20px"></div>
      <p class="meta">${esc(me.churchName)} · ${esc(me.name)} ${esc(me.title)}<br>
        권한: ${({owner:'담임목사',admin:'관리 권한',leader:'셀장'}[me.role]) || '성도'} ·
        나눔 범위: ${me.shareScope === 'all_cells' ? '셀 선택 가능' : '본인 셀만'}</p>`;
  },
  after() {
    $('#fs').oninput = (e) => { LS.set('fs', e.target.value); applyFont(); };
  },
};

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
        S.me = await api('/api/me'); LS.set('lastName', v('name'));
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
      S.me = await api('/api/me');
      if (act === 'clear') $('#url').value = '';
      out.innerHTML = msg(act === 'clear' ? '지웠습니다.' : '저장했습니다. 이제 홈에서 바로 들어갈 수 있습니다.', true);
    } catch (e) { out.innerHTML = msg(e.message); }
  },
};

// ═══════════════════════════════════════════════════════════════════════
//  교회 성도 / 셀 관리
// ═══════════════════════════════════════════════════════════════════════
const TITLES = ['목사','사모','장로','안수집사','권사','집사','성도'];
const ROLE_LABEL = { owner:'담임목사', admin:'관리 권한', leader:'셀장', member:'성도' };

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
          ${pending.map((x) => `<div class="card">
            <b style="font-size:calc(19px*var(--fs))">${esc(x.name)}</b>
            <div style="height:10px"></div>
            <div class="row">
              <button class="btn" data-act="approve" data-id="${x.id}" data-v="1">승인</button>
              <button class="btn danger" data-act="approve" data-id="${x.id}" data-v="0">거절</button>
            </div></div>`).join('')}` : ''}
        <h2 class="sec">성도 ${m.members.filter((x)=>x.status==='active').length}명</h2>
        ${m.members.filter((x) => x.status !== 'pending').map((x) => `
          <div class="card" data-m="${x.id}">
            <div style="display:flex;align-items:center;gap:8px">
              <div style="flex:1">
                <b>${esc(x.name)} ${esc(x.title)}</b>
                ${x.status === 'inactive' ? '<span class="pill grey">중지</span>' : ''}
                <div class="meta">${esc(x.cell_name || '셀 없음')}${x.role!=='member' ? ' · ' + ROLE_LABEL[x.role] : ''}${x.share_scope==='all_cells' ? ' · 셀 선택 가능' : ''}</div>
              </div>
              ${m.canEdit && x.role !== 'owner'
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

    if (act === 'approve') {
      try { await api('/api/members/approve', { method: 'POST', body: { memberId: id, approve: el.dataset.v === '1' } }); render(); }
      catch (e) { out.innerHTML = msg(e.message); } return;
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
        <label class="f"><span>셀</span><select id="e-cell"><option value="">셀 없음</option>
          ${S.cells.map((c)=>`<option value="${c.id}" ${c.id===x.cell_id?'selected':''}>${esc(c.name)}</option>`).join('')}</select></label>
        ${S.canGrantRole ? `
          <label class="f"><span>권한 (목사님만 바꿀 수 있습니다)</span><select id="e-role">
            ${['member','leader','admin'].map((r)=>`<option value="${r}" ${r===x.role?'selected':''}>${ROLE_LABEL[r]}</option>`).join('')}</select></label>
          <label class="f"><span>나눔 범위</span><select id="e-scope">
            <option value="own_cell" ${x.share_scope==='own_cell'?'selected':''}>본인 셀만</option>
            <option value="all_cells" ${x.share_scope==='all_cells'?'selected':''}>셀 선택 가능</option></select></label>` : ''}
        <label class="cb"><input type="checkbox" id="e-off" ${x.status==='inactive'?'checked':''}>사용 중지 (이사 등)</label>
        <div style="height:10px"></div>
        <div class="row"><button class="btn" data-act="savem" data-id="${id}">저장</button>
          <button class="btn danger" data-act="resetpin" data-id="${id}">비밀번호 초기화</button></div>`;
      return;
    }
    if (act === 'savem') {
      const x = S.members.find((v) => v.id === id);
      const body = { memberId: id };
      const t = $('#e-title').value; if (t !== x.title) body.title = t;
      const cv = $('#e-cell').value; const cell = cv ? +cv : null;
      if (cell !== x.cell_id) body.cellId = cell;
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
VIEWS.approvals = {
  async html() {
    const [m, c] = await Promise.all([api('/api/members'), api('/api/cells')]);
    const pending = m.members.filter((x) => x.status === 'pending');
    return head('가입 신청 승인') + `<div id="out"></div>` + (pending.length ? `
      <p class="hint">승인할 때 셀을 함께 정해 두시면 바로 나눔을 할 수 있습니다.</p>
      ${pending.map((x) => `<div class="card">
        <b style="font-size:calc(20px*var(--fs))">${esc(x.name)}</b>
        <div class="meta">신청 ${shortTime(x.created_at)}</div><div style="height:12px"></div>
        <label class="f"><span>들어갈 셀</span><select class="pcell" data-id="${x.id}">
          <option value="">나중에 정하기</option>
          ${c.cells.map((cc)=>`<option value="${cc.id}">${esc(cc.name)}</option>`).join('')}</select></label>
        <div class="row">
          <button class="btn" data-act="ok" data-id="${x.id}">승인</button>
          <button class="btn danger" data-act="no" data-id="${x.id}">거절</button></div>
      </div>`).join('')}` : empty('승인을 기다리는 신청이 없습니다.'));
  },
  async click(act, el) {
    if (act !== 'ok' && act !== 'no') return;
    const id = +el.dataset.id;
    const sel = $(`.pcell[data-id="${id}"]`);
    try {
      await api('/api/members/approve', { method: 'POST',
        body: { memberId: id, approve: act === 'ok', cellId: sel?.value ? +sel.value : null } });
      render();
    } catch (e) { $('#out').innerHTML = msg(e.message); }
  },
};

VIEWS.audit = {
  async html() {
    const { logs } = await api('/api/audit');
    return head('변경 기록') + `
      <p class="hint">누가 언제 무엇을 바꿨는지 남겨 둡니다. 목사님과 관리 권한자만 볼 수 있습니다.</p>
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
    return head('건의함') + (suggestions.length ? suggestions.map((s) => `<div class="card">
      <div style="display:flex"><b>${esc(s.name || '익명')}</b><span style="flex:1"></span>
        <span class="meta">${shortTime(s.created_at)}</span></div>
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
        ${f('담임목사', c.pastor_name)}${f('신학교/안수', c.seminary)}${f('홈페이지', c.homepage)}${f('남긴 말', c.note)}
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
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  if (!S.token) { S.view = { view: 'pick', params: {} }; return render(); }
  try {
    S.me = await api('/api/me');
    S.view = { view: S.me.mustChangePin ? 'changepin' : 'home', params: {} };
  } catch {
    S.token = null; LS.del('token');
    S.view = { view: 'pick', params: {} };
  }
  render();
})();
