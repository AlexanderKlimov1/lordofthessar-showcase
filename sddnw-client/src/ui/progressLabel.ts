import type { Key } from '../i18n';

/**
 * Подпись прогресса — field-backlog, пункт 52: смена точки отсчёта (Ку и Фишбах).
 *
 * Меньшее число ощущается большим шагом: пока сделано меньше половины, подпись говорит
 * «сделано N», а с половины — «осталось M». Правило ОДНО для всех полос игры (стройка, наука,
 * обучение…), поэтому живёт здесь, а не по месту на каждом экране, — иначе разойдётся (пункт 32).
 * У каждой подписи два ключа словаря: «сделано» и «осталось»; подстановка `{left}` — остаток.
 */
export const pastHalf = (done: number, total: number): boolean => total > 0 && done * 2 >= total;

/** Какой из двух ключей подписи взять и сколько осталось. */
export function progressKey<K extends Key>(done: number, total: number, keys: { done: K; left: K }):
  { key: K; left: number } {
  return { key: pastHalf(done, total) ? keys.left : keys.done, left: Math.max(0, total - done) };
}
