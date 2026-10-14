import { onSoundChange, soundGain } from './soundSettings';

/**
 * Общий звуковой выход игры: один `AudioContext` на страницу и две шины — музыка и эффекты.
 *
 * Контекст один, потому что браузеры держат их счётное число, а шины две, потому что
 * громкость у музыки и у боя своя. Обе сходятся в ограничитель: залп эскадры поверх пэда
 * музыки иначе хрипел бы перегрузом.
 *
 * Контекст заводится ЛЕНИВО, при первом звуке: пока оба канала выключены (а по умолчанию
 * они выключены), игра не держит звукового движка вовсе. Браузер к тому же не отдаёт звук
 * странице до первого нажатия игрока — для этого `armAudio`.
 */
export interface AudioOutput {
  ctx: AudioContext;
  music: GainNode;
  sfx: GainNode;
}

/** Музыка — фон, ей половина громкости; эффекты — событие, им больше. */
const MUSIC_HEADROOM = 0.5;
const SFX_HEADROOM = 0.8;

let output: AudioOutput | null = null;
const resumeListeners = new Set<() => void>();

function applyGains(): void {
  if (!output) {
    return;
  }
  const now = output.ctx.currentTime;
  // Плавно, а не скачком: скачок громкости щёлкает.
  output.music.gain.setTargetAtTime(soundGain('music') * MUSIC_HEADROOM, now, 0.3);
  output.sfx.gain.setTargetAtTime(soundGain('sfx') * SFX_HEADROOM, now, 0.05);
}

/** Выход, заведённый при надобности. `null` — в браузере нет Web Audio. */
export function audioOutput(): AudioOutput | null {
  if (output) {
    if (output.ctx.state === 'suspended' && !document.hidden) {
      void output.ctx.resume();
    }
    return output;
  }
  if (typeof AudioContext === 'undefined') {
    return null;
  }
  const ctx = new AudioContext();
  const limiter = ctx.createDynamicsCompressor();
  limiter.threshold.value = -14;
  limiter.ratio.value = 6;
  limiter.connect(ctx.destination);
  const music = ctx.createGain();
  const sfx = ctx.createGain();
  music.gain.value = 0;
  sfx.gain.value = 0;
  music.connect(limiter);
  sfx.connect(limiter);
  output = { ctx, music, sfx };
  applyGains();
  return output;
}

/** Подписка на возвращение звука после паузы — музыке нужно догнать расписание. */
export function onAudioResume(listener: () => void): () => void {
  resumeListeners.add(listener);
  return () => resumeListeners.delete(listener);
}

let armed = false;

/**
 * Готовит звук к первому нажатию игрока и ставит паузу на скрытую вкладку. Звать один раз,
 * при старте клиента.
 */
export function armAudio(onGesture: () => void): void {
  if (armed) {
    return;
  }
  armed = true;
  onSoundChange(applyGains);
  const unlock = () => {
    window.removeEventListener('pointerdown', unlock);
    window.removeEventListener('keydown', unlock);
    onGesture();
  };
  window.addEventListener('pointerdown', unlock);
  window.addEventListener('keydown', unlock);
  // Фоновая вкладка, играющая сама себе, раздражает — пока её не видно, звук стоит.
  document.addEventListener('visibilitychange', () => {
    if (!output) {
      return;
    }
    if (document.hidden) {
      void output.ctx.suspend();
    } else {
      void output.ctx.resume().then(() => resumeListeners.forEach((listener) => listener()));
    }
  });
}
