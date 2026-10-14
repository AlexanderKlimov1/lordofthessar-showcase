import { SCALES, seededRandom } from './musicRules';

/**
 * Лейтмотив игры — короткая тема, по которой Stellar Dominion узнают с первых секунд и
 * которая проходит через все сцены: в меню торжественно, в бою тревожно, на совете медленно.
 * Так устроена музыка кино и игр: тема одна, меняются темп, тембр и окружение.
 *
 * **Мотив сочиняется по правилам хука, а не берётся готовым** — поэтому он свой:
 *
 * * четыре такта восьмыми: фраза A, её повтор с вариацией (A'), развитие B и каденция;
 * * повтор — главное средство запоминания: A' повторяет ритм A и почти все ноты, меняется
 *   только хвост — узнавание без скуки;
 * * в B мелодия СКАЧКОМ уходит вверх к вершине (самая высокая нота фразы, на сильной доле), а
 *   затем заполняет скачок шагами вниз — правило «скачок — заполнение» из учебников
 *   мелодики, на нём держится ощущение порыва;
 * * каденция спускается к тонике и кончается долгой нотой — фраза закрыта;
 * * на сильных долях только звуки аккорда этого такта, между ними — проходящие шаги: мелодия
 *   и гармония не спорят ни в одном варианте.
 *
 * **Гармония — «эпическая»:** минорные последовательности, на которых стоит музыка фильмов и
 * трейлеров (i–VI–III–VII и родня). Это общие строительные блоки жанра, никому не
 * принадлежащие; своей тему делает мелодия, а её сочиняет жребий.
 *
 * Мотив задан тремя числами (`LEITMOTIF_SETTINGS`): зерно, лад и номер последовательности.
 * Ноты выводятся из них, поэтому найденный в мастерской мотив переносится в файл одной
 * строкой и звучит потом точно так же.
 */

/** Нота мотива: начало и длина в восьмых от начала фразы, высота — ступень лада от тоники. */
export interface MotifNote {
  start: number;
  length: number;
  degree: number;
}

export type MotifMode = 'aeolian' | 'dorian' | 'phrygian';
export const MOTIF_MODES: MotifMode[] = ['aeolian', 'dorian', 'phrygian'];

/**
 * Эпические последовательности: аккорд на такт, ступени лада от нуля. Записаны для минора;
 * в дорийском четвёртая ступень мажорная, во фригийском вторая пониженная — тот же ход звучит
 * светлее или темнее, и это часть выбора.
 */
export const EPIC_PROGRESSIONS: readonly (readonly number[])[] = [
  [0, 5, 2, 6], // i–VI–III–VII: самый «трейлерный» ход
  [0, 5, 6, 0], // i–VI–VII–i: подъём к тонике
  [0, 3, 5, 6], // i–iv–VI–VII: тяжёлое начало, светлый подъём
  [5, 6, 0, 0], // VI–VII–i: «эпическая каденция» с тоникой на два такта
  [0, 6, 5, 6], // i–VII–VI–VII: качание, тревога
  [0, 2, 6, 3], // i–III–VII–iv: героический поворот вниз
];

export interface LeitmotifSettings {
  seed: number;
  mode: MotifMode;
  /** Номер в `EPIC_PROGRESSIONS`. */
  progression: number;
}

export interface Leitmotif extends LeitmotifSettings {
  scale: readonly number[];
  chords: readonly number[];
  notes: MotifNote[];
}

/** Мотив игры. Найденный в мастерской вписывается сюда. */
export const LEITMOTIF_SETTINGS: LeitmotifSettings = { seed: 0, mode: 'aeolian', progression: 0 };

/** Тактов в мотиве и восьмых в такте. */
export const MOTIF_BARS = 4;
const BAR = 8;
/** Пределы мелодии, ступени от тоники: чуть ниже тоники и до кварты октавой выше. */
const LOWEST = -3;
const HIGHEST = 11;

/** Ритмы фразы A: сумма — такт восьмыми; длинная нота в конце даёт фразе дышать. */
const CELLS_A = [[2, 2, 4], [3, 1, 4], [2, 1, 1, 4], [1, 1, 2, 4], [4, 2, 2], [3, 3, 2], [2, 2, 2, 2]];
/** Ритмы развития B — подвижнее: здесь мелодия идёт к вершине. */
const CELLS_B = [[1, 1, 1, 1, 2, 2], [2, 1, 1, 2, 2], [1, 1, 2, 1, 1, 2], [2, 2, 1, 1, 2], [3, 1, 2, 2]];
/** Ритмы каденции — кончаются долгой нотой. */
const CELLS_END = [[2, 2, 4], [3, 1, 4], [2, 6], [1, 1, 6]];

