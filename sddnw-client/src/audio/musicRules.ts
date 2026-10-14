/**
 * Музыкальные правила фоновой музыки: лады, последовательности аккордов и настроения сцен.
 *
 * Музыку игра не проигрывает, а СОЧИНЯЕТ на лету (`AmbientMusic`): готовых треков нет, и
 * поэтому нет ни чужих лицензий, ни двоичных файлов в истории. Здесь только данные и чистые
 * функции — всё, что звучит, решается этой таблицей, а движок лишь исполняет её.
 *
 * Звучание взято у эмбиента стратегий середины девяностых (MOO II и соседи): медленные
 * пэды, арпеджио с эхом, гул баса, редкие колокольчики. Настроение — это лад, темп и
 * плотность, а не другая мелодия: смена сцены не обрывает музыку, а переводит её.
 */

/** Сцены, у которых своё настроение. */
export type MusicMood = 'menu' | 'galaxy' | 'battle' | 'council';

/** Лады — ступени в полутонах от тоники. */
export const SCALES = {
  ionian: [0, 2, 4, 5, 7, 9, 11],
  dorian: [0, 2, 3, 5, 7, 9, 10],
  phrygian: [0, 1, 3, 5, 7, 8, 10],
  lydian: [0, 2, 4, 6, 7, 9, 11],
  aeolian: [0, 2, 3, 5, 7, 8, 10],
} as const;

export interface MoodSpec {
  /** Тоника — номер ноты MIDI (60 — до первой октавы). */
  root: number;
  scale: readonly number[];
  /** Аккорды ступенями лада, от нуля. */
  progression: readonly number[];
  /** Удары в минуту; шаг движка — восьмая доля. */
  bpm: number;
  /** Сколько тактов по четыре доли держится аккорд. */
  barsPerChord: number;
  /** Громкость слоёв, 0…1. */
  pad: number;
  bass: number;
  arp: number;
  /** Вероятность ноты арпеджио на восьмую долю. */
  arpDensity: number;
  /** Вероятность колокольчика на восьмую долю. */
  bellDensity: number;
  /** Срез фильтра пэда в герцах: ниже — глуше и тревожнее. */
  cutoff: number;
  /** Пульс ударных — только в бою. */
  pulse: number;
  /*
    Приёмы «гимна» — необязательные: настроение без них звучит как прежде. Взяты как ЖАНРОВЫЕ
    черты космического рока (эхо гитары на точку с восьмой, аккорды без терции, пульс баса,
    нарастание), а не как чья-то пьеса: мелодии и гармонии здесь нет, их сочиняет жребий.
  */
  /** Склад аккорда: септаккорд (по умолчанию), sus2 — без терции, add9 — с ноной. */
  voicing?: 'seventh' | 'sus2' | 'add9';
  /** Тембр арпеджио: «гитара» — яркая пила с быстрым затуханием, иначе мягкий треугольник. */
  arpTone?: 'soft' | 'guitar';
  /** Сколько эха возвращается в эхо, 0…0,8: выше — длиннее хвост повторов. */
  echoFeedback?: number;
  /** Пульс баса восьмыми, громкость 0…1. */
  bassPulse?: number;
  /** Высокая протяжная линия, громкость 0…1. */
  lead?: number;
  /** Малый барабан на вторую и четвёртую долю, громкость 0…1. */
  snare?: number;
  /**
   * Нарастание: каждые столько тактов вступает новый слой — пэд, арпеджио, бас с линией,
   * ударные. Пусто — всё звучит сразу.
   */
  buildBars?: number;
  /** Громкость лейтмотива (`leitmotif.ts`), 0…1; пусто — сцена темы не проводит. */
  motif?: number;
  /** Через сколько аккордов сцены тема звучит снова. */
  motifEvery?: number;
  /**
   * Громкость фона, 0…1: множитель для пэда, баса, арпеджио, линии и ударных. Тема и гитара
   * в него не входят — фон уступает им место, а не они тонут в нём. Пусто — единица.
   */
  bed?: number;
  /** Перегруженная электрогитара квинтаккордами, громкость 0…1. */
  guitar?: number;
  /** Сила перегруза, 0…1: от тёплого овердрайва до плотного дисторшна. */
  guitarDrive?: number;
  /** Как играет гитара: «глушёные» восьмые с открытым аккордом на сильную долю или держит аккорд. */
  guitarPattern?: 'chug' | 'sustain';
  /** Громкость проигрыша (`bridge.ts`): хор, военные барабаны, подъём; пусто — проигрыша нет. */
  bridge?: number;
  /** Через сколько аккордов сцены звучит проигрыш. */
  bridgeEvery?: number;
  /**
   * Сценарий формы (`menuForm.ts`) вместо цикла сцены со вставками. `journey` — история
   * целиком, один раз (неизвестное … победа); `evening` — только спокойные части, по кругу с
   * паузой. Пусто — обычный цикл.
   */
  form?: 'journey' | 'evening';
}

