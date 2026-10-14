import { useEffect, useState, useSyncExternalStore } from 'react';
import type { Advice } from '../api/types';
import { gameApi } from '../api/client';
import { readAccount, writeAccount } from './session';
import { useGameStore } from './gameStore';

/**
 * Советник — field-backlog, пункт 23: что выбрал бы ИИ на месте игрока и почему, одной фразой.
 *
 * **Выключен по умолчанию, а в гостевой партии включён** (решение хозяина проекта, 07.10.2026):
 * гость и есть новичок, которому умственное усилие первых ходов мешает остаться, а ветерану
 * подсказка без спроса — шум. Явный выбор игрока в настройках сильнее умолчания и живёт в учётной
 * записи, как подсказки и окна (`state/hints.ts`, `state/windows.ts`).
 *
 * Сам совет считает сервер (`AdvisorService`) тем же правилом, что ИИ: второго свода здесь нет.
 */
let version = 0;
const listeners = new Set<() => void>();
const bump = () => {
  version += 1;
  listeners.forEach((listener) => listener());
};

/** Подписка компонента на настройку советника. */
export const useAdvisorSetting = (): number =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );

/** Явный выбор игрока: пусто — не выбирал. */
export const advisorChoice = (): boolean | undefined => readAccount()?.account.advisor;

/** Включён ли советник в этой партии: выбор игрока, а без него — только в гостевой. */
export const advisorOn = (guestGame: boolean): boolean => advisorChoice() ?? guestGame;

/** Запомнить выбор; `null` — вернуть умолчание. */
export const setAdvisor = (advisor: boolean | null): void => {
  const session = readAccount();
  if (!session) {
    return;
  }
  writeAccount({ ...session, account: { ...session.account, advisor: advisor ?? undefined } });
  bump();
  gameApi
    .saveAdvisor(advisor)
    .then((account) => {
      const current = readAccount();
      if (current) {
        writeAccount({ ...current, account });
        bump();
      }
    })
    .catch(() => undefined);
};

/**
 * Совет на текущий ход — или `null`, пока советник выключен или ответа нет. Перечитывается на
 * каждый ход, при смене цели науки и стройки: совет — про «сейчас», и вчерашний вводил бы в заблуждение.
 */
export const useAdvice = (): Advice | null => {
  useAdvisorSetting();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  // Карта меняется после каждого действия над колониями: стройка сменилась — совет тоже.
  const map = useGameStore((state) => state.map);
  const target = useGameStore((state) => state.research?.optionCode);
  const on = game ? advisorOn(Boolean(game.guestRules)) : false;
  const [advice, setAdvice] = useState<Advice | null>(null);
  useEffect(() => {
    if (!on || !game || !credentials || game.status !== 'IN_PROGRESS') {
      setAdvice(null);
      return;
    }
    let alive = true;
    gameApi.advice(game.id, credentials.accessToken)
      .then((answer) => {
        if (alive) {
          setAdvice(answer);
        }
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [on, game?.id, game?.status, credentials?.accessToken, game?.turn, map, target]);
  return advice;
};
