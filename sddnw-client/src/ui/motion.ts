import { useSyncExternalStore } from 'react';

/**
 * Меньше движения — field-backlog, пункт 170.
 *
 * Вращение Млечного Пути и Галактики на обложке, мерцание, огонь двигателей и дыхание лучей
 * людям с вестибулярными расстройствами и мигренью мешают, а заодно едят батарею. Решает
 * игрок: «как в системе» (пусто — так по умолчанию, `prefers-reduced-motion`), «меньше» или
 * «всё». Ответ один на CSS и на скрипты: CSS слушает признак `data-motion` у корня (одно
 * правило в `index.css`), скрипты спрашивают {@link reducedMotion} — двух правд о том, можно
 * ли двигаться, быть не должно.
 * <p>
 * Выбор живёт там же, где размер текста: в `localStorage` для первого кадра и в учётной
 * записи (`account.reduce_motion`), откуда приходит при входе на любой машине.
 */
export type MotionPreference = boolean | undefined;

const STORAGE_KEY = 'sddnw.settings.reduceMotion';

let preference: MotionPreference;
let version = 0;
const listeners = new Set<() => void>();

const systemReduces = (): boolean =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

/** Убирать ли движение сейчас: выбор игрока, а без него — система. */
export const reducedMotion = (): boolean => preference ?? systemReduces();

/** Что выбрал игрок: пусто — «как в системе». */
export const motionPreference = (): MotionPreference => preference;

const paint = (): void => {
  document.documentElement.dataset.motion = reducedMotion() ? 'reduce' : 'full';
  version += 1;
  listeners.forEach((listener) => listener());
};

/** Применить выбор: признак корня, браузер и подписчики. */
export const applyMotion = (value: MotionPreference): void => {
  preference = value;
  try {
    if (value === undefined) {
      window.localStorage.removeItem(STORAGE_KEY);
    } else {
      window.localStorage.setItem(STORAGE_KEY, String(value));
    }
  } catch {
    // Хранилище закрыто — выбор живёт до перезагрузки, а запись его вернёт при входе.
  }
  paint();
};

/** Первый кадр — из браузера; заодно слушаем смену системной настройки. */
export const restoreMotion = (): void => {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    preference = stored === 'true' ? true : stored === 'false' ? false : undefined;
  } catch {
    preference = undefined;
  }
  window.matchMedia?.('(prefers-reduced-motion: reduce)').addEventListener?.('change', paint);
  paint();
};

/** Подписка компонента: перерисоваться, когда движение включили или убрали. */
export const useReducedMotion = (): boolean => {
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );
  return reducedMotion();
};
