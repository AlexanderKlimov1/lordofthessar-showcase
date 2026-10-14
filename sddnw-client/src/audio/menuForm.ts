import { SCALES, chordNotes, scaleNote } from './musicRules';
import type { Leitmotif } from './leitmotif';

/**
 * Сценарий музыки главного меню — история, а не цикл (решение хозяина проекта, по разбору
 * трека: заставка должна давать погружение в неизвестное, интерес, нарастающую опасность,
 * борьбу и победу в конце).
 *
 * Прежде меню было циклом сцены со вставками темы и проигрыша: мажорный гимн с первой
 * секунды, тема трижды одинаково, кульминация на четырёх пятых длины и возврат к началу с
 * затуханием — то есть ни загадки, ни роста опасности, ни победы. Здесь каждая часть делает
 * своё, и порядок частей задан:
 *
 * | часть      | тактов | что делает |
 * |------------|--------|------------|
 * | неизвестное| 12     | гул на тонике, лидийский блеск, далёкие аккорды ♭VI и ♭III, обрывки темы внизу, без ритма |
 * | интерес    | 8      | тема впервые целиком — тихо, в миноре; арпеджио, удар на «раз» |
 * | опасность  | 12     | тема, затем она же на полтона выше, затем фригийское качание i–♭II; остинато ускоряется |
 * | соло баса  | 4      | одинокий герой: тема у бас-гитары, тейпом |
 * | марш       | 4      | военные барабаны и хор |
 * | подъём     | 6      | дробь сгущается, шум растёт, в конце — пауза в две восьмых |
 * | победа     | 8      | тема в МАЖОРЕ во весь состав, второй раз — к каденции ♭VI–♭VII |
 * | финал      | 2      | тоника мажора ударом и звоном |
 *
 * **История целиком в меню больше не звучит** (04.10.2026, решение хозяина проекта: «в
 * заставке и меню только спокойная тема»). Меню играет `eveningForm` — неизвестное и интерес по
 * кругу; `journeyForm` ждёт своего места — трейлера и победы в партии — и звучит один раз:
 * после финала музыка молчит. В самой игре фоном звучит свой трек — настроение карты
 * галактики; вернувшийся в меню слышит спокойную тему с начала.
 *
 * Аккорды здесь — НАБОРЫ ПОЛУТОНОВ от тоники, а не ступени лада: далёкие аккорды
 * неизвестного (♭VI, ♭III к мажорному ладу) и заимствованные аккорды победы в ладу не
 * лежат, а именно они и дают загадку и триумф.
 */

export type FormPartKind =
  | 'unknown' | 'curiosity' | 'danger' | 'bassSolo' | 'march' | 'rise' | 'victory' | 'final';

/** Нота темы внутри такта: начало и длина в восьмых, высота в полутонах от тоники такта. */
export interface FormNote {
  offset: number;
  length: number;
  pitch: number;
}

export interface FormBar {
  kind: FormPartKind;
  barInPart: number;
  partBars: number;
  /** Сдвиг тоники в полутонах: опасность поднимает тему на полтона. */
  shift: number;
  /** Аккорд — полутоны от тоники такта. */
  chord: number[];
  /** Лад такта — для баса и бас-гитары, которые идут по ступеням. */
  scale: readonly number[];
  /** Мажор или минор — для подписи тональности в нотах. */
  mode: 'major' | 'minor';
  /** Мелодия такта, если тема звучит; обрывок звучит тише и ниже. */
  theme?: FormNote[];
  fragment?: boolean;
}

const BAR = 8;
const major = (root: number) => [root, root + 4, root + 7];
const minor = (root: number) => [root, root + 3, root + 7];
const fifth = (root: number) => [root, root + 7, root + 12];
/** Лидийский блеск: квинта, нона и повышенная кварта сверху — светло и неустойчиво. */
const lydian = (root: number) => [root, root + 7, root + 14, root + 18];
const aeolian = SCALES.aeolian;
const ionian = SCALES.ionian;

/** Аккорд ступени лада — полутонами от тоники (трезвучие). */
const degreeChord = (scale: readonly number[], degree: number): number[] =>
  chordNotes({ root: 0, scale } as never, degree, 3);

/** Тема в миноре: ноты такта полутонами от тоники, октавой выше — регистр меди. */
function minorBar(theme: Leitmotif, bar: number): FormNote[] {
  return theme.notes
    .filter((note) => Math.floor(note.start / BAR) === bar)
    .map((note) => ({
      offset: note.start - bar * BAR,
      length: note.length,
      pitch: scaleNote({ root: 0, scale: theme.scale } as never, note.degree) + 12,
    }));
}

/**
 * Тема в мажоре — превращение, на котором стоит победа. Ступени те же, лад мажорный: ритм и
 * рисунок мелодии не меняются, и слух узнаёт тему. Но написана она была под минорные
 * аккорды, поэтому каждая сильная доля подтягивается к ближайшему звуку НОВОГО аккорда, а
 * слабая, если встала в полутон к звуку аккорда, садится на него: мелодия и гармония не
 * спорят.
 */
