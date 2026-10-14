import type { TurnReport } from '../api/types';

/**
 * Что было за сеанс — field-backlog, пункт 35.
 *
 * Сеанс начинается с входа в партию и живёт в памяти страницы: это не история партии (её
 * хранят летопись и «Инфо»), а «что вы сделали сегодня» к сводке при выходе. Отчёты ходов
 * сервер присылает и так; здесь они только копятся — по одному на ход, — а вместе с первым
 * запоминается, сколько у игрока было колоний.
 */
interface Session {
  gameId: string;
  reports: TurnReport[];
  startColonies: number;
}

let session: Session | null = null;

/** Записать отчёт хода; новая партия начинает новый сеанс. */
export function recordReport(gameId: string, report: TurnReport, colonies: number): void {
  if (!session || session.gameId !== gameId) {
    session = { gameId, reports: [], startColonies: colonies };
  }
  if (!session.reports.some((one) => one.turn === report.turn)) {
    session.reports.push(report);
  }
}

/** Сеанс этой партии; пусто — за сеанс не прошло ни одного хода. */
export function sessionOf(gameId: string | undefined): Session | null {
  return session && session.gameId === gameId && session.reports.length > 0 ? session : null;
}

/** Сеанс закончен: следующий вход начнёт его заново. */
export function endSession(): void {
  session = null;
}
