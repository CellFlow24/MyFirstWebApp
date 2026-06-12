const CACHE_NAME = 'dipsum-v7';
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
  // Use traditional indexOf to avoid any strict IDE validation issues
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

// Listen for application update triggers
self.addEventListener('message', function(event) {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});
