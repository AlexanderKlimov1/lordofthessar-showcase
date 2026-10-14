import { t, useT } from '../../i18n';
import { offerLater, offerNever } from '../../state/purchaseOffer';

/**
 * Предложение пакета основателя в главном меню — field-backlog, пункты 25 и 175: после успеха, на
 * пороге круга, раз за сеанс. Подача — «вы основатель», а не «оплатите доступ» (пункт 38); оба
 * отказа видны и равны кнопке согласия.
 */
export function FounderOffer({ onOpen, onDone }: { onOpen: () => void; onDone: () => void }) {
  useT();
  return (
    <section className="panel w-full max-w-xl text-14">
      <p className="text-ink">{t('offer.text')}</p>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        <button type="button" className="link text-lamp" onClick={() => { offerLater(); onOpen(); }}>
          {t('offer.open')}
        </button>
        <button type="button" className="link" onClick={() => { offerLater(); onDone(); }}>
          {t('offer.later')}
        </button>
        <button type="button" className="link" onClick={() => { offerNever(); onDone(); }}>
          {t('offer.never')}
        </button>
      </div>
    </section>
  );
}
