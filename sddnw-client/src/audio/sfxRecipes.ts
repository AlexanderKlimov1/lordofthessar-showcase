import { seededRandom } from './musicRules';

/**
 * Рецепты звуков боя — ДАННЫМИ, а не кодом: звук собран из слоёв, и у каждого слоя только
 * числа. Так их правит звуковая мастерская (`ui/soundlab/`) прямо на слух, а найденный
 * вариант переносится сюда одной вставкой — тем же правилом, что и справочники игры: новое
 * звучание — это запись, а не ветка в коде.
 *
 * Слой бывает двух видов: шум через фильтр (хлопки, шипение, рокот) и тон (свист луча, удар
 * низа). У обоих частота едет от `from` к `to` за время звучания, громкость — быстрый
 * подъём (`attack`) и спад по экспоненте (`decay`), секунды. `offsets` повторяют слой со
 * сдвигом: так снаряд звучит очередью из трёх выстрелов.
 */
export interface SfxLayer {
  source: 'noise' | 'tone';
  /** Фильтр шума. */
  filter?: BiquadFilterType;
  /** Форма волны тона. */
  wave?: OscillatorType;
  from: number;
  to: number;
  q?: number;
  peak: number;
  attack: number;
  decay: number;
  detune?: number;
  offsets?: number[];
  /** Длина слоя тянется с размером корпуса — только у взрыва. */
  stretch?: boolean;
}

export interface SfxRecipe {
  layers: SfxLayer[];
}

export type SfxName =
  | 'beam' | 'cannon' | 'launch' | 'hit' | 'hitHeavy' | 'pointDefence' | 'pop' | 'explosion'
  | 'click' | 'turn';

export const SFX_NAMES: SfxName[] =
  ['beam', 'cannon', 'launch', 'hit', 'hitHeavy', 'pointDefence', 'pop', 'explosion', 'click', 'turn'];

/** Очередь снаряда — с тем же шагом в 90 мс, каким поле рисует три росчерка. */
const BURST = [0, 0.09, 0.18];

