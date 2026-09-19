/* WCHNT live service worker.
   Precache the app shell; stale-while-revalidate everything else on this origin.
   Bump CACHE when a hard flush is needed after deploy. */
const CACHE = "wchnt-live-v8";

const PRECACHE = [
  "./",
  "./index.html",
  "./css/live.css",
  "./harness.js",
  "./js/main.js",
  "./manifest.webmanifest",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-192-maskable.png",
  "./icons/icon-512-maskable.png",
  "./vendor/codemirror/codemirror.min.css",
  "./vendor/codemirror/material-darker.min.css",
  "./vendor/codemirror/codemirror.min.js",
  "./vendor/codemirror/overlay.min.js",
  "./vendor/codemirror/xml.min.js",
  "./vendor/codemirror/markdown.min.js"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(PRECACHE)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

function staleWhileRevalidate(request) {
  return caches.open(CACHE).then((cache) =>
    cache.match(request).then((cached) => {
      const fetched = fetch(request)
        .then((response) => {
          if (response && response.ok) {
            cache.put(request, response.clone());
          }
          return response;
        })
        .catch(() => cached);
      return cached || fetched;
    })
  );
}

function networkFirst(request) {
  return fetch(request)
    .then((response) => {
      if (response && response.ok) {
        caches.open(CACHE).then((cache) => cache.put(request, response.clone()));
      }
      return response;
    })
    .catch(() => caches.match(request));
}

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") {
    return;
  }
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) {
    return;
  }
  // Test runner is not part of the installed app.
  if (url.pathname.indexOf("tests.html") !== -1 ||
      url.pathname.indexOf("/tests.js") !== -1 ||
      url.pathname.indexOf("test-examples/") !== -1) {
    return;
  }
  if (url.pathname.indexOf("/seed/") !== -1) {
    event.respondWith(networkFirst(event.request));
    return;
  }
  event.respondWith(staleWhileRevalidate(event.request));
});