/**
 * Настроения. Числа подобраны на слух, а не выведены: правится звучание только здесь.
 *
 * Меню — дорийский ре, загадочно и неторопливо. Галактика — лидийский фа: повышенная
 * четвёртая ступень даёт «космическое» парение, и на нём проходит почти вся партия, поэтому
 * он самый ровный и тихий. Бой — фригийский ми с пониженной второй ступенью (i — bII) и
 * пульсом. Совет — до мажор, торжественно, без арпеджио.
 */
export const MOODS: Record<MusicMood, MoodSpec> = {
  /*
    Меню и заставка вечера — ТОЛЬКО СПОКОЙНАЯ ТЕМА (04.10.2026, решение хозяина проекта):
    рояль «неизвестного» и тихое проведение лейтмотива, по кругу (`form: 'evening'`,
    `menuForm.eveningForm`). Игру зовут «провести вдумчивый спокойный вечер», и марш с
    военными барабанами через сорок секунд после этих слов спорил бы с приглашением. История
    целиком (опасность, марш, победа — `journeyForm`) не выброшена: её место — трейлер и
    победа в партии, куда она переедет своим настроением. Гитары, малого барабана и
    проигрыша здесь поэтому нет: спокойным частям они не нужны.
  */
  menu: {
    root: 49, scale: SCALES.ionian, progression: [0, 5, 3, 1],
    bpm: 112, barsPerChord: 2, pad: 0.2, bass: 0.2, arp: 0.5,
    arpDensity: 1, bellDensity: 0, cutoff: 1700, pulse: 0.45,
    voicing: 'sus2', arpTone: 'guitar', echoFeedback: 0.55,
    bassPulse: 0.7, lead: 0.45, buildBars: 4,
    motif: 0.6, motifEvery: 4,
    bed: 0.6,
    // Громкость частей сценария (удар на «раз» в «интересе»), а не проигрыш: его в меню нет.
    bridge: 0.7,
    form: 'evening',
  },
  galaxy: {
    root: 53, scale: SCALES.lydian, progression: [0, 1, 5, 4],
    bpm: 58, barsPerChord: 2, pad: 0.5, bass: 0.3, arp: 0.2,
    arpDensity: 0.25, bellDensity: 0.04, cutoff: 1800, pulse: 0,
    // На карте проходят часы партии: тема здесь редкая и тихая — напоминание, а не припев.
    motif: 0.3, motifEvery: 12,
    bed: 0.75,
  },
  battle: {
    root: 52, scale: SCALES.phrygian, progression: [0, 1, 0, 6],
    bpm: 96, barsPerChord: 1, pad: 0.4, bass: 0.5, arp: 0.3,
    arpDensity: 0.7, bellDensity: 0, cutoff: 1100, pulse: 0.6,
    motif: 0.5, motifEvery: 8,
    bed: 0.7, guitar: 0.4, guitarDrive: 0.75, guitarPattern: 'chug',
  },
  council: {
    root: 48, scale: SCALES.ionian, progression: [0, 5, 3, 4],
    bpm: 52, barsPerChord: 2, pad: 0.65, bass: 0.45, arp: 0,
    arpDensity: 0, bellDensity: 0.02, cutoff: 2400, pulse: 0,
    // Совет — торжество: тема звучит часто и медленно, как гимн галактики.
    motif: 0.6, motifEvery: 2,
    bed: 0.7,
  },
};

