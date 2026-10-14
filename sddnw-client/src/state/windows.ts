import { useSyncExternalStore } from 'react';
import { gameApi } from '../api/client';
import { readAccount, writeAccount } from './session';

/**
 * Окна, которые открываются сами, — настройкой (field-backlog, пункт 164).
 *
 * Разведанная система, экран науки после прорыва и сцены редких событий открываются сами: для
 * новичка это обучение, а к сотому ходу — помеха, которую закрывают не глядя. По умолчанию
 * открываются все; приглушённое живёт в учётной записи, как раскладка клавиш, и правка ложится
 * в браузер сразу, а на сервер уходит следом — так же, как подсказки (`state/hints.ts`).
 *
 * Приглушённое окно не пропадает: о нём остаётся строка итогов хода, а открыть его можно
 * обычным путём — щелчком по звезде, кнопкой науки.
 */
export type AutoWindow = 'explored' | 'research' | 'stories' | 'council' | 'theft' | 'proposals' | 'artifacts';

/** Окна по порядку для экрана настроек. */
export const AUTO_WINDOWS: AutoWindow[] = ['explored', 'research', 'stories', 'council', 'theft', 'proposals', 'artifacts'];

let version = 0;
const listeners = new Set<() => void>();
const bump = () => {
  version += 1;
  listeners.forEach((listener) => listener());
};

/** Подписка компонента на настройку окон. */
export const useQuietWindows = (): number =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );

const quietList = (): string[] => readAccount()?.account.quietWindows ?? [];

/** Открывается ли окно само: пока игрок не приглушил — да. */
export const opensItself = (window: AutoWindow): boolean => !quietList().includes(window);

export const setOpensItself = (window: AutoWindow, on: boolean): void => {
  const session = readAccount();
  if (!session) {
    return;
  }
  const quiet = on ? quietList().filter((key) => key !== window) : [...new Set([...quietList(), window])];
  writeAccount({ ...session, account: { ...session.account, quietWindows: quiet } });
  bump();
  gameApi
    .saveQuietWindows(quiet)
    .then((account) => {
      const current = readAccount();
      if (current) {
        writeAccount({ ...current, account });
        bump();
      }
    })
    .catch(() => undefined);
};
