import { gameApi } from '../api/client';

/**
 * Воронка новичка — field-backlog, пункт 2 (P0): где именно уходят пришедшие.
 *
 * Клиент шлёт серверу ШАГИ пути — экран, ход, шаг обучения, итоги хода, — а последняя
 * строка посетителя в журнале и есть место ухода. Шлётся без ожидания и без отказов:
 * сбой журнала не должен мешать игре ни на миг, поэтому ошибка проглатывается молча.
 *
 * Посетитель — случайный номер браузера, а не учётная запись: до входа записи нет вовсе, а
 * первый вопрос воронки — сколько людей ушли с экрана входа, не нажав ничего. Хранилище
 * бывает закрыто (частное окно) — тогда номер живёт до закрытия вкладки.
 */
const VISITOR_KEY = 'sddnw.visitor';

let visitor: string | null = null;

const newVisitor = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `v-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;

export const visitorId = (): string => {
  if (visitor) {
    return visitor;
  }
  try {
    visitor = window.localStorage.getItem(VISITOR_KEY);
    if (!visitor) {
      visitor = newVisitor();
      window.localStorage.setItem(VISITOR_KEY, visitor);
    }
  } catch {
    visitor = visitor ?? newVisitor();
  }
  return visitor;
};

/** Подробность шага — только то, что пропустит сервер: буквы, цифры и знаки кодов. */
const clean = (detail?: string): string | undefined =>
  detail === undefined ? undefined : detail.replace(/[^A-Za-z0-9._:, -]/g, '').slice(0, 255);

/**
 * Шаги уходят НЕ СРАЗУ, а очередью по одному, через {@link FUNNEL_DELAY_MS} после последнего
 * (12.10.2026, замер конца хода): шаги «ход» и «итоги хода» рождаются ровно в миг, когда клиент
 * перечитывает партию, и занимали два из шести соединений браузера перед картой и отчётом.
 * Журналу воронки важен порядок шагов, а не секунда, — порядок очередь сохраняет.
 */
const FUNNEL_DELAY_MS = 2000;
const pending: Parameters<typeof gameApi.funnelStep>[0][] = [];
let flushTimer: ReturnType<typeof setTimeout> | null = null;

const flush = async (): Promise<void> => {
  flushTimer = null;
  while (pending.length > 0) {
    const body = pending.shift()!;
    await gameApi.funnelStep(body).catch(() => undefined);
  }
};

// Уходящий со страницы уносит последний шаг с собой: он и есть место ухода, ждать его нельзя.
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && pending.length > 0) {
      if (flushTimer !== null) {
        clearTimeout(flushTimer);
      }
      void flush();
    }
  });
}

export const funnelStep = (
  step: string,
  where: { gameId?: string | null; turn?: number | null; detail?: string } = {},
): void => {
  pending.push({
    visitor: visitorId(),
    step,
    gameId: where.gameId ?? undefined,
    turn: where.turn ?? undefined,
    detail: clean(where.detail),
  });
  if (flushTimer !== null) {
    clearTimeout(flushTimer);
  }
  flushTimer = setTimeout(() => void flush(), FUNNEL_DELAY_MS);
};
