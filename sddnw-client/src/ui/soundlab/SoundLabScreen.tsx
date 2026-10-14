import { useEffect, useRef, useState } from 'react';
import { t, useT, type Key } from '../../i18n';
import { useModalEscape } from '../useModalEscape';
import { AmbientMusic } from '../../audio/AmbientMusic';
import { audioOutput } from '../../audio/audioOutput';
import {
  MOODS,
  MOODS_ORIGINAL,
  SCALES,
  moodVariant,
  scaleName,
  type MoodSpec,
  type MusicMood,
} from '../../audio/musicRules';
import {
  SFX,
  SFX_NAMES,
  SFX_ORIGINAL,
  sfxVariant,
  type SfxLayer,
  type SfxName,
} from '../../audio/sfxRecipes';
import { explosionOptions, playPops, playRecipe, type PlayOptions } from '../../audio/battleSfx';
import {
  EPIC_PROGRESSIONS,
  LEITMOTIF,
  LEITMOTIF_SETTINGS,
  MOTIF_MODES,
  chordNumeral,
  setLeitmotif,
  type LeitmotifSettings,
  type MotifMode,
} from '../../audio/leitmotif';

/**
 * Звуковая мастерская — подбор музыки сцен и звуков боя на слух (только администратору).
 *
 * Музыка и эффекты игры СОЧИНЯЮТСЯ кодом из таблиц (`MOODS`, `SFX`), и подбирать их числами
 * в файле вслепую — плохой обмен: каждое «а если темп чуть быстрее» стоило бы пересборки.
 * Здесь таблица правится на лету и тут же звучит, а понравившееся уходит обратно в файл
 * одной вставкой — кнопка «копировать» отдаёт ровно ту запись, что лежит в таблице.
 *
 * **Вариант — это номер.** «Следующий вариант» — не случайный щелчок, а зерно жребия:
 * седьмой вариант звучит одинаково сегодня и через месяц, и о нём можно сказать словами.
 * Нулевой — исходный, как в файле.
 *
 * Мастерская звучит МИМО громкости игрока: своя шина прямо на выход, иначе при выключенном в
 * игре звуке (а он выключен по умолчанию) подбирать было бы нечего. Правки при этом живут в
 * той же таблице, что и игра, — в этой вкладке их услышит и сама партия, до перезагрузки.
 */

type Tab = 'music' | 'motif' | 'sfx';

const MOOD_NAMES: MusicMood[] = ['menu', 'galaxy', 'battle', 'council'];
const MOOD_LABELS: Record<MusicMood, Key> = {
  menu: 'soundlab.mood.menu',
  galaxy: 'soundlab.mood.galaxy',
  battle: 'soundlab.mood.battle',
  council: 'soundlab.mood.council',
};
const SFX_LABELS: Record<SfxName, Key> = {
  beam: 'soundlab.sfx.beam',
  cannon: 'soundlab.sfx.cannon',
  launch: 'soundlab.sfx.launch',
  hit: 'soundlab.sfx.hit',
  hitHeavy: 'soundlab.sfx.hitHeavy',
  pointDefence: 'soundlab.sfx.pointDefence',
  pop: 'soundlab.sfx.pop',
  explosion: 'soundlab.sfx.explosion',
  click: 'soundlab.sfx.click',
  turn: 'soundlab.sfx.turn',
};

