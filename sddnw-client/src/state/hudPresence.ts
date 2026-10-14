import { useSyncExternalStore } from 'react';

/**
 * Видна ли нижняя полоса карты — редизайн главной сцены (07.10.2026, решение хозяина проекта).
 *
 * Значок звука и око наблюдения на карте живут в нижней полосе, под пунктом «Игра», а не в
 * углу поверх галактики. На прочих сценах (меню, бой, колония поверх карты) полосы не видно, и
 * значок звука обязан остаться доступным — там он по-прежнему стоит в углу. Полоса отмечает себя
 * здесь, а угловые значки спрашивают отметку.
 */
let mounted = 0;
const listeners = new Set<() => void>();

export const markHud = (): (() => void) => {
  mounted += 1;
  listeners.forEach((listener) => listener());
  return () => {
    mounted -= 1;
    listeners.forEach((listener) => listener());
  };
};

export const useHudShown = (): boolean =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => mounted > 0,
  );

/**
 * Сцена без углового значка звука — главное меню (редизайн, 07.10.2026, решение хозяина проекта):
 * звук там правят в личном кабинете, а значок занимал верхний угол рядом с названием игры.
 */
let quiet = 0;

export const markQuietCorner = (): (() => void) => {
  quiet += 1;
  listeners.forEach((listener) => listener());
  return () => {
    quiet -= 1;
    listeners.forEach((listener) => listener());
  };
};

export const useQuietCorner = (): boolean =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => quiet > 0,
  );
