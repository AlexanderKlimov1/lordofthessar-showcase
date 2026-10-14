import { useSyncExternalStore } from 'react';

/**
 * Установка игры как приложения — field-backlog, пункты 193 и 203.
 *
 * Браузер сам предлагает установку только значком в адресной строке, которого игрок не ищет.
 * Поэтому событие `beforeinstallprompt` перехватывается ПРИ ЗАГРУЗКЕ страницы (`armInstall` в
 * `main.tsx` — позже оно уже пролетит) и придерживается: кнопка «Установить игру» в главном
 * меню и на экране входа зовёт `prompt()`. После `appinstalled` кнопка уходит навсегда. Где
 * события нет вовсе (Safari, Firefox, игра уже открыта в своём окне — `display-mode:
 * standalone`), кнопки нет, а в настройках — строка, как поставить вручную.
 */
interface InstallPrompt extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const INSTALLED_KEY = 'sddnw.installed';

let deferred: InstallPrompt | null = null;
let version = 0;
const listeners = new Set<() => void>();
const bump = () => {
  version += 1;
  listeners.forEach((listener) => listener());
};

/** Игра уже открыта как приложение — в своём окне. */
export const runningInstalled = (): boolean =>
  window.matchMedia?.('(display-mode: standalone)').matches === true
  || (navigator as Navigator & { standalone?: boolean }).standalone === true;

const markInstalled = () => {
  try {
    window.localStorage.setItem(INSTALLED_KEY, 'true');
  } catch {
    // без отметки кнопка просто не придёт: события после установки браузер не шлёт
  }
};

export const armInstall = (): void => {
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    deferred = event as InstallPrompt;
    bump();
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    markInstalled();
    bump();
  });
};

/** Можно ли предложить установку своей кнопкой. */
export const canInstall = (): boolean => deferred !== null && !runningInstalled();

export const install = async (): Promise<void> => {
  if (!deferred) {
    return;
  }
  const event = deferred;
  deferred = null;
  bump();
  await event.prompt();
  const choice = await event.userChoice.catch(() => ({ outcome: 'dismissed' as const }));
  if (choice.outcome === 'accepted') {
    markInstalled();
  }
};

export const useCanInstall = (): boolean => {
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );
  return canInstall();
};
