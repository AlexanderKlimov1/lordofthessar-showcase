import { t, useT } from '../../i18n';
import { MUSIC_AVAILABLE } from '../../audio/soundSettings';
import { SoundSwitch } from '../SoundSwitch';

/**
 * Раздел «Звук» настроек — 07.10.2026, решение хозяина проекта: музыка и звуковые эффекты
 * включаются РАЗДЕЛЬНО. Значок звука на сценах гасит и включает эффекты одним нажатием, а
 * здесь каждая громкость своими ступенями; выбор живёт в учётной записи (`rememberSound`).
 *
 * Музыки в игре пока нет (`MUSIC_AVAILABLE`): её переключатель виден, но погашен и подписан —
 * пропавшая настройка читалась бы поломкой, а погашенная с причиной говорит правду.
 */
export function SoundSettings() {
  useT();
  return (
    <div className="space-y-4">
      <p className="text-14 text-ink-dim">{t('preferences.sound.what')}</p>
      <div className="space-y-1">
        <SoundSwitch channel="sfx" />
        <p className="text-14 text-ink-soft">{t('preferences.sound.sfx')}</p>
      </div>
      <div className="space-y-1">
        <SoundSwitch channel="music" disabled={!MUSIC_AVAILABLE} />
        <p className="text-14 text-ink-soft">
          {t(MUSIC_AVAILABLE ? 'preferences.sound.music' : 'preferences.sound.musicAway')}
        </p>
      </div>
    </div>
  );
}
