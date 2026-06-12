const CACHE_NAME = 'dipsum-v6';
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
            if (!response.ok) throw new Error(`Request failed for ${url}`);
            return cache.put(url, response);
          });
        })
      );
    })
  );
  self.skipWaiting();
});

// Clean up old caches (like v5) when v6 activates
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

// Network-first fetch strategy (Bypasses API calls entirely)
self.addEventListener('fetch', function(event) {
  // 1. NEVER intercept API calls. Let the frontend handle connection issues.
  if (event.request.url.includes('/api/')) return;
  
  // 2. Only cache GET requests.
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request).catch(function() {
      // Pure Promise chain to prevent IDE syntax errors
      return caches.match(event.request).then(function(cachedResponse) {
        return cachedResponse || new Response('Offline', { status: 503 });
      });
    })
  );
});

// Listen for connectivity changes
self.addEventListener('message', function(event) {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

// Pure ES5/ES6 syntax for Push Notifications (No async/await wrapper to clear IDE errors)
self.addEventListener('push', function(event) {
  var title = 'Dipsum';
  var body = 'You have a new message';
  var url = '/';

  if (event.data) {
    try {
      var data = event.data.json();
      title = data.title || title;
      body = data.body || body;
      url = data.url || url;
    } catch (e) {
      body = event.data.text();
    }
  }

  // Passing the notification directly into waitUntil guarantees the IDE understands the Promise chain
  event.waitUntil(
    self.registration.showNotification(title, {
      body: body,
      icon: '/dipsum-logo.png',
      badge: '/dipsum-logo.png',
      vibrate: [300, 100, 300, 100, 300],
      requireInteraction: true,
      tag: 'dipsum-msg-' + Date.now(), // Unique tag = every message shows separately
      renotify: true,
      silent: false,
      data: { url: url }
    })
  );
});

// Handle user clicking on the notification
self.addEventListener('notificationclick', function(event) {
  event.notification.close();
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function(list) {
      for (var i = 0; i < list.length; i++) {
        var client = list[i];
        if (client.url.includes('/') && 'focus' in client) return client.focus();
      }
      return clients.openWindow('/');
    })
  );
});
