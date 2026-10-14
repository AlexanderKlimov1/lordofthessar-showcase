import type { Story } from './story/storyEvents';
import type { Theft } from './espionage/TechStolenScene';

/**
 * Цепочка окон после хода — field-backlog, пункт 14.
 *
 * Насыщенный ход давал пять окон подряд: итоги, совет, кража, сцены событий, разведанная система, —
 * и поток рвался на каждом. Окна делятся на два рода. РЕШЕНИЯ (выбор науки, совет с правом отказа,
 * встреча и бой, высадка, конец партии) открываются по-прежнему: без них ход не сделать. Окна ДЛЯ
 * СВЕДЕНИЯ — сцены событий, кража технологии, разведанная система — после итогов открывается само
 * не больше {@link INFO_WINDOWS_PER_TURN}; остальные ждут полоской «отложено» над картой, откуда
 * их открывают кнопкой, когда удобно. Ничего не пропадает: полоска живёт до следующих итогов.
 *
 * Порядок важности: сцены событий (их немного, и это главное в ходе), потом кража, потом разведанная
 * система (о ней и так строка итогов, а звезду можно открыть щелчком).
 */
export const INFO_WINDOWS_PER_TURN = 1;

/** Окно для сведения, которое ждёт кнопки. */
export type Deferred =
  | { kind: 'story'; story: Story }
  | { kind: 'theft'; theft: Theft }
  | { kind: 'explored'; systemId: string; name: string };

/** Что откроется само сейчас и что встанет в полоску — по порядку важности. */
export function splitInfoWindows(candidates: Deferred[]): { open: Deferred[]; deferred: Deferred[] } {
  const rank = (one: Deferred) => (one.kind === 'story' ? 0 : one.kind === 'theft' ? 1 : 2);
  const ordered = [...candidates].sort((a, b) => rank(a) - rank(b));
  return { open: ordered.slice(0, INFO_WINDOWS_PER_TURN), deferred: ordered.slice(INFO_WINDOWS_PER_TURN) };
}
