/**
 * Набор справочников основателя, выбранный для ближайшей партии, — field-backlog, пункт 42.
 *
 * Тот же приём, что у своей расы из кабинета (`chosenRaceTemplate.ts`): выбор делает окно новой
 * игры, а заказ партии уходит из машины экранов, и протаскивать номер через все её события ради
 * одного поля незачем. Выбор живёт здесь до ближайшего заказа и забирается им.
 */
let chosen: string | null = null;

export const chooseMod = (modId: string | null | undefined): void => {
  chosen = modId ?? null;
};

/** Что выбрано сейчас — окно новой игры показывает это на переключателе. */
export const chosenMod = (): string | null => chosen;

/** Забрать выбор: он годится для одного заказа партии. */
export const takeChosenMod = (): string | undefined => {
  const value = chosen ?? undefined;
  chosen = null;
  return value;
};
