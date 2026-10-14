import { useSyncExternalStore } from 'react';

/**
 * Действие ушло на сервер — field-backlog, пункт 73.
 *
 * Нажатие без ответа повторяют или считают поломкой. Ответ сервера обычно приходит за десятки
 * миллисекунд, но на медленной связи или под нагрузкой — за секунды, и тогда игроку нужен хотя
 * бы знак «идёт». Считается это в ОДНОМ месте — в `request` (`api/client.ts`): каждое
 * изменяющее действие любого экрана проходит через него, и экраны поодиночке об этом не
 * помнят. Конец хода сюда не входит: у него своё, подробное «идёт» на кнопке хода.
 */
let pending = 0;
let since: number | null = null;
let version = 0;
const listeners = new Set<() => void>();

const bump = () => {
  version += 1;
  listeners.forEach((listener) => listener());
};

export const beginAction = (): void => {
  pending += 1;
  if (pending === 1) {
    since = Date.now();
    bump();
  }
};

export const endAction = (): void => {
  pending = Math.max(0, pending - 1);
  if (pending === 0) {
    since = null;
    bump();
  }
};

/** С какого мига ждём ответа; пусто — ничего не ждём. */
export const usePendingSince = (): number | null => {
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );
  return since;
};
