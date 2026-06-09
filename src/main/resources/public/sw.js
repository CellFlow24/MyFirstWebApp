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
  // 🚨 Completely bypass the Service Worker for backend API calls
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

// 🔔 NEW: Handle Native Web Push Notifications (Even when screen is locked/off)
self.addEventListener('push', event => {
  let data = { title: 'New Message', body: 'You received a new text context.' };

  // Safely parse the payload sent from your backend server
  if (event.data) {
    try {
      data = event.data.json();
    } catch (e) {
      data = { title: 'New Message', body: event.data.text() };
    }
  }

  const options = {
    body: data.body,
    icon: '/dipsum-logo.png',   // App icon displayed on the side
    badge: '/dipsum-logo.png',  // Tiny status bar icon for Android
    vibrate: [200, 100, 200],    // Haptic vibration pattern when screen is locked
    data: {
      url: '/'                  // Deep-link context data mapping
    }
  };

  event.waitUntil(
    self.registration.showNotification(data.title, options)
  );
});

// 🚀 NEW: Handle Tapping the Notification banner on the phone
self.addEventListener('notificationclick', event => {
  event.notification.close(); // Instantly dim and remove the banner from tray

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clientList => {
      // If the app tab is already running background-frozen, bring it back forward
      for (const client of clientList) {
        if (client.url.includes('/') && 'focus' in client) {
          return client.focus();
        }
      }
      // If the browser tab was completely swiped away, launch a fresh instance
      if (clients.openWindow) {
        return clients.openWindow('/');
      }
    })
  );
});
