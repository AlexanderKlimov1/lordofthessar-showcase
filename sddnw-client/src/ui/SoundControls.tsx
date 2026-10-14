import { useEffect, useRef, useState } from 'react';
import { t, useT } from '../i18n';
import { rememberSound } from '../state/session';
import {
  SOUND_DEFAULT,
  SOUND_MAX,
  chooseSoundThisVisit,
  onSoundChange,
  setSoundLevel,
  soundLevel,
  type SoundChannel,
} from '../audio/soundSettings';
import { hoverOnly } from './touch';

/** Громкость, к которой вернуться после выключения, — в памяти сеанса. */
const before: Record<SoundChannel, number> = { music: SOUND_DEFAULT, sfx: SOUND_DEFAULT };

/**
 * Музыка и звуковые эффекты — двумя значками под меню «Игра» (09.10.2026, решение хозяина
 * проекта): скрипичный ключ ведает музыкой, динамик — эффектами. Щелчок включает и выключает
 * свой канал; включённый показывает над значком ползунок 0…100 % — он же открывается наведением,
 * пока канал звучит. Выключенный канал помнит прежнюю громкость и возвращается к ней.
 * Громкость уходит в учётную запись (`rememberSound`) по отпусканию ползунка, а не на каждом шаге.
 */
export function SoundControls() {
  return (
    <>
      <ChannelControl channel="music" />
      <ChannelControl channel="sfx" />
    </>
  );
}

function ChannelControl({ channel }: { channel: SoundChannel }) {
  useT();
  const [level, setLevel] = useState(soundLevel(channel));
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const closing = useRef<number | undefined>(undefined);
  // Громкость меняют и запись при входе, и угловой значок — ползунок обязан это видеть.
  useEffect(() => onSoundChange(() => setLevel(soundLevel(channel))), [channel]);
  // Щелчок мимо закрывает ползунок.
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const away = (event: PointerEvent) => {
      if (box.current && !box.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener('pointerdown', away);
    return () => window.removeEventListener('pointerdown', away);
  }, [open]);

  const on = level > 0;
  const name = t(channel === 'music' ? 'sound.music' : 'sound.sfx');
  const toggle = () => {
    if (on) {
      before[channel] = level;
      setSoundLevel(channel, 0);
      setOpen(false);
    } else {
      setSoundLevel(channel, before[channel] || SOUND_DEFAULT);
      chooseSoundThisVisit();
      setOpen(true);
    }
    rememberSound();
  };
  const show = () => {
    window.clearTimeout(closing.current);
    if (on) {
      setOpen(true);
    }
  };
  const hide = () => {
    window.clearTimeout(closing.current);
    closing.current = window.setTimeout(() => setOpen(false), 400);
  };

  return (
    <div ref={box} className="relative flex items-center" onPointerEnter={hoverOnly(show)} onPointerLeave={hoverOnly(hide)}>
      <button
        type="button"
        onClick={toggle}
        title={t(on ? 'sound.channel.off' : 'sound.channel.on', { name })}
        aria-label={t(on ? 'sound.channel.off' : 'sound.channel.on', { name })}
        aria-pressed={on}
        className={`hit-24 flex h-5 w-5 items-center justify-center hover:text-accent ${on ? 'text-accent' : 'text-ink-dim'}`}
      >
        {channel === 'music' ? <ClefIcon muted={!on} /> : <SpeakerIcon muted={!on} />}
      </button>
      {open && on ? (
        // Раскрывается ВВЕРХ: значки стоят в нижней полосе, у края экрана.
        <div className="absolute bottom-full left-1/2 z-40 mb-2 flex -translate-x-1/2 items-center gap-2 rounded border border-space-600 bg-space-900 px-3 py-2 shadow-lg shadow-black/60">
          <span className="text-11 uppercase tracking-widest text-ink-faint">{name}</span>
          <input
            type="range"
            min={0}
            max={SOUND_MAX}
            step={1}
            value={level}
            aria-label={name}
            className="sddnw-range w-32"
            onChange={(event) => setSoundLevel(channel, Number(event.target.value))}
            onPointerUp={() => rememberSound()}
            onKeyUp={() => rememberSound()}
          />
          <span className="w-10 text-right text-12 tabular-nums text-ink-soft">{level}%</span>
        </div>
      ) : null}
    </div>
  );
}

const ICON = { viewBox: '0 0 24 24', className: 'h-4 w-4', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8,
  strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true };

/** Скрипичный ключ — музыка; выключенная перечёркнута, как динамик. */
function ClefIcon({ muted }: { muted: boolean }) {
  return (
    <svg {...ICON}>
      <path d="M12.5 21c-1.6 0-2.4-1-2.2-2.1.2-1 1.4-1.4 2.2-.8.9.7.5 2.3-.4 2.6M12.5 21c1.4-.4 1.6-2.4 1.1-5.5L12 4.5c-.2-1.6.6-2.3 1.4-1.8 1 .7.9 3-1 5.4L9.4 11.5c-1.9 2.5-1.1 5.6 1.8 5.8 2.6.2 4.1-1.6 3.7-3.5-.4-1.8-2.3-2.6-4.1-1.7" />
      {muted ? <path d="M3.5 3.5l17 17" strokeWidth="2.2" /> : null}
    </svg>
  );
}

/** Динамик — звуковые эффекты. */
function SpeakerIcon({ muted }: { muted: boolean }) {
  return (
    <svg {...ICON}>
      <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor" fillOpacity="0.25" />
      {muted ? <path d="M3.5 3.5l17 17" strokeWidth="2.2" /> : (
        <>
          <path d="M15.5 9a4 4 0 0 1 0 6" />
          <path d="M18 6.5a7.5 7.5 0 0 1 0 11" />
        </>
      )}
    </svg>
  );
}