function majorBar(theme: Leitmotif, bar: number, chord: number[]): FormNote[] {
  const classes = chord.map((note) => ((note % 12) + 12) % 12);
  const isChordTone = (pitch: number) => classes.includes(((pitch % 12) + 12) % 12);
  /**
   * Звук аккорда для ноты, которую надо подтянуть: из ближних (до кварты в обе стороны)
   * берётся тот, что сохраняет НАПРАВЛЕНИЕ мелодии — шла вверх, идёт вверх. Просто ближайший
   * стягивал соседние ноты темы к одному звуку («фа-диез, фа-диез, фа-диез»), и рисунок,
   * по которому тему узнают, пропадал.
   */
  const snap = (pitch: number, direction: number, previous: number | null) => {
    const near: number[] = [];
    for (let distance = -5; distance <= 5; distance += 1) {
      if (isChordTone(pitch + distance)) {
        near.push(pitch + distance);
      }
    }
    const keepsDirection = near.filter((candidate) => previous === null
      || (direction > 0 ? candidate > previous : direction < 0 ? candidate < previous : candidate === previous));
    const pool = keepsDirection.length > 0 ? keepsDirection : near;
    return pool.reduce((best, candidate) => (Math.abs(candidate - pitch) < Math.abs(best - pitch) ? candidate : best), pool[0] ?? pitch);
  };
  let previousOriginal: number | null = null;
  let previousResult: number | null = null;
  return theme.notes
    .filter((note) => Math.floor(note.start / BAR) === bar)
    .map((note) => {
      const original = scaleNote({ root: 0, scale: ionian } as never, note.degree) + 12;
      const strong = note.start % 4 === 0;
      const clashes = !isChordTone(original)
        && classes.some((tone) => Math.min((((original - tone) % 12) + 12) % 12, (((tone - original) % 12) + 12) % 12) === 1);
      const direction = previousOriginal === null ? 0 : Math.sign(original - previousOriginal);
      const pitch = strong || clashes ? snap(original, direction, previousResult) : original;
      previousOriginal = original;
      previousResult = pitch;
      return { offset: note.start - bar * BAR, length: note.length, pitch };
    });
}

/**
 * Спокойный сценарий меню и заставки вечера: «неизвестное» и «интерес» из той же истории —
 * те самые такты, а не их подобие (их звучание хозяин проекта уже утвердил). Играет по кругу:
 * движок после последнего такта держит паузу-вдох (`EVENING_BREATH_BARS`) и начинает снова.
 */
export function eveningForm(theme: Leitmotif): FormBar[] {
  return journeyForm(theme).filter((bar) => bar.kind === 'unknown' || bar.kind === 'curiosity');
}

/** Тактов тишины между проведениями спокойного сценария — вдох перед новым кругом. */
export const EVENING_BREATH_BARS = 2;

/** Собирает сценарий под нынешнюю тему: мастерская меняет тему, и сценарий следует за ней. */
export function journeyForm(theme: Leitmotif): FormBar[] {
  const bars: FormBar[] = [];
  const part = (kind: FormPartKind, specs: Omit<FormBar, 'kind' | 'barInPart' | 'partBars'>[]) => {
    specs.forEach((spec, barInPart) => bars.push({ kind, barInPart, partBars: specs.length, ...spec }));
  };
  const themeChords = theme.chords.map((degree) => degreeChord(theme.scale, degree));

  // Неизвестное: аккорды по два такта, обрывок темы (её первый такт) — в пятом и одиннадцатом.
  const unknownChords = [fifth(0), fifth(0), lydian(0), lydian(0), major(8), major(8),
    lydian(0), lydian(0), major(3), major(3), fifth(0), fifth(0)];
  part('unknown', unknownChords.map((chord, index) => ({
    shift: 0, chord, scale: aeolian, mode: 'minor' as const,
    ...(index === 4 || index === 10
      ? { theme: minorBar(theme, 0).map((note) => ({ ...note, pitch: note.pitch - 12 })), fragment: true }
      : {}),
  })));

  // Интерес: тема целиком дважды, тихо.
  part('curiosity', Array.from({ length: 8 }, (_, index) => ({
    shift: 0, chord: themeChords[index % 4], scale: theme.scale, mode: 'minor' as const,
    theme: minorBar(theme, index % 4),
  })));

  // Опасность: тема, тема на полтона выше, затем фригийское качание i–♭II на новой высоте.
  part('danger', [
    ...Array.from({ length: 4 }, (_, index) => ({
      shift: 0, chord: themeChords[index], scale: theme.scale, mode: 'minor' as const, theme: minorBar(theme, index),
    })),
    ...Array.from({ length: 4 }, (_, index) => ({
      shift: 1, chord: themeChords[index], scale: theme.scale, mode: 'minor' as const, theme: minorBar(theme, index),
    })),
    ...[minor(0), major(1), minor(0), major(1)].map((chord) => ({
      shift: 1, chord, scale: SCALES.phrygian, mode: 'minor' as const,
    })),
  ]);

  // Борьба: одинокий бас, марш, долгий подъём.
  part('bassSolo', themeChords.map((chord) => ({ shift: 0, chord, scale: theme.scale, mode: 'minor' as const })));
  part('march', [5, 3, 5, 6].map((degree) => ({
    shift: 0, chord: degreeChord(aeolian, degree), scale: aeolian, mode: 'minor' as const,
  })));
  part('rise', [3, 3, 5, 5, 6, 6].map((degree) => ({
    shift: 0, chord: degreeChord(aeolian, degree), scale: aeolian, mode: 'minor' as const,
  })));

  // Победа: тема в мажоре — сперва на I–V–vi–IV, затем к каденции IV–V–♭VI–♭VII.
  const triumph = [major(0), major(7), minor(9), major(5)];
  const cadence = [major(5), major(7), major(8), major(10)];
  part('victory', [...triumph, ...cadence].map((chord, index) => ({
    shift: 0, chord, scale: ionian, mode: 'major' as const, theme: majorBar(theme, index % 4, chord),
  })));

  // Финал: тоника мажора — удар и звон на два такта.
  part('final', [major(0), major(0)].map((chord) => ({ shift: 0, chord, scale: ionian, mode: 'major' as const })));
  return bars;
}
