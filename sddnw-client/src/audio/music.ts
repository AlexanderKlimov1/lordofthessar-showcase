import { useEffect } from 'react';
import { AmbientMusic } from './AmbientMusic';
import { armAudio, audioOutput, onAudioResume } from './audioOutput';
import type { MusicMood } from './musicRules';
import { LEITMOTIF } from './leitmotif';
import { MUSIC_AVAILABLE, onSoundChange, soundLevel } from './soundSettings';

/**
 * Фоновая музыка игры: одна на страницу, громкость — выбор игрока (`soundSettings.ts`).
 *
 * Музыка играет, только пока её громкость не ноль: выключенная, она останавливает и свой
 * планировщик — ноты, расписанные в тишину, тоже стоят процессора. Браузер не отдаёт звук до
 * первого нажатия, поэтому включённая в прошлый раз музыка заводится первым нажатием.
 */

/** Зерно жребия музыки — постоянное: одна и та же сцена звучит одинаково от запуска к запуску. */
const MUSIC_SEED = 1996;

let engine: AmbientMusic | null = null;
let mood: MusicMood = 'menu';
let gestured = false;

function sync(): void {
  // Музыка пока убрана из игры (`MUSIC_AVAILABLE`): что бы ни лежало в записи, она молчит.
  if (!MUSIC_AVAILABLE || soundLevel('music') === 0) {
    engine?.stop();
    return;
  }
  // До первого нажатия заводить нечего: контекст родился бы немым.
  if (!gestured) {
    return;
  }
  const out = audioOutput();
  if (!out) {
    return;
  }
  engine ??= new AmbientMusic(out.ctx, out.music, mood, MUSIC_SEED);
  engine.start();
}

/** Звать один раз, при старте клиента. */
export function armMusic(): void {
  armAudio(() => {
    gestured = true;
    sync();
  });
  onSoundChange(sync);
  onAudioResume(() => engine?.resync());
}

/** Настроение текущей сцены; смена слышна со следующего аккорда. */
export function useMusicMood(next: MusicMood): void {
  useEffect(() => {
    mood = next;
    engine?.setMood(next);
  }, [next]);
}

/** Тоника одиночного проведения темы — ре первой октавы, номером MIDI. */
const MOMENT_TONIC = 62;
/** Длина восьмой в одиночном проведении, секунды: торжественно, но не тягуче. */
const MOMENT_STEP = 0.2;
/** Не чаще раза в столько миллисекунд: два мига подряд не должны сливаться в повтор. */
const MOMENT_GAP_MS = 4000;
let lastMoment = 0;

/**
 * Лейтмотив в миг, который запоминают, — field-backlog, пункт 180: победа, сцена конца, первое
 * знакомство, включённый на входе звук. Звук — примета, которую узнают с закрытыми глазами, но
 * только если её слышали.
 *
 * Играет музыка — тема вступает с ближайшего аккорда (`queueMotif`), не дожидаясь `motifEvery`.
 * Музыка выключена, а эффекты нет — тема звучит один раз целиком на канале эффектов, мягкой
 * «медью» без фона. Выключено всё — тишина: миг не повод нарушить выбор игрока.
 */
export function playLeitmotifMoment(): void {
  const now = Date.now();
  if (now - lastMoment < MOMENT_GAP_MS) {
    return;
  }
  if (MUSIC_AVAILABLE && soundLevel('music') > 0 && gestured && engine) {
    lastMoment = now;
    engine.queueMotif();
    return;
  }
  if (soundLevel('sfx') === 0) {
    return;
  }
  const out = audioOutput();
  if (!out) {
    return;
  }
  lastMoment = now;
  const { ctx, sfx } = out;
  const start = ctx.currentTime + 0.05;
  const scale = LEITMOTIF.scale;
  for (const note of LEITMOTIF.notes) {
    const octave = Math.floor(note.degree / scale.length);
    const step = ((note.degree % scale.length) + scale.length) % scale.length;
    const midi = MOMENT_TONIC + scale[step] + 12 * octave;
    const time = start + note.start * MOMENT_STEP;
    const end = time + Math.max(0.15, note.length * MOMENT_STEP * 0.95);
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(0.16, time + 0.05);
    gain.gain.setValueAtTime(0.13, Math.max(time + 0.05, end - 0.1));
    gain.gain.linearRampToValueAtTime(0, end + 0.3);
    const filter = ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.setValueAtTime(900, time);
    filter.frequency.linearRampToValueAtTime(2400, time + 0.08);
    filter.connect(gain);
    gain.connect(sfx);
    for (const detune of [-5, 5]) {
      const osc = ctx.createOscillator();
      osc.type = 'sawtooth';
      osc.frequency.value = 440 * Math.pow(2, (midi - 69) / 12);
      osc.detune.value = detune;
      osc.connect(filter);
      osc.start(time);
      osc.stop(end + 0.35);
    }
  }
}
