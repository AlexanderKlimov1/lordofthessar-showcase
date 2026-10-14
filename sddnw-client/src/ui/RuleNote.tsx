import { dismissHint, isDismissed, useHints } from '../state/hints';
import { t } from '../i18n';
/**
 * Строка-пояснение к правилу, которое у нас НЕ как в оригинале, — backlog-promo, пункт 12.
 *
 * Ветеран MOO II сравнивает с оригиналом, и отступление без объяснения читает как ошибку;
 * новичок оригинала не знает, и ему то же самое нужно как простое правило. Поэтому текст
 * пишется правилом этой игры («урон — верхняя граница…»), а оговорка про оригинал стоит в
 * нём хвостом, если она вообще нужна. Ставится там, где игрок с правилом сталкивается, и
 * одна строка: образец — окно продажи постройки, а не абзац справки.
 */
export function RuleNote({ text, className = '', hint }: {
  text: string;
  className?: string;
  /**
   * Ключ подсказки — field-backlog, пункт 130: с ним строку можно отклонить насовсем
   * («больше не показывать»), и учётная запись это помнит. Без ключа строка постоянная.
   */
  hint?: string;
}) {
  useHints();
  if (hint && isDismissed(hint)) {
    return null;
  }
  return (
    <p className={'leading-snug text-ink-faint ' + className}>
      ◇ {text}
      {hint ? (
        <button
          type="button"
          className="link-quiet hit-24 ml-2 text-ink-off"
          title={t('hint.dismiss')}
          aria-label={t('hint.dismiss')}
          onClick={() => dismissHint(hint)}
        >
          ✕
        </button>
      ) : null}
    </p>
  );
}
