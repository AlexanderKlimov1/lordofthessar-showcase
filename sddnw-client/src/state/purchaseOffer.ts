import type { FounderPage } from '../api/types';
import { dismissHint, isDismissed } from './hints';

/**
 * Когда предложить пакет основателя — field-backlog, пункты 25, 175, 48 и правило 201.
 *
 * * **В миг высокой мотивации (пункт 25)**: после успеха — выигранной партии или первого места по
 *   мощи в её конце. Сцена конца лишь ОТМЕЧАЕТ успех (`markSuccess`), а предлагает главное меню:
 * * **не посреди хода (пункт 201)** — только на пороге круга, а главное меню и есть порог;
 * * **не в миг уязвимости (пункт 175)**: после поражения отметки нет, и предложения нет;
 * * **после маленьких шагов (пункт 48)**: только учётной записи (гость сперва сохраняет партию
 *   регистрацией — это его шаг, и цепочка не рвётся на покупке), у которой есть сыгранная партия;
 * * **не чаще раза за сеанс, а отказ помнится**: «не предлагать» — подсказка учётной записи
 *   (`dismissHint`), «не сейчас» — до конца сеанса.
 *
 * Честность (пункт 38): предложение есть, только когда страница опубликована, продажа открыта и
 * места не кончились — ни выдуманной срочности, ни ложной редкости.
 */
const SUCCESS_KEY = 'sddnw.offer.success';
const SHOWN_KEY = 'sddnw.offer.shown';
export const OFFER_HINT = 'offer.founders';

const session = (): Storage | null => {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
};

/** Сцена конца отмечает успех — пункт 25; поражение не отмечается (пункт 175). */
export const markSuccess = (): void => {
  try {
    session()?.setItem(SUCCESS_KEY, '1');
  } catch {
    // Без хранилища предложения просто не будет — это лучше, чем предложение не к месту.
  }
};

/** Пакет действительно можно купить: опубликован, продажа открыта, места есть. */
export const purchasable = (page: FounderPage | undefined): boolean =>
  !!page && page.published && !!page.buyUrl && page.seatsTaken < page.seatsTotal;

/** Пора ли предложить: успех в этом сеансе, предложения ещё не было и от него не отказались. */
export const offerDue = (): boolean => {
  try {
    const store = session();
    return store?.getItem(SUCCESS_KEY) === '1' && store.getItem(SHOWN_KEY) !== '1' && !isDismissed(OFFER_HINT);
  } catch {
    return false;
  }
};

/** «Не сейчас» — до конца сеанса. */
export const offerLater = (): void => {
  try {
    session()?.setItem(SHOWN_KEY, '1');
  } catch {
    // нечего запоминать — нечего и показывать снова
  }
};

/** «Не предлагать» — помнит учётная запись. */
export const offerNever = (): void => {
  offerLater();
  dismissHint(OFFER_HINT);
};