/** Числовые поля настроения: имя поля, шаг и пределы ползунка. */
const MOOD_FIELDS: { field: keyof MoodSpec; min: number; max: number; step: number }[] = [
  { field: 'root', min: 36, max: 64, step: 1 },
  { field: 'bpm', min: 30, max: 160, step: 1 },
  { field: 'barsPerChord', min: 1, max: 4, step: 1 },
  { field: 'pad', min: 0, max: 1, step: 0.05 },
  { field: 'bass', min: 0, max: 1, step: 0.05 },
  { field: 'arp', min: 0, max: 1, step: 0.05 },
  { field: 'arpDensity', min: 0, max: 1, step: 0.05 },
  { field: 'bellDensity', min: 0, max: 0.3, step: 0.01 },
  { field: 'cutoff', min: 300, max: 6000, step: 50 },
  { field: 'pulse', min: 0, max: 1, step: 0.05 },
  { field: 'echoFeedback', min: 0, max: 0.8, step: 0.05 },
  { field: 'bassPulse', min: 0, max: 1, step: 0.05 },
  { field: 'lead', min: 0, max: 1, step: 0.05 },
  { field: 'snare', min: 0, max: 1, step: 0.05 },
  { field: 'buildBars', min: 0, max: 8, step: 1 },
  { field: 'motif', min: 0, max: 1, step: 0.05 },
  { field: 'motifEvery', min: 0, max: 16, step: 1 },
  { field: 'bed', min: 0, max: 1, step: 0.05 },
  { field: 'guitar', min: 0, max: 1, step: 0.05 },
  { field: 'guitarDrive', min: 0, max: 1, step: 0.05 },
  { field: 'bridge', min: 0, max: 1, step: 0.05 },
  { field: 'bridgeEvery', min: 0, max: 32, step: 1 },
];

const GUITAR_PATTERNS: NonNullable<MoodSpec['guitarPattern']>[] = ['chug', 'sustain'];

const VOICINGS: NonNullable<MoodSpec['voicing']>[] = ['seventh', 'sus2', 'add9'];
const ARP_TONES: NonNullable<MoodSpec['arpTone']>[] = ['soft', 'guitar'];

/** Числовые поля слоя звука. */
const LAYER_FIELDS: { field: keyof SfxLayer; min: number; max: number; step: number }[] = [
  { field: 'from', min: 20, max: 12000, step: 10 },
  { field: 'to', min: 20, max: 12000, step: 10 },
  { field: 'peak', min: 0, max: 1.5, step: 0.01 },
  { field: 'attack', min: 0.001, max: 0.5, step: 0.001 },
  { field: 'decay', min: 0.01, max: 3, step: 0.01 },
  { field: 'q', min: 0.1, max: 20, step: 0.1 },
];

const WAVES: OscillatorType[] = ['sine', 'triangle', 'sawtooth', 'square'];
const FILTERS: BiquadFilterType[] = ['lowpass', 'highpass', 'bandpass'];

/** Своя шина мастерской: прямо на выход, мимо громкости игрока. */
function useLabBus(volume: number) {
  const bus = useRef<GainNode | null>(null);
  const get = (): GainNode | null => {
    if (!bus.current) {
      const out = audioOutput();
      if (!out) {
        return null;
      }
      bus.current = out.ctx.createGain();
      bus.current.connect(out.ctx.destination);
    }
    bus.current.gain.value = volume * volume;
    return bus.current;
  };
  useEffect(() => {
    if (bus.current) {
      bus.current.gain.value = volume * volume;
    }
  }, [volume]);
  useEffect(() => () => bus.current?.disconnect(), []);
  return get;
}

