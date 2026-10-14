import type { WeaponKindCode } from '../api/types';
import { audioOutput } from './audioOutput';
import { seededRandom } from './musicRules';
import { SFX, type SfxLayer, type SfxRecipe } from './sfxRecipes';
import { soundLevel } from './soundSettings';

/**
 * Звуки тактического боя — п. 8. Синтезом, как и музыка: файлов нет, лицензий нет, а звук
 * следует за тем, что показывает поле, — у каждого вида оружия свой выстрел, удар считается
 * по попаданию, а взрыв тем громче и дольше, чем крупнее погибший корпус.
 *
 * Сами звуки — рецептами в `sfxRecipes.ts`: здесь только то, КОГДА и ГДЕ звучать.
 *
 * Звуки привязаны к ТЕМ ЖЕ срокам, что и рисунок (`ShotEffect`, `ShipDebris`): выстрел — в
 * миг вылета, удар — в миг, когда снаряд долетел и вспыхнул. Расписываются они по часам
 * звука, а не таймером страницы: залпы одной пачки идут шагом в 70–160 мс, и таймер
 * страницы смазал бы этот ритм.
 *
 * Положение на поле слышно: источник панорамируется по столбцу клетки — выстрел с левого
 * края звучит слева. Поле 32 клетки в ширину, и панорама не доходит до края уха нарочно:
 * жёсткое лево-право в наушниках утомляет.
 *
 * Чего пока нет: звука удара по ЩИТУ — сервер не говорит в событии залпа, сколько урона
 * принял щит, а сколько корпус; встанет сюда, когда событие это понесёт.
 */

/** Ширина поля в клетках — для панорамы. */
const FIELD_COLUMNS = 32;
/** Насколько панорама уводит к краю: 1 — до упора. */
const PAN_WIDTH = 0.6;
/** Одинаковый звук чаще этого не повторяется: восемь ракет роя не должны звучать стеной. */
const SAME_SOUND_GAP_SECONDS = 0.035;
/** Сколько звуков звучит разом; сверх этого новые молча пропускаются. */
const MAX_VOICES = 28;
/** Шаг хлопков сбитых ракет — тот же, каким поле рисует их взрывы. */
const POP_STEP_SECONDS = 0.09;
/** Перелёт ракеты по полю тише пуска: это продолжение, а не новый залп. */
const LEG_GAIN = 0.35;

let noiseBuffer: AudioBuffer | null = null;
let voices = 0;
const lastPlayed = new Map<string, number>();

/** Как сыграть рецепт. */
export interface PlayOptions {
  /** Через сколько звучать, миллисекунды. */
  delayMs?: number;
  /** Столбец поля для панорамы; пусто — по центру. */
  column?: number;
  /** Множитель громкости. */
  gain?: number;
  /** Во сколько раз тянутся слои с `stretch`. */
  stretch?: number;
  /**
   * Куда звучать. Пусто — шина эффектов, и тогда звук спрашивает выбор игрока и предел
   * голосов. Мастерская даёт свой узел: там звук нужен всегда, даже выключенный в игре.
   */
  to?: AudioNode;
  /** Ключ повтора: одинаковый звук чаще `SAME_SOUND_GAP_SECONDS` не звучит. */
  key?: string;
}

const layerSeconds = (layer: SfxLayer, stretch: number): number =>
  Math.max(0, ...(layer.offsets ?? [0])) + layer.attack + layer.decay * (layer.stretch ? stretch : 1);

/** Сколько звучит рецепт целиком, секунды. */
export const recipeSeconds = (recipe: SfxRecipe, stretch = 1): number =>
  Math.max(0, ...recipe.layers.map((layer) => layerSeconds(layer, stretch)));

/**
 * Играет рецепт: проверяет, звучать ли вообще, собирает цепочку «громкость → панорама →
 * выход» и разбирает её, когда звук отзвучал. Возвращает, прозвучал ли он.
 */
export function playRecipe(recipe: SfxRecipe, options: PlayOptions = {}): boolean {
  const own = options.to === undefined;
  if (own && (soundLevel('sfx') === 0 || voices >= MAX_VOICES)) {
    return false;
  }
  const out = audioOutput();
  if (!out) {
    return false;
  }
  const { ctx } = out;
  const at = ctx.currentTime + Math.max(0, options.delayMs ?? 0) / 1000;
  if (own && options.key) {
    const previous = lastPlayed.get(options.key);
    if (previous !== undefined && Math.abs(at - previous) < SAME_SOUND_GAP_SECONDS) {
      return false;
    }
    lastPlayed.set(options.key, at);
  }
  const stretch = options.stretch ?? 1;
  const input = ctx.createGain();
  input.gain.value = options.gain ?? 1;
  const pan = ctx.createStereoPanner();
  pan.pan.value = options.column === undefined
    ? 0
    : ((options.column + 0.5) / FIELD_COLUMNS * 2 - 1) * PAN_WIDTH;
  input.connect(pan);
  pan.connect(options.to ?? out.sfx);
  for (const layer of recipe.layers) {
    for (const offset of layer.offsets ?? [0]) {
      playLayer(ctx, input, at + offset, layer, stretch);
    }
  }
  if (own) {
    voices += 1;
  }
  window.setTimeout(() => {
    if (own) {
      voices -= 1;
    }
    input.disconnect();
    pan.disconnect();
  }, (at - ctx.currentTime + recipeSeconds(recipe, stretch) + 0.2) * 1000);
  return true;
}

