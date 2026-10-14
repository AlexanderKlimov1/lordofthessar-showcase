/**
 * Своя раса из личного кабинета, выбранная для ближайшей партии, — field-backlog, пункт 99.
 *
 * Сервер засчитывает партию автору расы, взятой по ссылке, а для этого ему нужно знать, КАКОЙ
 * расой из кабинета начата партия. Выбор делает экран расы, а заказ партии уходит из машины
 * экранов — протаскивать номер через все её события ради одного поля незачем: выбор живёт здесь
 * до ближайшего заказа и забирается им (`takeChosenRaceTemplate`).
 */
let chosen: string | null = null;

export const chooseRaceTemplate = (templateId: string | null | undefined): void => {
  chosen = templateId ?? null;
};

/** Забрать выбор: он годится для одного заказа партии. */
export const takeChosenRaceTemplate = (): string | undefined => {
  const value = chosen ?? undefined;
  chosen = null;
  return value;
};
