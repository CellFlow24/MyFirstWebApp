const CACHE_NAME = 'app-cache-v1';
const ASSETS = [
  '/',
  '/index.html',
  '/css/style.css', // Adjust these paths to match your actual frontend files
  '/js/app.js',
  '/manifest.json'
];

// 1. Install Event - Core assets are cached for offline availability
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      console.log('[Service Worker] Pre-caching core assets');
      return cache.addAll(ASSETS);
    })
  );
  self.skipWaiting(); // Force the waiting service worker to become active immediately
});

// 2. Activate Event - Clears out old caches if CACHE_NAME changes
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(cacheNames => {
      return Promise.all(
        cacheNames.map(cache => {
          if (cache !== CACHE_NAME) {
            console.log('[Service Worker] Clearing old cache:', cache);
            return caches.delete(cache);
          }
        })
      );
    })
  );
  return self.clients.claim(); // Take control of all open pages immediately
});

// 3. Fetch Event - Network-First strategy with Cache Fallback
self.addEventListener('fetch', event => {
  // 🚨 BYPASS FOR BACKEND API: Do not intercept or cache any backend communication routes
  if (event.request.url.includes('/api/') || event.request.url.includes('/login') || event.request.url.includes('/logout')) {
    return; // Let the browser handle these requests natively over the live network
  }

  // Only handle standard asset GET requests (HTML, CSS, JS, Images)
  if (event.request.method !== 'GET') return;

  event.respondWith(
    // Always attempt a fresh network request first
    fetch(event.request)
      .then(response => {
        // If the response is valid, dynamically update our cache with the latest version
        if (response && response.status === 200) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then(cache => {
            cache.put(event.request, responseClone);
          });
        }
        return response;
      })
      .catch(() => {
        // FALLBACK: If the network is completely unreachable (offline), load from cache
        console.log('[Service Worker] Network failed, serving from cache:', event.request.url);
        return caches.match(event.request).then(cachedResponse => {
          if (cachedResponse) {
            return cachedResponse;
          }
          
          // If the specific file isn't in cache, fallback to index.html for SPA routing
          if (event.request.headers.get('accept').includes('text/html')) {
            return caches.match('/index.html');
          }
        });
      })
  );
});
