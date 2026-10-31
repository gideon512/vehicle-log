// Vehicle Log service worker. Bump VERSION on every release to trigger the update toast.
const VERSION = 'vlog-v1';
const ASSETS = ['./', 'index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(VERSION).then(c => c.addAll(ASSETS))); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('message', e => { if (e.data === 'SKIP_WAITING') self.skipWaiting(); });
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.mode === 'navigate') {
    // App shell: ignore ?trip=start etc. and #hash; serve cached index, refresh in background
    e.respondWith(caches.match('index.html').then(r => {
      const net = fetch(req).then(res => { if (res.ok) caches.open(VERSION).then(c => c.put('index.html', res.clone())); return res; }).catch(() => r);
      return r || net;
    }));
    return;
  }
  e.respondWith(caches.match(req, { ignoreSearch: true }).then(r => r || fetch(req)));
});
