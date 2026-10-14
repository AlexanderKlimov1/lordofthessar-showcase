import { useEffect, useState } from 'react';
import { useT } from '../i18n';
import { useHudShown, useQuietCorner } from '../state/hudPresence';
import { useGameStore } from '../state/gameStore';
import { rememberSound } from '../state/session';
import { playLeitmotifMoment } from '../audio/music';
import {
  MUSIC_AVAILABLE,
  SOUND_DEFAULT,
  chooseSoundThisVisit,
  onSoundChange,
  setSoundLevel,
  soundLevel,
  toSoundLevel,
  type SoundLevel,
} from '../audio/soundSettings';

/**
 * Значок звука — на КАЖДОЙ сцене, в левом верхнем углу (04.10.2026, решение хозяина проекта).
 *
 * Звук выбирают в заставке вечера, а ступени громкости живут в главном меню и в меню «Игра».
 * Этого мало: игрок, начавший вечер с музыкой, сидит в бою, на экране колонии или в окне
 * исследований — и выключить звук оттуда ему было нечем. Звук, который нельзя остановить там,
 * где он мешает, ломает «спокойный вечер» сильнее, чем его отсутствие.
 *
 * Значок говорит состояние видом: динамик с волнами — звук есть, перечёркнутый — тишина.
 * Нажатие гасит ОБА канала (музыку и эффекты) и помнит, какими они были; второе нажатие
 * возвращает прежние ступени. Тонкая настройка остаётся ступенями в меню — значок не
 * повторяет её, а лишь выключает и включает.
 *
 * Рисуется поверх всего (`main.tsx`, рядом с `App`), а не внутри экранов: экранов десятки, и
 * значок, вписанный в каждый, однажды забыли бы в новом.
 */

const BEFORE_KEY = 'sddnw.settings.soundBefore';
/** Что включить, если прежних ступеней нет (звук ни разу не включали): средняя громкость. */
const DEFAULT_LEVEL: SoundLevel = SOUND_DEFAULT;

/*
 * Пока музыки в игре нет (`MUSIC_AVAILABLE`), значок ведает одними звуковыми эффектами
 * (07.10.2026, решение хозяина проекта): громкость музыки он не трогает вовсе, и вернувшаяся
 * музыка найдёт её такой, какой игрок её оставил.
 */
const audible = () => (MUSIC_AVAILABLE && soundLevel('music') > 0) || soundLevel('sfx') > 0;

function readBefore(): { music: SoundLevel; sfx: SoundLevel } {
  try {
    const saved = JSON.parse(window.localStorage.getItem(BEFORE_KEY) ?? 'null') as { music?: unknown; sfx?: unknown } | null;
    const music = toSoundLevel(saved?.music);
    const sfx = toSoundLevel(saved?.sfx);
    if (music > 0 || sfx > 0) {
      return { music, sfx };
    }
  } catch {
    // Испорченная запись — включаем по умолчанию.
  }
  return { music: DEFAULT_LEVEL, sfx: DEFAULT_LEVEL };
}

export function SoundToggle({ inline = false }: {
  /**
   * В нижней полосе карты, под «Игрой» (07.10.2026): маленький и без своего места на экране.
   * Без признака — в левом верхнем углу, на сценах, где полосы не видно.
   */
  inline?: boolean;
} = {}) {
  const { t } = useT();
  const hud = useHudShown();
  const quiet = useQuietCorner();
  // Идёт партия — угловой значок не нужен ни на одной сцене (09.10.2026, решение хозяина проекта):
  // на карте звук правят под «Игрой», а колонию, бой и прочие сцены поверх карты значок лишь
  // загромождал. Остаётся он только на экранах ДО галактики — вход, лобби, выбор расы.
  const playing = useGameStore((state) => state.game !== null && state.game.status !== 'LOBBY');
  const [on, setOn] = useState(audible);
  // Ступени меняют и меню, и запись при входе — значок обязан это видеть.
  useEffect(() => onSoundChange(() => setOn(audible())), []);

  const toggle = () => {
    if (audible()) {
      try {
        window.localStorage.setItem(BEFORE_KEY, JSON.stringify({ music: soundLevel('music'), sfx: soundLevel('sfx') }));
      } catch {
        // Хранилище закрыто — включим потом по умолчанию.
      }
      if (MUSIC_AVAILABLE) {
        setSoundLevel('music', 0);
      }
      setSoundLevel('sfx', 0);
    } else {
      const before = readBefore();
      if (MUSIC_AVAILABLE) {
        setSoundLevel('music', before.music);
      }
      setSoundLevel('sfx', before.sfx || DEFAULT_LEVEL);
      chooseSoundThisVisit();
      // Включённый на входе звук встречает темой игры (пункт 180): первое, что слышит игрок.
      playLeitmotifMoment();
    }
    rememberSound();
  };

  const label = on ? t('sound.toggle.off') : t('sound.toggle.on');
  // Угловой значок уступает место значку полосы, пока полоса видна и поверх неё ничего нет.
  if (!inline && (quiet || hud || playing)) {
    return null;
  }
  return (
    <button
      type="button"
      onClick={toggle}
      title={label}
      aria-label={label}
      aria-pressed={on}
      className={`${inline ? 'hit-24 h-5 w-5' : 'fixed left-1.5 top-1.5 z-[80] h-7 w-7 rounded border border-space-600/70 bg-space-950/70'} flex items-center justify-center hover:border-accent hover:text-accent ${on ? 'text-accent' : 'text-ink-dim'}`}
    >
      <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor" fillOpacity="0.25" />
        <path d="M15.5 9a4 4 0 0 1 0 6" />
        <path d="M18 6.5a7.5 7.5 0 0 1 0 11" />
        {/* Тишина — тот же динамик, перечёркнутый наискось: значок узнаётся, а состояние видно. */}
        {on ? null : <path d="M3.5 3.5l17 17" strokeWidth="2.2" />}
      </svg>
    </button>
  );
}
