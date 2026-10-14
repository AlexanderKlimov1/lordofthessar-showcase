/**
 * Сэмплы рояля заставки — Salamander Grand Piano (CC-BY 3.0, `public/audio/piano/CREDITS.md`).
 *
 * Синтезированный рояль не дотянул до настоящего (хозяин проекта слышал «не совсем
 * пианино»), поэтому вступление играет записанный инструмент. Записаны 30 нот через малую
 * терцию, от ля субконтроктавы до до пятой октавы; промежуточные ноты получаются сдвигом
 * высоты ближайшей записанной — не больше чем на полтора полутона, на слух это незаметно.
 *
 * Сэмплы берутся по АДРЕСУ, а не импортом в сборку: в git их нет (их скачивает
 * `tools/fetch_piano.py`), и импорт сделал бы их условием сборки. Не нашлись — рояль молчит,
 * а музыка играет дальше: заставка без рояля лучше, чем упавшая заставка.
 */

const NAMES = ['C', 'Ds', 'Fs', 'A'];
/** Высота записанной ноты (MIDI) → имя файла. */
const RECORDED: [number, string][] = [
  [21, 'A0'],
  ...Array.from({ length: 7 }, (_, octave) => NAMES.map((name, index): [number, string] =>
    [24 + octave * 12 + index * 3, `${name}${octave + 1}`])).flat(),
  [108, 'C8'],
];

export interface PianoSamples {
  /** Записанная нота, ближайшая к нужной, и во сколько раз ускорить её воспроизведение. */
  pick(note: number): { buffer: AudioBuffer; rate: number } | null;
}

const loading = new WeakMap<BaseAudioContext, Promise<PianoSamples>>();

/** Загружает сэмплы в контекст звука — один раз на контекст. */
export function loadPianoSamples(ctx: BaseAudioContext): Promise<PianoSamples> {
  let promise = loading.get(ctx);
  if (!promise) {
    promise = (async () => {
      const buffers = new Map<number, AudioBuffer>();
      await Promise.all(RECORDED.map(async ([note, name]) => {
        try {
          const response = await fetch(`/audio/piano/${name}.mp3`);
          if (!response.ok) {
            return;
          }
          buffers.set(note, await ctx.decodeAudioData(await response.arrayBuffer()));
        } catch {
          // Нет файла или не разобрался — эта нота возьмётся у соседей.
        }
      }));
      const recorded = [...buffers.keys()].sort((a, b) => a - b);
      return {
        pick(note: number) {
          if (recorded.length === 0) {
            return null;
          }
          const nearest = recorded.reduce((best, candidate) =>
            (Math.abs(candidate - note) < Math.abs(best - note) ? candidate : best), recorded[0]);
          return { buffer: buffers.get(nearest)!, rate: 2 ** ((note - nearest) / 12) };
        },
      };
    })();
    loading.set(ctx, promise);
  }
  return promise;
}
