/**
 * Уведомления браузера о ходе — field-backlog, пункт 22 (браузерная половина).
 *
 * В сетевой партии часы считают ход без игрока, а через три минуты отсутствия его империю ведёт
 * ИИ: партия уходит вперёд, а человек, переключившийся на другую вкладку, об этом не знает. Здесь —
 * уведомление браузера, пока вкладка игры открыта, но не на виду: «ход посчитан» и «соперники
 * закончили ход». Правила подсказки (пункты 22 и 178): только по согласию, по умолчанию тишина;
 * зовём, только когда действие доступно; не больше одного зова каждого рода на ход; без имён —
 * «Петя ждёт только вас» это давление через других.
 *
 * Согласие живёт в браузере, а не в учётной записи, и это нарочно: разрешение на уведомления
 * браузер даёт сайту на ЭТОЙ машине, и запись согласия на другой машине ничего бы там не включила.
 * Закрытую вкладку достаёт Web Push (`subscribePush`): браузер подписывается у своей службы
 * уведомлений, сервер шлёт туда зов, сервис-воркер (`public/sw.js`) его показывает. Пока подписка
 * есть, зов из открытой вкладки молчит — иначе одно и то же пришло бы дважды. Письмо — отдельное
 * согласие в учётной записи (`NotifySettings`).
 */
const KEY = 'sddnw.notify.turn';
/** Этот браузер подписан на Web Push: зов из вкладки тогда не нужен. */
const PUSH_KEY = 'sddnw.notify.push';

const sent = new Set<string>();

/** Умеет ли браузер уведомления вообще. */
export const notifyAvailable = (): boolean => typeof window !== 'undefined' && 'Notification' in window;

/** Игрок согласился, и браузер разрешил. */
export const notifyEnabled = (): boolean => {
  if (!notifyAvailable() || Notification.permission !== 'granted') {
    return false;
  }
  try {
    return window.localStorage.getItem(KEY) === 'on';
  } catch {
    return false;
  }
};

/** Включить: браузер спросит разрешения сам; отказ браузера — выключено. */
export const enableNotify = async (): Promise<boolean> => {
  if (!notifyAvailable()) {
    return false;
  }
  const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
  try {
    window.localStorage.setItem(KEY, permission === 'granted' ? 'on' : 'off');
  } catch {
    // Хранилище закрыто: согласие проживёт до перезагрузки страницы.
  }
  return permission === 'granted';
};

export const disableNotify = (): void => {
  try {
    window.localStorage.setItem(KEY, 'off');
  } catch {
    // См. выше.
  }
};

/**
 * Позвать — один раз на ключ (род, партия, ход) и только когда вкладка не на виду: смотрящему на
 * игру уведомление ничего не скажет, кроме того, что он и так видит.
 */
export const notifyOnce = (key: string, title: string, body: string): void => {
  if (sent.has(key) || !notifyEnabled() || !document.hidden || pushActive()) {
    return;
  }
  sent.add(key);
  try {
    const shown = new Notification(title, { body, tag: key, icon: '/favicon.svg' });
    shown.onclick = () => {
      window.focus();
      shown.close();
    };
  } catch {
    // Некоторые браузеры уведомляют только через сервис-воркер; без него зов пропускается молча.
  }
};

const readFlag = (key: string): boolean => {
  try {
    return window.localStorage.getItem(key) === 'on';
  } catch {
    return false;
  }
};

const writeFlag = (key: string, on: boolean): void => {
  try {
    window.localStorage.setItem(key, on ? 'on' : 'off');
  } catch {
    // Хранилище закрыто: флаг проживёт до перезагрузки страницы — и зов придёт дважды, не больше.
  }
};

/** Подписан ли этот браузер на зов при закрытой вкладке. */
export const pushActive = (): boolean => readFlag(PUSH_KEY);

/** Умеет ли браузер Web Push: нужны сервис-воркер и PushManager (у Safari — только у установленной игры). */
export const pushAvailable = (): boolean =>
  typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof window !== 'undefined' && 'PushManager' in window;

const keyBytes = (base64url: string): ArrayBuffer => {
  const padded = base64url.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (base64url.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
};

/**
 * Подписать браузер на зов при закрытой вкладке — пункт 22. Ключ сервера (VAPID) приходит с
 * сервера; подписку браузер отдаёт адресом своей службы и двумя ключами — их и хранит сервер.
 */
export const subscribePush = async (
  serverKey: string,
  save: (endpoint: string, p256dh: string, auth: string) => Promise<void>,
): Promise<boolean> => {
  if (!pushAvailable()) {
    return false;
  }
  const registration = await navigator.serviceWorker.ready;
  const subscription = (await registration.pushManager.getSubscription())
    ?? await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(serverKey) });
  const json = subscription.toJSON() as { endpoint?: string; keys?: { p256dh?: string; auth?: string } };
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) {
    return false;
  }
  await save(json.endpoint, json.keys.p256dh, json.keys.auth);
  writeFlag(PUSH_KEY, true);
  return true;
};

/** Отписать браузер: и у службы уведомлений, и у сервера. */
export const unsubscribePush = async (forget: (endpoint: string) => Promise<void>): Promise<void> => {
  writeFlag(PUSH_KEY, false);
  if (!pushAvailable()) {
    return;
  }
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.getSubscription();
  if (subscription) {
    await forget(subscription.endpoint).catch(() => undefined);
    await subscription.unsubscribe().catch(() => false);
  }
};
