import { useState } from 'react';
import { LOCALES, t, useT } from '../i18n';
import { rememberLocale, rememberTextScale } from '../state/session';
import { FullscreenSwitch } from './FullscreenSwitch';
import { TEXT_SCALES, applyTextScale, textScale, type TextScale } from './textScale';

/**
 * Язык и размер текста — значками в углу (редизайн экрана входа, 07.10.2026, решение хозяина
 * проекта), а не строками рядом с формой. Каждый значок — кнопка по кругу: язык EN ↔ RU, размер
 * 100 → 115 → 130 → 150 %. Запоминается то же и там же, что у полных переключателей
 * (`LocaleSwitch`, `TextScaleSwitch`): запись, а до входа — браузер.
 */
export function CornerSwitches() {
  const { locale, setLocale } = useT();
  const [scale, setScale] = useState<TextScale>(textScale());
  const nextLocale = LOCALES[(LOCALES.indexOf(locale) + 1) % LOCALES.length];
  const nextScale = TEXT_SCALES[(TEXT_SCALES.indexOf(scale) + 1) % TEXT_SCALES.length];
  const button = 'hit-24 flex h-8 items-center gap-1 rounded border border-space-600 bg-space-900/80 px-2 text-13 text-ink-soft hover:border-accent hover:text-accent';
  return (
    <div className="flex items-center gap-2">
      {/* Полный экран — слева от языка (09.10.2026): подсказка называет клавишу. */}
      <FullscreenSwitch className={button} />
      <button type="button" className={button} title={t('corner.locale', { next: t(`locale.${nextLocale}`) })}
              onClick={() => {
                setLocale(nextLocale);
                rememberLocale(nextLocale);
              }}>
        <svg viewBox="0 0 24 24" aria-hidden className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={1.6}>
          <circle cx="12" cy="12" r="8.5" />
          <path d="M3.5 12h17M12 3.5c2.4 2.3 3.6 5.1 3.6 8.5s-1.2 6.2-3.6 8.5c-2.4-2.3-3.6-5.1-3.6-8.5S9.6 5.8 12 3.5Z" />
        </svg>
        <span className="uppercase">{locale}</span>
      </button>
      <button type="button" className={button} title={t('corner.textScale', { next: nextScale })}
              onClick={() => {
                applyTextScale(nextScale);
                setScale(nextScale);
                rememberTextScale(nextScale);
              }}>
        <span aria-hidden className="font-bold">Aa</span>
        <span>{scale}%</span>
      </button>
    </div>
  );
}
