// 껍데기만 캐시합니다. 글·명단 같은 내용은 항상 서버에서 새로 받아옵니다.
const CACHE = 'woori-v7';
const SHELL = ['/', '/app.js', '/manifest.webmanifest',
               '/icon-192.png', '/icon-512.png', '/icon-180.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  // API 는 절대 캐시하지 않습니다
  if (url.pathname.startsWith('/api/') || e.request.method !== 'GET') return;
  // 묵상 사진·주소 찾기·다른 사이트는 브라우저에 맡깁니다 (사진은 브라우저가 알아서 오래 저장)
  if (url.origin !== location.origin || url.pathname.startsWith('/img/') || url.pathname === '/postcode.html') return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('/')))
  );
});

// ── 알림 ─────────────────────────────────────────────────────────────────
// 내용 없이 '깨우기'만 받고, 내용은 서버에서 받아 옵니다.
self.addEventListener('push', (e) => {
  e.waitUntil((async () => {
    let title = '모두의 교회', body = '새 소식이 있습니다.', link = '';
    try {
      const res = await fetch('/api/notifications', { credentials: 'omit',
        headers: await authHeader() });
      if (res.ok) {
        const j = await res.json();
        const n = (j.notifications || []).find((x) => !x.read_flag) || (j.notifications || [])[0];
        if (n) { title = n.title; body = n.body; link = n.link || ''; }
      }
    } catch {}
    await self.registration.showNotification(title, {
      body, icon: '/icon-192.png', badge: '/icon-192.png',
      tag: 'woori', renotify: true, data: { link },
    });
  })());
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const open = all.find((c) => c.url.startsWith(self.registration.scope));
    if (open) { open.focus(); open.postMessage({ type: 'open', link: e.notification.data?.link }); }
    else await self.clients.openWindow('/');
  })());
});

// 서비스워커는 localStorage 를 못 읽어서, 앱이 알려 준 토큰을 여기에 보관합니다.
let AUTH = null;
self.addEventListener('message', (e) => {
  if (e.data?.type === 'token') AUTH = e.data.token;
});
async function authHeader() {
  return AUTH ? { Authorization: 'Bearer ' + AUTH } : {};
}
