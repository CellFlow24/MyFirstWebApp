const CACHE_NAME = 'dipsum-v3'; 
const ASSETS = [
  '/index.html',
  '/dipsum-logo.png',
  '/manifest.json'
];

// Install Service Worker
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(ASSETS);
    })
  );
  self.skipWaiting(); // Forces the new service worker to activate immediately
});

// Activate and clean up old caches
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
      );
    })
  );
  self.clients.claim();
});

// Fetch: Network-First Strategy with API Bypass
self.addEventListener('fetch', event => {
  // 🚨 NEW: Completely bypass the Service Worker for backend API calls
  if (event.request.url.includes('/api/')) {
    return; // Let the browser communicate natively with the Java backend
  }

  // Only intercept basic web page assets
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request)
      .catch(() => {
        // If the network fails (offline), fall back to the cache
        return caches.match(event.request);
      })
  );
});