/** Имя лада по его ступеням — для мастерской; чужой набор ступеней зовётся «свой». */
export const scaleName = (scale: readonly number[]): string =>
  Object.entries(SCALES).find(([, steps]) => steps.join() === scale.join())?.[0] ?? 'custom';

/** Настроения как были при загрузке — мастерская правит `MOODS` на месте и возвращает отсюда. */
export const MOODS_ORIGINAL: Record<MusicMood, MoodSpec> = structuredClone(MOODS);

/**
 * Вариант настроения по номеру: другой лад, тоника в пределах кварты, своя
 * последовательность аккордов (первый — всегда тоника, иначе пьеса не «дома») и сдвинутые
 * темп с плотностью. Номер — зерно: один номер — один вариант, его можно назвать словами.
 * Громкость слоёв трогается мягко — вариант должен звучать иначе, а не громче.
 */
export function moodVariant(base: MoodSpec, seed: number): MoodSpec {
  const random = seededRandom(seed * 104729 + 7);
  const scale = (low: number, high: number) => low + random() * (high - low);
  const pick = <T,>(items: readonly T[]): T => items[Math.floor(random() * items.length)];
  const unit = (value: number) => Math.min(1, Math.max(0, +value.toFixed(2)));
  const scales = Object.values(SCALES);
  return {
    ...base,
    root: base.root + Math.floor(scale(-5, 6)),
    scale: pick(scales),
    progression: [0, ...Array.from({ length: 3 }, () => 1 + Math.floor(random() * 6))],
    bpm: Math.round(base.bpm * scale(0.85, 1.15)),
    barsPerChord: base.barsPerChord === 1 ? pick([1, 1, 2]) : pick([1, 2, 2]),
    pad: unit(base.pad * scale(0.8, 1.2)),
    bass: unit(base.bass * scale(0.8, 1.2)),
    arp: unit(base.arp * scale(0.8, 1.2)),
    arpDensity: unit(base.arpDensity * scale(0.6, 1.4)),
    bellDensity: unit(base.bellDensity * scale(0.5, 1.6)),
    cutoff: Math.round(base.cutoff * scale(0.7, 1.4)),
    pulse: unit(base.pulse * scale(0.8, 1.2)),
  };
}

/** Частота ноты MIDI. */
export const frequency = (midi: number): number => 440 * 2 ** ((midi - 69) / 12);

/** Нота ступени лада с переходом через октаву (ступень может быть больше семи). */
export const scaleNote = (spec: MoodSpec, degree: number): number => {
  const size = spec.scale.length;
  const octave = Math.floor(degree / size);
  return spec.root + octave * 12 + spec.scale[((degree % size) + size) % size];
};

/** Аккорд ступени — терции, взятые в ладу: трезвучие или септаккорд. */
export const chordNotes = (spec: MoodSpec, degree: number, size = 4): number[] => {
  // Ступени аккорда от его основы: терции, или секунда вместо терции, или нона сверху.
  const steps = spec.voicing === 'sus2' ? [0, 1, 4, 7]
    : spec.voicing === 'add9' ? [0, 2, 4, 8]
      : [0, 2, 4, 6];
  return steps.slice(0, size).map((step) => scaleNote(spec, degree + step));
};

/**
 * Считанный жребий (mulberry32). `Math.random` музыке не годится по той же причине, что
 * звёздам: одно и то же зерно должно звучать одинаково, иначе нельзя сравнить на слух две
 * правки правил.
 */
export const seededRandom = (seed: number): (() => number) => {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let mixed = state;
    mixed = Math.imul(mixed ^ (mixed >>> 15), mixed | 1);
    mixed ^= mixed + Math.imul(mixed ^ (mixed >>> 7), mixed | 61);
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
  };
};
