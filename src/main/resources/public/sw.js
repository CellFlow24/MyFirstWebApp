const CACHE_NAME = 'dipsum-v8';
const ASSETS = [
  '/index.html',
  '/dipsum-logo.png',
  '/manifest.json',
  '/sw.js'
];

// Force fetch directly from the network during installation
self.addEventListener('install', function(event) {
  event.waitUntil(
    caches.open(CACHE_NAME).then(function(cache) {
      return Promise.all(
        ASSETS.map(function(url) {
          return fetch(new Request(url, { cache: 'reload' })).then(function(response) {
            if (!response.ok) throw new Error('Request failed for ' + url);
            return cache.put(url, response);
          });
        })
      );
    })
  );
  self.skipWaiting();
});

// Clean up old version caches automatically
self.addEventListener('activate', function(event) {
  event.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(
        keys.filter(function(k) { return k !== CACHE_NAME; }).map(function(k) { 
          return caches.delete(k); 
        })
      );
    })
  );
  self.clients.claim();
});

// Network-first fetch strategy (Completely ignores API traffic)
self.addEventListener('fetch', function(event) {
  // Bypasses caching for chat data updates entirely to ensure live messaging works
  if (event.request.url.indexOf('/api/') !== -1) return;
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request).catch(function() {
      return caches.match(event.request).then(function(cachedResponse) {
        return cachedResponse || new Response('Offline', { status: 503 });
      });
    })
  );
});

// Listen for application updates
self.addEventListener('message', function(event) {
  if (event.data === 'skipWaiting') {
    self.skipWaiting();
  }
});

// ==========================================
// 🔔 PUSH NOTIFICATION ENGINE (Restored)
// ==========================================

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
      tag: 'dipsum-msg-' + Date.now(), // Unique tag = every message shows separately
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
