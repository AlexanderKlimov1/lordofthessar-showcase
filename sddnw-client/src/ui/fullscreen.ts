import { useSyncExternalStore } from 'react';

/**
 * Полноэкранный режим — field-backlog, пункт 189: первая дыра, найденная сверкой ожиданий
 * жанра (`docs/genre-expectations.md`). Стратегию в браузере ждут во весь экран — без адресной
 * строки и вкладок, — а F11 игре не принадлежит: её держит браузер, и назначить её нельзя
 * (`assignable`). Поэтому свой пункт в меню «Игра» и своя клавиша в раскладке (F9 по умолчанию).
 *
 * Включается только нажатием игрока — браузер иначе откажет, — поэтому зовут его из
 * обработчика нажатия или клавиши, а не из эффекта. Выход — тот же пункт, та же клавиша или
 * Esc: Esc в полноэкранном режиме забирает себе браузер, и это его правило, а не наше.
 */
export const fullscreenSupported = (): boolean =>
  typeof document !== 'undefined' && document.fullscreenEnabled === true;

export const isFullscreen = (): boolean =>
  typeof document !== 'undefined' && document.fullscreenElement !== null;

export const toggleFullscreen = (): void => {
  if (!fullscreenSupported()) {
    return;
  }
  // Отказ браузера (нет жеста, запрещено политикой) — не поломка игры: режим просто не сменился.
  const done = isFullscreen()
    ? document.exitFullscreen()
    : document.documentElement.requestFullscreen({ navigationUI: 'hide' });
  void done.catch(() => undefined);
};

const subscribe = (listener: () => void): (() => void) => {
  document.addEventListener('fullscreenchange', listener);
  return () => document.removeEventListener('fullscreenchange', listener);
};

/** Включён ли полноэкранный режим — пункт меню пишет «войти» или «выйти». */
export const useFullscreen = (): boolean => useSyncExternalStore(subscribe, isFullscreen, () => false);