export const SFX: Record<SfxName, SfxRecipe> = {
  /** Луч: электрический свист, падающий по высоте, и гул под ним. */
  beam: {
    layers: [
      { source: 'tone', wave: 'sawtooth', from: 2200, to: 260, peak: 0.13, attack: 0.004, decay: 0.38 },
      { source: 'tone', wave: 'square', from: 1650, to: 200, peak: 0.07, attack: 0.004, decay: 0.34, detune: 12 },
      { source: 'tone', wave: 'sine', from: 240, to: 110, peak: 0.18, attack: 0.01, decay: 0.3 },
    ],
  },
  /** Снаряд: очередь хлопков шума с ударом низа. */
  cannon: {
    layers: [
      { source: 'noise', filter: 'lowpass', from: 2400, to: 300, peak: 0.32, attack: 0.002, decay: 0.12, offsets: BURST },
      { source: 'tone', wave: 'sine', from: 160, to: 48, peak: 0.35, attack: 0.002, decay: 0.14, offsets: BURST },
    ],
  },
  /**
   * Пуск ракеты: шипение с нарастающей высотой — двигатель набирает тягу. Пик выше прочих:
   * полосовой фильтр оставляет от шума малую долю энергии. Перелёт по полю — тот же рецепт тише.
   */
  launch: {
    layers: [
      { source: 'noise', filter: 'bandpass', from: 380, to: 2600, q: 2.5, peak: 1, attack: 0.12, decay: 0.55 },
      { source: 'tone', wave: 'triangle', from: 140, to: 420, peak: 0.4, attack: 0.08, decay: 0.5 },
    ],
  },
  /** Удар луча или снаряда: треск и глухой толчок. */
  hit: {
    layers: [
      { source: 'noise', filter: 'lowpass', from: 5000, to: 400, peak: 0.3, attack: 0.003, decay: 0.18 },
      { source: 'tone', wave: 'sine', from: 180, to: 40, peak: 0.25, attack: 0.003, decay: 0.2 },
    ],
  },
  /** Удар ракеты — крупнее: она несёт заряд. */
  hitHeavy: {
    layers: [
      { source: 'noise', filter: 'lowpass', from: 3000, to: 120, peak: 0.5, attack: 0.003, decay: 0.5 },
      { source: 'tone', wave: 'sine', from: 120, to: 40, peak: 0.45, attack: 0.003, decay: 0.45 },
    ],
  },
  /** Выстрел ближней обороны по ракете — короткий и сухой. */
  pointDefence: {
    layers: [
      { source: 'noise', filter: 'highpass', from: 4000, to: 1500, peak: 0.15, attack: 0.002, decay: 0.06 },
    ],
  },
  /** Сбитая ракета — хлопок; звучит по разу на каждую, шагом в 90 мс. */
  pop: {
    layers: [
      { source: 'noise', filter: 'bandpass', from: 2600, to: 700, q: 1.5, peak: 0.28, attack: 0.002, decay: 0.09 },
    ],
  },
  /**
   * Гибель корабля: рокот, уходящий вниз, удар низа и треск обломков. Записан для фрегата;
   * у крупного корпуса рокот тянется дольше (`stretch`), а громкость растёт.
   */
  explosion: {
    layers: [
      { source: 'noise', filter: 'lowpass', from: 2800, to: 60, peak: 0.6, attack: 0.01, decay: 1.05, stretch: true },
      { source: 'tone', wave: 'sine', from: 90, to: 26, peak: 0.6, attack: 0.01, decay: 0.84, stretch: true },
      {
        source: 'noise', filter: 'highpass', from: 3500, to: 2000, peak: 0.12, attack: 0.002, decay: 0.04,
        offsets: [0.08, 0.175, 0.32, 0.29, 0.46, 0.68],
      },
    ],
  },
  /**
   * Нажатие кнопки главной сцены (07.10.2026, решение хозяина проекта): короткий мягкий щелчок —
   * тон с быстрым спадом и чуть шума сверху. Тихий нарочно: его слышат десятки раз за ход.
   */
  click: {
    layers: [
      { source: 'tone', wave: 'triangle', from: 1400, to: 900, peak: 0.12, attack: 0.002, decay: 0.05 },
      { source: 'noise', filter: 'highpass', from: 5000, to: 3000, peak: 0.04, attack: 0.001, decay: 0.02 },
    ],
  },
  /**
   * Конец хода — главное нажатие сцены: два тона вверх, как подтверждение, и мягкий гул под
   * ними. Длиннее щелчка, но короче секунды: ход объявляют часто.
   */
  turn: {
    layers: [
      { source: 'tone', wave: 'sine', from: 440, to: 440, peak: 0.16, attack: 0.005, decay: 0.18 },
      { source: 'tone', wave: 'sine', from: 660, to: 660, peak: 0.14, attack: 0.005, decay: 0.32, offsets: [0.09] },
      { source: 'tone', wave: 'triangle', from: 110, to: 90, peak: 0.12, attack: 0.02, decay: 0.4 },
    ],
  },
};

/** Рецепты как были при загрузке — чтобы мастерская могла вернуть исходное. */
export const SFX_ORIGINAL: Record<SfxName, SfxRecipe> = structuredClone(SFX);

const WAVES: OscillatorType[] = ['sine', 'triangle', 'sawtooth', 'square'];
const clampFrequency = (value: number): number => Math.round(Math.min(16000, Math.max(20, value)));

/**
 * Вариант рецепта по номеру: те же слои, сдвинутые жребием — высота на полоктавы в обе
 * стороны, громкость и длина на треть, изредка другая форма волны. Номер — зерно: один и тот
 * же номер даёт один и тот же вариант, и его можно назвать словами («беру седьмой»).
 */
export function sfxVariant(base: SfxRecipe, seed: number): SfxRecipe {
  const random = seededRandom(seed * 7919 + 13);
  const scale = (low: number, high: number) => low + random() * (high - low);
  return {
    layers: base.layers.map((layer) => {
      const pitch = 2 ** scale(-0.5, 0.5);
      return {
        ...layer,
        from: clampFrequency(layer.from * pitch * 2 ** scale(-0.25, 0.25)),
        to: clampFrequency(layer.to * pitch * 2 ** scale(-0.25, 0.25)),
        peak: +(layer.peak * scale(0.75, 1.3)).toFixed(3),
        attack: +(layer.attack * scale(0.6, 1.6)).toFixed(4),
        decay: +(layer.decay * scale(0.65, 1.5)).toFixed(3),
        q: layer.q === undefined ? undefined : +(layer.q * scale(0.6, 1.6)).toFixed(2),
        wave: layer.wave && random() < 0.3 ? WAVES[Math.floor(random() * WAVES.length)] : layer.wave,
      };
    }),
  };
}
