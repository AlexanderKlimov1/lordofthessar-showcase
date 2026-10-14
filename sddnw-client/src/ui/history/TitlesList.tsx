import type { Title } from '../../api/types';
import { t, useT, type Key } from '../../i18n';

/**
 * Звания за трудное — field-backlog, пункт 41: что заработано и сколько осталось до прочих.
 *
 * Звание — сведение о трудном, а не плата за присутствие (пункт 20): здесь нет ни дней входа, ни
 * числа ходов. Заработанное помечено знаком ✦, а не только цветом (правило цвета, пункты 168 и
 * 173); незаработанное показывает ход к себе — «4 из 13», — потому что у дальней цели виден путь.
 */
export function TitlesList({ titles }: { titles: Title[] }) {
  useT();
  if (titles.length === 0) {
    return null;
  }
  return (
    <section>
      <h3 className="text-16 text-ink-bright">{t('titles.heading')}</h3>
      <p className="text-14 text-ink-dim">{t('titles.what')}</p>
      <ul className="mt-2 space-y-1 text-14">
        {titles.map((title) => (
          <li key={title.code} className={title.earned ? 'text-lamp' : 'text-ink-soft'}>
            <span aria-hidden className="mr-2">{title.earned ? '✦' : '◇'}</span>
            {t(`titles.${title.code}` as Key)}
            {!title.earned && title.goal > 1 ? (
              <span className="ml-2 text-ink-dim">{t('titles.progress', { n: title.progress, goal: title.goal })}</span>
            ) : null}
            <span className="block pl-6 text-13 text-ink-dim">{t(`titles.${title.code}.how` as Key)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Название звания на языке читателя — для сцены конца и карточки «поделиться». */
export const titleName = (code: string): string => t(`titles.${code}` as Key);