/** Сочиняет мотив по зерну, ладу и гармонии. Одни и те же числа — одни и те же ноты. */
export function composeMotif(settings: LeitmotifSettings): Leitmotif {
  const random = seededRandom(settings.seed * 7349 + 101);
  const pick = <T,>(items: readonly T[]): T => items[Math.floor(random() * items.length)];
  const chords = EPIC_PROGRESSIONS[settings.progression] ?? EPIC_PROGRESSIONS[0];

  /** Звуки аккорда такта во всех октавах, что влезают в пределы мелодии. */
  const chordTones = (bar: number): number[] => {
    const root = chords[bar];
    const tones: number[] = [];
    for (let octave = -1; octave <= 1; octave += 1) {
      for (const third of [0, 2, 4]) {
        const degree = root + third + octave * 7;
        if (degree >= LOWEST && degree <= HIGHEST) {
          tones.push(degree);
        }
      }
    }
    return tones;
  };
  const nearestTone = (bar: number, target: number): number =>
    chordTones(bar).reduce((best, tone) => (Math.abs(tone - target) < Math.abs(best - target) ? tone : best));
  /** Проходящий шаг от прежней ноты к цели; на месте мелодия не стоит. */
  const stepToward = (previous: number, target: number): number => {
    const direction = target > previous ? 1 : target < previous ? -1 : (random() < 0.5 ? 1 : -1);
    return Math.min(HIGHEST, Math.max(LOWEST, previous + direction));
  };

  /** Такт по ритму и цели на каждую ноту: сильная доля — звук аккорда, слабая — шаг. */
  const phrase = (bar: number, cell: readonly number[], targets: (index: number) => number, start: number) => {
    const notes: MotifNote[] = [];
    let position = bar * BAR;
    let previous = start;
    cell.forEach((length, index) => {
      const target = targets(index);
      const strong = position % 4 === 0;
      const degree = strong || index === 0 ? nearestTone(bar, target) : stepToward(previous, target);
      notes.push({ start: position, length, degree });
      previous = degree;
      position += length;
    });
    return notes;
  };

  // A: от звука аккорда в середине лада — немного вверх или вниз.
  const cellA = pick(CELLS_A);
  const opening = nearestTone(0, pick([2, 4]));
  const drift = random() < 0.5 ? 2 : -1;
  const a = phrase(0, cellA, (index) => opening + (drift * index) / Math.max(1, cellA.length - 1), opening);

  // A': тот же ритм и почти те же ноты под новый аккорд, хвост меняется.
  const turn = random() < 0.5 ? 1 : -1;
  const aPrime = a.map((note, index) => {
    const start = note.start + BAR;
    const last = index === a.length - 1;
    const wanted = last ? note.degree + turn * 2 : note.degree;
    const degree = start % 4 === 0 ? nearestTone(1, wanted) : Math.min(HIGHEST, Math.max(LOWEST, wanted));
    return { start, length: note.length, degree };
  });

  // B: скачок вверх к вершине на сильной доле, затем шагами вниз.
  const cellB = pick(CELLS_B);
  const before = aPrime[aPrime.length - 1].degree;
  // Вершина обязана быть выше всего, что звучало до неё: иначе порыва нет, фраза топчется.
  const heardHighest = Math.max(...a.map((note) => note.degree), ...aPrime.map((note) => note.degree));
  const wantedPeak = nearestTone(2, 7 + Math.floor(random() * 2));
  const peak = wantedPeak > heardHighest
    ? wantedPeak
    : chordTones(2).filter((tone) => tone > heardHighest).sort((x, y) => x - y)[0] ?? wantedPeak;
  const middle = Math.floor(cellB.length / 2);
  const b = phrase(2, cellB, (index) => (index <= middle
    ? before + 3 + ((peak - before - 3) * index) / Math.max(1, middle)
    : peak - (index - middle)), before);
  // Вершина — нота на третьей доле такта (или на первой, если третью покрыла долгая нота):
  // сильная доля, и выше неё во фразе нет ничего.
  const peakNote = b.find((note) => note.start === 2 * BAR + 4) ?? b[0];
  peakNote.degree = peak;

  // Каденция: спуск к звуку последнего аккорда, ближайшему к тонике, и долгая нота.
  const cellEnd = pick(CELLS_END);
  const from = b[b.length - 1].degree;
  const home = nearestTone(3, 0);
  const end = phrase(3, cellEnd, (index) => from + ((home - from) * (index + 1)) / cellEnd.length, from);
  end[end.length - 1].degree = home;

  // Выше вершины во фразе нет ничего: всё, что до неё дотянулось, опускается под неё —
  // на сильной доле к звуку своего аккорда, на слабой шагом.
  const notes = [...a, ...aPrime, ...b, ...end];
  for (const note of notes) {
    if (note !== peakNote && note.degree >= peak) {
      const bar = Math.floor(note.start / BAR);
      note.degree = note.start % 4 === 0
        ? Math.max(...chordTones(bar).filter((tone) => tone < peak))
        : peak - 1;
    }
  }

  return {
    ...settings,
    scale: SCALES[settings.mode],
    chords,
    notes,
  };
}

/**
 * Действующий мотив. Правится на месте: движок музыки читает его при каждом проведении темы,
 * и мастерская меняет его прямо во время звучания.
 */
export const LEITMOTIF: Leitmotif = composeMotif(LEITMOTIF_SETTINGS);

export function setLeitmotif(settings: LeitmotifSettings): void {
  Object.assign(LEITMOTIF, composeMotif(settings));
}

/** Римская цифра аккорда ступени: заглавная — мажор, строчная — минор, «°» — уменьшённый. */
export function chordNumeral(scale: readonly number[], degree: number): string {
  const names = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII'];
  const at = (step: number) => scale[(degree + step) % 7] + 12 * Math.floor((degree + step) / 7);
  const third = at(2) - at(0);
  const fifth = at(4) - at(0);
  const name = names[degree % 7];
  if (fifth === 6) {
    return `${name.toLowerCase()}°`;
  }
  return third === 4 ? name : name.toLowerCase();
}
