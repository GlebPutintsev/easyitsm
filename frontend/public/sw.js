/*
 * Service worker портала (PRT-03): установка на телефон и открытие оболочки без сети.
 * API никогда не кэшируется: данные заявок всегда свежие и не остаются на устройстве.
 */
const SHELL = 'easyitsm-shell-v2';

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(['/', '/manifest.webmanifest', '/icon.svg'])));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k)))),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')) return;

  // Страницы: сеть, при отказе — сохранённая оболочка приложения.
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/')));
    return;
  }
  // Собранные файлы с хешем в имени неизменны: кэш, затем сеть.
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(event.request).then(
        (hit) =>
          hit ||
          fetch(event.request).then((res) => {
            if (res.ok) {
              const copy = res.clone();
              caches.open(SHELL).then((c) => c.put(event.request, copy));
            }
            return res;
          }),
      ),
    );
  }
});

/*
 * Web Push (NTF-02). Сервер шифрует { id, title, body, link } по RFC 8291; показываем системное
 * уведомление и по нажатию открываем ссылку в уже открытой вкладке или в новой.
 */
self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { title: event.data ? event.data.text() : '' };
  }
  const title = data.title || 'Easy ITSM';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || '',
      icon: '/icon.svg',
      badge: '/icon.svg',
      tag: data.id,
      data: { link: data.link || '/' },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const link = new URL((event.notification.data && event.notification.data.link) || '/', self.location.origin);
  if (link.origin !== self.location.origin) return;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const c of list) {
        if (new URL(c.url).origin === self.location.origin && 'focus' in c) {
          return c.focus().then((w) => (w && 'navigate' in w ? w.navigate(link.href) : undefined));
        }
      }
      return self.clients.openWindow(link.href);
    }),
  );
});
