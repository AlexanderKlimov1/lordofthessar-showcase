import { useSyncExternalStore } from 'react';
import { gameApi } from '../api/client';
import { readAccount, writeAccount } from './session';

/**
 * Подсказки игрока — field-backlog, пункты 30 и 130.
 *
 * Две настройки, и обе живут в учётной записи, как язык и раскладка клавиш: совет, отклонённый
 * на одной машине, не должен возвращаться на другой.
 *
 * * **Подсказки новичку** (`noviceHints`): ветерану MOO II обучение — шум, и он уходит от игры,
 *   которая объясняет ему очевидное. Пусто — ещё не спрашивали; спрашивает главное меню один
 *   раз (`VeteranQuestion`), а меняется галкой в настройках.
 * * **Отклонённые насовсем** (`dismissedHints`): совет, повторяемый вопреки желанию, становится
 *   давлением. У подсказки есть ключ, и «больше не показывать» кладёт его сюда; вернуть все
 *   можно одной кнопкой в настройках.
 *
 * Правка ложится в браузер сразу, а на сервер уходит следом: подсказка обязана исчезнуть по
 * нажатию, а не по ответу. Не дошло до сервера — останется в браузере до следующего входа.
 */
let version = 0;
const listeners = new Set<() => void>();
const bump = () => {
  version += 1;
  listeners.forEach((listener) => listener());
};

/** Подписка компонента на подсказки: перерисовка при любой их правке. */
export const useHints = (): number =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );

/** Ответ на вопрос ветерану: пусто — ещё не спрашивали. */
export const noviceHintsAnswer = (): boolean | undefined => readAccount()?.account.noviceHints;

/** Нужны ли подсказки новичку: пока не ответили — да. */
export const noviceHintsOn = (): boolean => noviceHintsAnswer() !== false;

const dismissedList = (): string[] => readAccount()?.account.dismissedHints ?? [];

export const isDismissed = (key: string): boolean => dismissedList().includes(key);

export const dismissedCount = (): number => dismissedList().length;

const save = (noviceHints: boolean, dismissedHints: string[]): void => {
  const session = readAccount();
  if (!session) {
    return;
  }
  writeAccount({ ...session, account: { ...session.account, noviceHints, dismissedHints } });
  bump();
  gameApi
    .saveHints(noviceHints, dismissedHints)
    .then((account) => {
      const current = readAccount();
      if (current) {
        writeAccount({ ...current, account });
        bump();
      }
    })
    .catch(() => undefined);
};

export const setNoviceHints = (on: boolean): void => save(on, dismissedList());

export const dismissHint = (key: string): void => {
  if (!isDismissed(key)) {
    save(noviceHintsOn(), [...dismissedList(), key]);
  }
};

/** «Вернуть все подсказки» — отклонённые снова показываются. */
export const restoreHints = (): void => save(noviceHintsOn(), []);

/** Ключ всей карточки обучения: её отклоняют целиком, а не по шагу. */
export const TUTORIAL_HINT = 'tutorial';