export function SoundLabScreen({ onClose }: { onClose: () => void }) {
  useT();
  useModalEscape(true, onClose);
  const [tab, setTab] = useState<Tab>('music');
  const [volume, setVolume] = useState(0.7);
  const bus = useLabBus(volume);

  return (
    <div className="fixed inset-0 z-40 flex justify-center overflow-y-auto bg-space-950/95">
      <div className="my-auto w-full max-w-5xl p-6">
        <div className="panel">
          <div className="flex items-baseline justify-between">
            <div className="panel-title text-16">{t('soundlab.title')}</div>
            <button type="button" className="link text-13" onClick={onClose}>{t('common.close')}</button>
          </div>
          <p className="mt-2 text-13 text-ink-soft">{t('soundlab.what')}</p>

          <div className="mt-4 flex flex-wrap items-center gap-4 text-13">
            {(['music', 'motif', 'sfx'] as Tab[]).map((value) => (
              <button
                key={value}
                type="button"
                className={`link uppercase tracking-widest ${tab === value ? 'link-active text-accent' : 'text-ink-dim'}`}
                onClick={() => setTab(value)}
              >
                {t(value === 'music' ? 'soundlab.tab.music' : value === 'motif' ? 'soundlab.tab.motif' : 'soundlab.tab.sfx')}
              </button>
            ))}
            <label className="ml-auto flex items-center gap-2 text-ink-dim">
              {t('soundlab.volume')}
              <input type="range" min={0} max={1} step={0.05} value={volume}
                     onChange={(event) => setVolume(Number(event.target.value))} />
            </label>
          </div>

          {tab === 'music' ? <MusicLab bus={bus} /> : tab === 'motif' ? <MotifLab bus={bus} /> : <SfxLab bus={bus} />}
        </div>
      </div>
    </div>
  );
}