function noise(ctx: AudioContext): AudioBuffer {
  if (!noiseBuffer || noiseBuffer.sampleRate !== ctx.sampleRate) {
    // Шум считанным жребием: звук от этого не меняется, но правило проекта одно на всё.
    const random = seededRandom(0xb00);
    noiseBuffer = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);
    const data = noiseBuffer.getChannelData(0);
    for (let index = 0; index < data.length; index += 1) {
      data[index] = random() * 2 - 1;
    }
  }
  return noiseBuffer;
}

/** Один слой: источник, его частота от `from` к `to`, огибающая громкости. */
function playLayer(ctx: AudioContext, input: AudioNode, at: number, layer: SfxLayer, stretch: number): void {
  const decay = layer.decay * (layer.stretch ? stretch : 1);
  const end = at + layer.attack + decay;
  const gain = ctx.createGain();
  gain.gain.setValueAtTime(0.0001, at);
  gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, layer.peak), at + layer.attack);
  gain.gain.exponentialRampToValueAtTime(0.0001, end);
  gain.connect(input);
  if (layer.source === 'noise') {
    const source = ctx.createBufferSource();
    source.buffer = noise(ctx);
    // По кругу: взрыв Левиафана длиннее куска шума, оставшегося после сдвига.
    source.loop = true;
    const filter = ctx.createBiquadFilter();
    filter.type = layer.filter ?? 'lowpass';
    filter.Q.value = layer.q ?? 1;
    filter.frequency.setValueAtTime(layer.from, at);
    filter.frequency.exponentialRampToValueAtTime(layer.to, end);
    source.connect(filter);
    filter.connect(gain);
    source.onended = () => {
      filter.disconnect();
      gain.disconnect();
    };
    // Каждый всплеск берёт свой кусок шума, иначе одинаковые удары звучали бы одним сэмплом.
    source.start(at, (at * 7.31) % 1.5);
    source.stop(end + 0.05);
    return;
  }
  const osc = ctx.createOscillator();
  osc.type = layer.wave ?? 'sine';
  osc.detune.value = layer.detune ?? 0;
  osc.frequency.setValueAtTime(layer.from, at);
  osc.frequency.exponentialRampToValueAtTime(layer.to, end);
  osc.connect(gain);
  osc.onended = () => gain.disconnect();
  osc.start(at);
  osc.stop(end + 0.05);
}

/** Сведения о выстреле, которые нужны звуку: те же, что у рисунка. */
export interface ShotSound {
  kind: WeaponKindCode;
  /** Отрезок полёта ракеты — пуск или перелёт, а не удар. */
  leg?: boolean;
  hit: boolean;
  /** Сбито ракет — у перехвата. */
  downed?: number;
  fromX: number;
  toX: number;
  /** Когда выстрел уходит и когда долетает, миллисекунды от появления на поле. */
  delayMs: number;
  arrivalMs: number;
}

/** Хлопки сбитых ракет: по одному на каждую, не больше шести. */
export function playPops(count: number, options: PlayOptions = {}): void {
  for (let index = 0; index < Math.min(6, count); index += 1) {
    playRecipe(SFX.pop, { ...options, delayMs: (options.delayMs ?? 0) + index * POP_STEP_SECONDS * 1000 });
  }
}

/** Выстрел со всем, что за ним следует: вылет, удар или перехват. */
export function playShot(shot: ShotSound): void {
  if (shot.downed !== undefined) {
    // Перехват: короткий выстрел обороны и хлопки сбитых ракет на месте залпа.
    playRecipe(SFX.pointDefence, { delayMs: shot.delayMs, column: shot.fromX, key: 'pd' });
    playPops(shot.downed, { delayMs: shot.arrivalMs, column: shot.toX, key: 'pop' });
    return;
  }
  const fire = shot.kind === 'BEAM' ? SFX.beam : shot.kind === 'PROJECTILE' ? SFX.cannon : SFX.launch;
  playRecipe(fire, {
    delayMs: shot.delayMs,
    column: shot.fromX,
    gain: shot.kind === 'MISSILE' && shot.leg ? LEG_GAIN : 1,
    key: `fire-${shot.kind}`,
  });
  if (shot.hit && !shot.leg) {
    const heavy = shot.kind === 'MISSILE';
    playRecipe(heavy ? SFX.hitHeavy : SFX.hit, { delayMs: shot.arrivalMs, column: shot.toX, key: `hit-${shot.kind}` });
  }
}

/**
 * Взрыв корабля: рецепт записан для фрегата, крупный корпус тянет рокот дольше и звучит
 * громче — фрегат хлопает, Левиафан рушится.
 */
export const explosionOptions = (hullSize: number): Pick<PlayOptions, 'gain' | 'stretch'> => ({
  stretch: (0.7 + 0.35 * Math.max(1, hullSize)) / 1.05,
  gain: Math.min(1, 0.55 + hullSize * 0.08),
});

/** Гибель корабля в клетке `x`. */
export function playExplosion(x: number, hullSize: number): void {
  playRecipe(SFX.explosion, { ...explosionOptions(hullSize), column: x, key: 'explosion' });
}
