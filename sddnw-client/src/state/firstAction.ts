import { setActionListener } from '../api/client';
import { funnelStep } from './funnel';

/**
 * Замер трения — field-backlog, пункт 195: сколько секунд проходит от открытия страницы до
 * первого ДЕЙСТВИЯ в партии. Трение — главный рычаг привычки, и видеть его надо числом: цель
 * для вернувшегося игрока — меньше десяти секунд.
 *
 * Мерил два, и оба один раз на открытие страницы:
 *
 * * `timing.first-action` — первый удавшийся изменяющий запрос внутри партии (поставить
 *   стройку, отправить флот, закончить ход…). Вход в партию, возврат, уход и старт действием
 *   не считаются: это дорога к столу, а не ход за ним;
 * * `timing.first-turn` — первый законченный ход (пункт 125, «как мерить» для гостя: у новичка
 *   первое действие — ещё не игра, а первый ход — уже она).
 *
 * Отсчёт — от начала навигации (`performance.now()` отсчитывается от неё же), то есть
 * включает загрузку страницы, заставку, вход и меню: игрок ждёт всего этого, а не одного
 * экрана. Уходит шагом воронки (пункт 2) с длительностью в подробности (`ms:12345,turn` —
 * число и род действия по адресу запроса), без имён и содержимого. Разбирает
 * `tools/guest_funnel.py`, отдельно у гостей и у записей.
 */
const GAME_ACTION = /^\/api\/games\/([0-9a-f-]{36})\/([a-z-]+)/;

/** Дорога к столу, а не действие за ним. */
const NOT_ACTIONS = new Set(['join', 'rejoin', 'leave', 'start']);

let firstAction = false;
let firstTurn = false;

export const armFirstActionTiming = (): void => {
  setActionListener((_method, path) => {
    if (firstAction && firstTurn) {
      return;
    }
    const match = GAME_ACTION.exec(path);
    if (!match || NOT_ACTIONS.has(match[2])) {
      return;
    }
    const gameId = match[1];
    const ms = Math.round(performance.now());
    if (!firstAction) {
      firstAction = true;
      funnelStep('timing.first-action', { gameId, detail: `ms:${ms},${match[2]}` });
    }
    if (!firstTurn && path.endsWith('/turn/end')) {
      firstTurn = true;
      funnelStep('timing.first-turn', { gameId, detail: `ms:${ms}` });
    }
  });
};