function MusicLab({ bus }: { bus: () => GainNode | null }) {
  const [mood, setMood] = useState<MusicMood>('galaxy');
  const [variant, setVariant] = useState(0);
  const [playing, setPlaying] = useState(false);
  // Таблица правится на месте, и React об этом не знает: счётчик правок перерисовывает экран.
  const [, setEdits] = useState(0);
  const engine = useRef<AmbientMusic | null>(null);
  const spec = MOODS[mood];

  const stop = () => {
    engine.current?.dispose();
    engine.current = null;
    setPlaying(false);
  };
  const start = (which: MusicMood) => {
    engine.current?.dispose();
    const out = audioOutput();
    const node = bus();
    if (!out || !node) {
      return;
    }
    engine.current = new AmbientMusic(out.ctx, node, which, 1996);
    engine.current.start();
    setPlaying(true);
  };
  useEffect(() => () => engine.current?.dispose(), []);

  /** Вариант по номеру; ноль — исходное. Играющий движок заводится заново — с начала пьесы. */
  const choose = (which: MusicMood, number: number) => {
    const next = Math.max(0, number);
    setVariant(next);
    Object.assign(MOODS[which], next === 0 ? structuredClone(MOODS_ORIGINAL[which]) : moodVariant(MOODS_ORIGINAL[which], next));
    setEdits((edits) => edits + 1);
    if (playing) {
      start(which);
    }
  };
  const edit = (patch: Partial<MoodSpec>) => {
    Object.assign(MOODS[mood], patch);
    setEdits((edits) => edits + 1);
  };

  return (
    <div className="mt-4 text-13">
      <div className="flex flex-wrap items-center gap-3">
        {MOOD_NAMES.map((value) => (
          <button key={value} type="button"
                  className={`link ${mood === value ? 'link-active text-accent' : 'text-ink-dim'}`}
                  onClick={() => {
                    setMood(value);
                    setVariant(0);
                    if (playing) {
                      start(value);
                    }
                  }}>
            {t(MOOD_LABELS[value])}
          </button>
        ))}
        <button type="button" className="link ml-4 text-ink-bright" onClick={() => (playing ? stop() : start(mood))}>
          {playing ? `■ ${t('soundlab.stop')}` : `▶ ${t('soundlab.play')}`}
        </button>
        <button type="button" className="link text-ink-bright"
                onClick={() => {
                  if (!engine.current) {
                    start(mood);
                  }
                  engine.current?.queueBridge();
                }}>
          ★ {t('soundlab.bridge.now')}
        </button>
      </div>

      <VariantBar variant={variant} onChoose={(number) => choose(mood, number)} />

      <div className="mt-4 grid grid-cols-1 gap-x-8 gap-y-2 md:grid-cols-2">
        <label className="flex items-center gap-3">
          <span className="w-28 text-ink-dim">scale</span>
          <select className="field" value={scaleName(spec.scale)}
                  onChange={(event) => edit({ scale: SCALES[event.target.value as keyof typeof SCALES] })}>
            {Object.keys(SCALES).map((name) => <option key={name} value={name}>{name}</option>)}
            {scaleName(spec.scale) === 'custom' ? <option value="custom">custom</option> : null}
          </select>
        </label>
        <label className="flex items-center gap-3">
          <span className="w-28 text-ink-dim">progression</span>
          <input className="field w-40" defaultValue={spec.progression.join(', ')}
                 key={`${mood}-${variant}`}
                 onBlur={(event) => {
                   const degrees = event.target.value.split(/[\s,]+/).map(Number).filter((n) => Number.isInteger(n) && n >= 0);
                   if (degrees.length > 0) {
                     edit({ progression: degrees });
                   }
                 }} />
        </label>
        <label className="flex items-center gap-3">
          <span className="w-28 text-ink-dim">voicing</span>
          <select className="field" value={spec.voicing ?? 'seventh'}
                  onChange={(event) => edit({ voicing: event.target.value as MoodSpec['voicing'] })}>
            {VOICINGS.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-3">
          <span className="w-28 text-ink-dim">arpTone</span>
          <select className="field" value={spec.arpTone ?? 'soft'}
                  onChange={(event) => edit({ arpTone: event.target.value as MoodSpec['arpTone'] })}>
            {ARP_TONES.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-3">
          <span className="w-28 text-ink-dim">guitarPattern</span>
          <select className="field" value={spec.guitarPattern ?? 'chug'}
                  onChange={(event) => edit({ guitarPattern: event.target.value as MoodSpec['guitarPattern'] })}>
            {GUITAR_PATTERNS.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        {MOOD_FIELDS.map(({ field, min, max, step }) => (
          <Slider key={field} name={field} min={min} max={max} step={step}
                  value={(spec[field] as number | undefined) ?? (field === 'bed' ? 1 : 0)}
                  onChange={(value) => edit({ [field]: value })} />
        ))}
      </div>

      <Export value={spec} />
    </div>
  );
}

function SfxLab({ bus }: { bus: () => GainNode | null }) {
  const [name, setName] = useState<SfxName>('beam');
  const [variants, setVariants] = useState<Partial<Record<SfxName, number>>>({});
  const [column, setColumn] = useState(16);
  const [hull, setHull] = useState(3);
  const [, setEdits] = useState(0);
  const recipe = SFX[name];
  const variant = variants[name] ?? 0;

  const options = (which: SfxName): PlayOptions => ({
    to: bus() ?? undefined,
    column,
    ...(which === 'explosion' ? explosionOptions(hull) : {}),
  });
  const play = (which: SfxName) => {
    const to = bus();
    if (!to) {
      return;
    }
    if (which === 'pop') {
      playPops(3, options(which));
    } else {
      playRecipe(SFX[which], options(which));
    }
  };
  /** Залп как в бою: луч, очередь, пуск ракеты, попадания, перехват и гибель корабля. */
  const salvo = () => {
    const to = bus();
    if (!to) {
      return;
    }
    playRecipe(SFX.beam, { to, column: 3 });
    playRecipe(SFX.hit, { to, column: 27, delayMs: 60 });
    playRecipe(SFX.cannon, { to, column: 5, delayMs: 300 });
    playRecipe(SFX.hit, { to, column: 26, delayMs: 740 });
    playRecipe(SFX.launch, { to, column: 28, delayMs: 900 });
    playRecipe(SFX.pointDefence, { to, column: 6, delayMs: 1500 });
    playPops(2, { to, column: 9, delayMs: 1560 });
    playRecipe(SFX.launch, { to, column: 28, delayMs: 1900 });
    playRecipe(SFX.hitHeavy, { to, column: 4, delayMs: 2660 });
    playRecipe(SFX.explosion, { to, column: 4, delayMs: 2700, ...explosionOptions(hull) });
  };

  const choose = (number: number) => {
    const next = Math.max(0, number);
    setVariants((current) => ({ ...current, [name]: next }));
    SFX[name] = next === 0 ? structuredClone(SFX_ORIGINAL[name]) : sfxVariant(SFX_ORIGINAL[name], next);
    setEdits((edits) => edits + 1);
    play(name);
  };
  const editLayer = (index: number, patch: Partial<SfxLayer>) => {
    SFX[name] = { layers: recipe.layers.map((layer, at) => (at === index ? { ...layer, ...patch } : layer)) };
    setEdits((edits) => edits + 1);
  };

  return (
    <div className="mt-4 text-13">
      <div className="flex flex-wrap items-center gap-3">
        {SFX_NAMES.map((value) => (
          <span key={value} className="flex items-center gap-1">
            <button type="button" className="link text-ink-bright" title={t('soundlab.play')}
                    onClick={() => play(value)}>▶</button>
            <button type="button"
                    className={`link ${name === value ? 'link-active text-accent' : 'text-ink-dim'}`}
                    onClick={() => setName(value)}>
              {t(SFX_LABELS[value])}
            </button>
          </span>
        ))}
        <button type="button" className="link ml-4 text-ink-bright" onClick={salvo}>
          ▶ {t('soundlab.salvo')}
        </button>
      </div>

      <VariantBar variant={variant} onChoose={choose} />

      <div className="mt-3 flex flex-wrap gap-x-8 gap-y-2">
        <Slider name={t('soundlab.column')} min={0} max={31} step={1} value={column} onChange={setColumn} />
        {name === 'explosion'
          ? <Slider name={t('soundlab.hull')} min={1} max={6} step={1} value={hull} onChange={setHull} />
          : null}
      </div>

      <div className="mt-4 space-y-3">
        {recipe.layers.map((layer, index) => (
          <div key={`${name}-${variant}-${index}`} className="border-t border-space-700 pt-2">
            <div className="flex items-center gap-3 text-ink-dim">
              <span className="text-ink">{t('soundlab.layer', { n: index + 1 })} · {layer.source}</span>
              {layer.source === 'tone' ? (
                <select className="field" value={layer.wave ?? 'sine'}
                        onChange={(event) => editLayer(index, { wave: event.target.value as OscillatorType })}>
                  {WAVES.map((wave) => <option key={wave} value={wave}>{wave}</option>)}
                </select>
              ) : (
                <select className="field" value={layer.filter ?? 'lowpass'}
                        onChange={(event) => editLayer(index, { filter: event.target.value as BiquadFilterType })}>
                  {FILTERS.map((filter) => <option key={filter} value={filter}>{filter}</option>)}
                </select>
              )}
              <button type="button" className="link text-ink-bright" onClick={() => play(name)}>▶</button>
            </div>
            <div className="mt-1 grid grid-cols-1 gap-x-8 gap-y-1 md:grid-cols-3">
              {LAYER_FIELDS.filter(({ field }) => field !== 'q' || layer.source === 'noise').map(({ field, min, max, step }) => (
                <Slider key={field} name={field} min={min} max={max} step={step}
                        value={(layer[field] as number | undefined) ?? 1}
                        onChange={(value) => editLayer(index, { [field]: value })} />
              ))}
            </div>
          </div>
        ))}
      </div>

      <Export value={recipe} />
    </div>
  );
}

/**
 * Лейтмотив: зерно, лад и гармония темы, её ноты лентой и проведение в любой сцене.
 *
 * Тема звучит внутри сцены, а не сама по себе: её смысл в том, как она ложится на темп и
 * тембр сцены. Поэтому здесь играет сцена, а «тема сейчас» просит провести тему с ближайшего
 * аккорда, не дожидаясь её очереди.
 */
function MotifLab({ bus }: { bus: () => GainNode | null }) {
  const [settings, setSettings] = useState<LeitmotifSettings>({
    seed: LEITMOTIF.seed, mode: LEITMOTIF.mode, progression: LEITMOTIF.progression,
  });
  const [scene, setScene] = useState<MusicMood>('menu');
  const [playing, setPlaying] = useState(false);
  const engine = useRef<AmbientMusic | null>(null);

  const start = (which: MusicMood): AmbientMusic | null => {
    engine.current?.dispose();
    const out = audioOutput();
    const node = bus();
    if (!out || !node) {
      return null;
    }
    engine.current = new AmbientMusic(out.ctx, node, which, 1996);
    engine.current.start();
    setPlaying(true);
    return engine.current;
  };
  const stop = () => {
    engine.current?.dispose();
    engine.current = null;
    setPlaying(false);
  };
  useEffect(() => () => engine.current?.dispose(), []);

  /** Новая тема звучит сразу: играющая сцена проводит её с ближайшего аккорда. */
  const apply = (next: LeitmotifSettings) => {
    setLeitmotif(next);
    setSettings(next);
    engine.current?.queueMotif();
  };
  const playMotif = () => (engine.current ?? start(scene))?.queueMotif();

  return (
    <div className="mt-4 text-13">
      <p className="text-ink-soft">{t('soundlab.motif.what')}</p>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <span className="text-ink-dim">{t('soundlab.motif.scene')}</span>
        {MOOD_NAMES.map((value) => (
          <button key={value} type="button"
                  className={`link ${scene === value ? 'link-active text-accent' : 'text-ink-dim'}`}
                  onClick={() => {
                    setScene(value);
                    if (playing) {
                      start(value);
                    }
                  }}>
            {t(MOOD_LABELS[value])}
          </button>
        ))}
        <button type="button" className="link ml-4 text-ink-bright" onClick={() => (playing ? stop() : start(scene))}>
          {playing ? `■ ${t('soundlab.stop')}` : `▶ ${t('soundlab.play')}`}
        </button>
        <button type="button" className="link text-ink-bright" onClick={playMotif}>★ {t('soundlab.motif.now')}</button>
        <button type="button" className="link text-ink-bright"
                onClick={() => (engine.current ?? start(scene))?.queueBridge()}>
          ★ {t('soundlab.bridge.now')}
        </button>
      </div>

      <VariantBar variant={settings.seed} onChoose={(seed) => apply({ ...settings, seed })} />

      <div className="mt-3 flex flex-wrap items-center gap-6">
        <label className="flex items-center gap-3">
          <span className="text-ink-dim">{t('soundlab.motif.mode')}</span>
          <select className="field" value={settings.mode}
                  onChange={(event) => apply({ ...settings, mode: event.target.value as MotifMode })}>
            {MOTIF_MODES.map((mode) => <option key={mode} value={mode}>{mode}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-3">
          <span className="text-ink-dim">{t('soundlab.motif.harmony')}</span>
          <select className="field" value={settings.progression}
                  onChange={(event) => apply({ ...settings, progression: Number(event.target.value) })}>
            {EPIC_PROGRESSIONS.map((chords, index) => (
              <option key={chords.join()} value={index}>
                {chords.map((degree) => chordNumeral(LEITMOTIF.scale, degree)).join(' – ')}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="link text-ink-dim" onClick={() => apply({ ...LEITMOTIF_SETTINGS })}>
          {t('soundlab.motif.reset')}
        </button>
      </div>

      <MotifRoll />

      <Export value={settings} />
    </div>
  );
}

/** Ступени мелодии на ленте — те же пределы, что у сочинителя темы. */
const ROLL_LOW = -3;
const ROLL_HIGH = 11;
const ROLL_STEPS = 32;

/**
 * Ноты темы лентой, как в нотном редакторе: по горизонтали восьмые, по вертикали ступени
 * лада. Глазом видно то, что слух ловит не сразу, — повтор A и A', скачок к вершине, спуск к
 * тонике. Над каждым тактом — его аккорд.
 */
function MotifRoll() {
  const cell = 18;
  const row = 10;
  const rows = ROLL_HIGH - ROLL_LOW + 1;
  const top = 16;
  const y = (degree: number) => top + (ROLL_HIGH - degree) * row;
  return (
    <svg viewBox={`0 0 ${ROLL_STEPS * cell} ${top + rows * row}`} className="mt-4 w-full text-ink-faint">
      {Array.from({ length: rows }, (_, index) => {
        const degree = ROLL_HIGH - index;
        const tonic = ((degree % 7) + 7) % 7 === 0;
        return (
          <rect key={degree} x={0} y={y(degree)} width={ROLL_STEPS * cell} height={row}
                fill="currentColor" opacity={tonic ? 0.18 : index % 2 === 0 ? 0.05 : 0.02} />
        );
      })}
      {LEITMOTIF.chords.map((degree, bar) => (
        <g key={bar}>
          <line x1={bar * 8 * cell} x2={bar * 8 * cell} y1={0} y2={top + rows * row}
                stroke="currentColor" strokeWidth={bar === 0 ? 0 : 1} opacity={0.6} />
          <text x={bar * 8 * cell + 4} y={11} fontSize={10} fill="currentColor">
            {chordNumeral(LEITMOTIF.scale, degree)}
          </text>
        </g>
      ))}
      <g className="text-accent">
        {LEITMOTIF.notes.map((note) => (
          <rect key={note.start} x={note.start * cell + 1} y={y(note.degree) + 1}
                width={note.length * cell - 2} height={row - 2} rx={2} fill="currentColor" />
        ))}
      </g>
    </svg>
  );
}

/** Номер варианта: назад, вперёд, случайный; ноль — исходный. */
function VariantBar({ variant, onChoose }: { variant: number; onChoose: (number: number) => void }) {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-3">
      <span className="text-ink-dim">{t('soundlab.variant')}</span>
      <button type="button" className="link" onClick={() => onChoose(variant - 1)} disabled={variant === 0}>◂</button>
      <input className="field w-20 text-center" type="number" min={0} value={variant}
             onChange={(event) => onChoose(Number(event.target.value) || 0)} />
      <button type="button" className="link" onClick={() => onChoose(variant + 1)}>▸</button>
      <button type="button" className="link" onClick={() => onChoose(1 + Math.floor(Math.random() * 9999))}>
        {t('soundlab.random')}
      </button>
      <button type="button" className="link text-ink-dim" onClick={() => onChoose(0)}>{t('soundlab.original')}</button>
    </div>
  );
}

function Slider({ name, min, max, step, value, onChange }: {
  name: string; min: number; max: number; step: number; value: number; onChange: (value: number) => void;
}) {
  return (
    <label className="flex items-center gap-3">
      <span className="w-28 shrink-0 text-ink-dim">{name}</span>
      <input type="range" className="min-w-0 flex-1" min={min} max={max} step={step} value={value}
             onChange={(event) => onChange(Number(event.target.value))} />
      <span className="w-14 text-right tabular-nums text-ink">{+value.toFixed(3)}</span>
    </label>
  );
}

/** Запись таблицы как она есть — ею заменяют запись в файле. */
function Export({ value }: { value: unknown }) {
  const [copied, setCopied] = useState(false);
  const text = JSON.stringify(value, null, 1).replace(/\n\s*/g, ' ');
  return (
    <div className="mt-5 border-t border-space-700 pt-3">
      <div className="flex items-center gap-3">
        <span className="text-ink-dim">{t('soundlab.export')}</span>
        <button type="button" className="link"
                onClick={() => {
                  void navigator.clipboard.writeText(text).then(() => setCopied(true), () => setCopied(false));
                }}>
          {copied ? t('soundlab.copied') : t('soundlab.copy')}
        </button>
      </div>
      <textarea className="field mt-2 h-20 w-full font-mono text-11" readOnly value={text}
                onFocus={(event) => event.target.select()} />
    </div>
  );
}
