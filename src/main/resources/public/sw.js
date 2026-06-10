const CACHE_NAME = 'dipsum-v4';
const ASSETS = [
  '/index.html',
  '/dipsum-logo.png',
  '/manifest.json',
  '/sw.js'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  if (event.request.url.includes('/api/')) return;
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request))
  );
});

self.addEventListener('push', event => {
  const promise = (async () => {
    let title = 'Dipsum';
    let body = 'You have a new message';
    let url = '/';

    if (event.data) {
      try {
        const data = event.data.json();
        title = data.title || title;
        body = data.body || body;
        url = data.url || url;
      } catch (e) {
        body = event.data.text();
      }
    }

    // Force show - this bypasses Chrome's quiet notification UI
    await self.registration.showNotification(title, {
      body: body,
      icon: '/dipsum-logo.png',
      badge: '/dipsum-logo.png',
      vibrate: [300, 100, 300, 100, 300],
      requireInteraction: true,
      tag: 'dipsum-msg-' + Date.now(), // ✅ Unique tag = every message shows separately
      renotify: true,
      silent: false,
      data: { url: url }
    });
  })();

  // CRITICAL: Must pass the promise or Android kills the SW before notification shows
  event.waitUntil(promise);
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      for (const client of list) {
        if (client.url.includes('/') && 'focus' in client) return client.focus();
      }
      return clients.openWindow('/');
    })
  );
});
