import { t, useT } from '../../i18n';
import { useGlyphs } from '../glyphs';
import { rememberGlyphs } from '../../state/session';
import { useGameStore } from '../../state/gameStore';
import { runningInstalled } from '../install';

/**
 * Раздел «Особые возможности» настроек — field-backlog, пункт 202: то, что нужно немногим и
 * мешало бы остальным, если бы стояло всегда. Первая возможность — знаки знамён у хозяев на
 * карте, для различения без цвета. Включение перерисовывает карту и флоты сразу.
 */
export function SpecialSettings() {
  useT();
  const on = useGlyphs();
  const toggle = (value: boolean) => {
    rememberGlyphs(value);
    // Карта Phaser рисуется из хранилища: новый объект карты и флота — и сцена перерисует подписи.
    const store = useGameStore.getState();
    if (store.map) {
      store.setMap({ ...store.map });
    }
    if (store.fleet) {
      store.setEmpire(store.espionage, { ...store.fleet });
    }
  };
  return (
    <div className="space-y-3">
      <p className="text-14 text-ink-dim">{t('preferences.special.what')}</p>
      <label className="flex cursor-pointer items-start gap-3 text-16 text-ink">
        <input type="checkbox" className="mt-1" checked={on} onChange={(event) => toggle(event.target.checked)} />
        <span>
          {t('preferences.special.glyphs')}
          <span className="block text-14 text-ink-faint">{t('preferences.special.glyphs.hint')}</span>
        </span>
      </label>

      {/*
        Установка — пункт 203: где браузер даёт своё событие, в меню есть кнопка; здесь — как
        поставить вручную там, где его нет, и что игра уже открыта как приложение.
      */}
      <p className="text-14 text-ink-soft">
        {runningInstalled() ? t('install.already') : t('install.manual')}
      </p>
    </div>
  );
}
