import { useEffect, useState } from 'react';
import { t, useT } from '../i18n';
import { rememberSound } from '../state/session';
import {
  MUSIC_AVAILABLE,
  SOUND_MAX,
  onSoundChange,
  setSoundLevel,
  soundLevel,
  type SoundChannel,
} from '../audio/soundSettings';

/**
 * Громкость музыки и звуковых эффектов — ползунком 0…100 % (09.10.2026, решение хозяина
 * проекта; прежде три ступени). Ноль — «выкл», словом.
 *
 * Выбранное уходит в учётную запись (`rememberSound`): выключенный однажды звук не должен
 * заиграть снова на другой машине.
 */
export function SoundSwitches() {
  // Пока музыки в игре нет (`MUSIC_AVAILABLE`), её ступеней в меню нет тоже: переключатель,
  // который ничего не включает, читался бы поломкой. Объяснение — в настройках, раздел «Звук».
  return (
    <>
      {MUSIC_AVAILABLE ? <SoundSwitch channel="music" /> : null}
      <SoundSwitch channel="sfx" />
    </>
  );
}

export function SoundSwitch({ channel, disabled = false }: { channel: SoundChannel; disabled?: boolean }) {
  useT();
  const [current, setCurrent] = useState(soundLevel(channel));
  // Запись при входе меняет громкость мимо этого переключателя — он должен это увидеть.
  useEffect(() => onSoundChange(() => setCurrent(soundLevel(channel))), [channel]);
  return (
    <div className="flex items-center gap-2 text-11 uppercase tracking-widest">
      <span className="text-ink-faint">{t(channel === 'music' ? 'sound.music' : 'sound.sfx')}</span>
      <input
        type="range"
        min={0}
        max={SOUND_MAX}
        step={1}
        value={current}
        disabled={disabled}
        className="sddnw-range w-40"
        title={t(channel === 'music' ? 'sound.music.hint' : 'sound.sfx.hint')}
        onChange={(event) => setSoundLevel(channel, Number(event.target.value))}
        // В запись — по отпусканию, а не на каждом шаге ползунка: иначе сотня запросов за движение.
        onPointerUp={() => rememberSound()}
        onKeyUp={() => rememberSound()}
      />
      <span className="w-12 text-right normal-case tabular-nums text-ink-soft">
        {current === 0 ? t('sound.off') : `${current}%`}
      </span>
    </div>
  );
}
