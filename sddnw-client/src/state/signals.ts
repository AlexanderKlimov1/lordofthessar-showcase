import { useSyncExternalStore } from 'react';

/**
 * Пришёл короткий знак — field-backlog, пункт 183: подписка партии (`SIGNAL`) дёргает счётчик, а
 * панель реплик и всплывающая строка перечитывают последние знаки сами. Текста в событии нет — его
 * переводит словарь читателя, — поэтому и хранить здесь нечего, кроме «было новое».
 */
let tick = 0;
const listeners = new Set<() => void>();

export const bumpSignals = (): void => {
  tick += 1;
  listeners.forEach((listener) => listener());
};

export const useSignalTick = (): number =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => tick,
  );
