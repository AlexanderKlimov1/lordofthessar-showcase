/*
  Сервис-воркер игры — field-backlog, пункты 193 и 22.

  Первое его дело — чтобы браузер признал игру приложением и предложил поставить её на рабочий
  стол, в меню «Пуск» и на домашний экран планшета. Работа без сети игре не нужна — партия
  считается на сервере, — поэтому здесь нет кэша: каждый запрос уходит в сеть как есть, и
  устаревшей сборки у игрока не бывает.

  Второе — уведомления о ходе, когда вкладка игры закрыта (Web Push, пункт 22): сервер шлёт
  зашифрованное письмецо службе уведомлений браузера, та будит этот воркер, и он показывает
  уведомление. Если игра открыта и на виду, уведомление не нужно — игрок и так видит ход.
*/
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {
  // Пустой обработчик: браузер требует его для установки, а отвечает сеть.
});

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (broken) {
    data = {};
  }
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      if (windows.some((client) => client.visibilityState === 'visible' && client.focused)) {
        return undefined;
      }
      return self.registration.showNotification(data.title || 'Lord of Thessar', {
        body: data.body || '',
        // Один знак на партию: новое уведомление о той же партии заменяет прежнее, а не копится.
        tag: data.tag || 'turn',
        icon: '/favicon.svg',
        data: { url: '/' },
      });
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((client) => 'focus' in client);
      return open ? open.focus() : self.clients.openWindow('/');
    }),
  );
});
