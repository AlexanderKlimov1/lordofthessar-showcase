/**
 * Громкость музыки и звуковых эффектов — выбор игрока.
 *
 * Правда о громкости лежит в УЧЁТНОЙ ЗАПИСИ (`account.musicVolume`, `account.sfxVolume`),
 * как язык и размер текста: настройка, лежащая только в браузере, теряется на второй машине,
 * и выключенный однажды звук заиграл бы снова. `localStorage` здесь — быстрый ответ до входа
 * и для того, кто не вошёл; при входе решает запись (`applyAccountSound` в `state/session.ts`).
 *
 * **По умолчанию звук ВЫКЛЮЧЕН** — и музыка, и эффекты (решение хозяина проекта): звук,
 * заигравший сам при первом входе, раздражает сильнее, чем тишина, которую надо включить.
 * Поэтому пустая запись читается как ноль, а не как «тихо».
 *
 * Здесь только числа и подписка: сам звук заводят `music.ts` и `battleSfx.ts`, а громкость
 * шин ставит `audioOutput.ts`.
 */

/**
 * Звучит ли в игре музыка вообще — 07.10.2026, решение хозяина проекта: ПОКА НЕТ. Звучание
 * фоновой музыки ещё не то, и до тех пор игра звучит одними эффектами: значок звука на сценах
 * включает и выключает только их, заставка «Начать ♪» включает их же, а переключатель музыки
 * в настройках виден, но погашен с объяснением. Мастерская звука (только администратору)
 * музыку играет по-прежнему — там её и доводят. Вернуть музыку — одна строка.
 */
export const MUSIC_AVAILABLE = true;

/**
 * Громкость — ПРОЦЕНТ 0…100 (09.10.2026, решение хозяина проекта: ползунки под меню «Игра»
 * вместо трёх ступеней). Ноль — выключено. Тот же предел на сервере (`AccountService.SOUND_MAX`).
 */
export const SOUND_MAX = 100;
/** Что включить, если прежней громкости нет: середина ползунка. */
export const SOUND_DEFAULT = 50;
export type SoundLevel = number;
export type SoundChannel = 'music' | 'sfx';

/** Ключи процентов — новые: под прежними лежали ступени 0…3, и «3» прочлось бы как 3 %. */
const KEYS: Record<SoundChannel, string> = {
  music: 'sddnw.settings.musicPercent',
  sfx: 'sddnw.settings.sfxPercent',
};
const OLD_KEYS: Record<SoundChannel, string> = {
  music: 'sddnw.settings.music',
  sfx: 'sddnw.settings.sfx',
};

/** Громкость из чего угодно: из хранилища, из записи. Неизвестное и пустое — выключено. */
export const toSoundLevel = (value: unknown): SoundLevel => {
  const number = Math.round(Number(value));
  return Number.isFinite(number) ? Math.min(SOUND_MAX, Math.max(0, number)) : 0;
};

const read = (channel: SoundChannel): SoundLevel => {
  try {
    const saved = window.localStorage.getItem(KEYS[channel]);
    if (saved !== null) {
      return toSoundLevel(saved);
    }
    // Ступень прежнего вида (0…3) переводится в процент тем же правилом, что и запись на сервере
    // (миграция 139): 1 → 33, 2 → 67, 3 → 100.
    const old = Number(window.localStorage.getItem(OLD_KEYS[channel]));
    return old >= 1 && old <= 3 ? Math.round((old * SOUND_MAX) / 3) : 0;
  } catch {
    return 0;
  }
};

const levels: Record<SoundChannel, SoundLevel> = { music: read('music'), sfx: read('sfx') };
const listeners = new Set<() => void>();

export const soundLevel = (channel: SoundChannel): SoundLevel => levels[channel];

/**
 * Громкость как множитель: квадрат, потому что слух меряет громкость не линейно, и при линейной
 * шкале треть ползунка звучала бы почти так же, как весь.
 */
export const soundGain = (channel: SoundChannel): number => (levels[channel] / SOUND_MAX) ** 2;

/**
 * Меняет ступень в этом браузере. В запись её отправляет `rememberSound` — отдельно, потому
 * что применение записи при входе тоже идёт сюда, и слать её обратно на сервер незачем.
 */
export function setSoundLevel(channel: SoundChannel, value: SoundLevel): void {
  if (levels[channel] === value) {
    return;
  }
  levels[channel] = value;
  try {
    window.localStorage.setItem(KEYS[channel], String(value));
  } catch {
    // Хранилище закрыто (приватное окно) — настройка проживёт до перезагрузки.
  }
  listeners.forEach((listener) => listener());
}

/**
 * Звук, выбранный игроком в заставке вечера ДО входа (`EveningIntro`). Запись при входе
 * решает за браузер, и пустая запись выключает звук (`applyAccountSound`) — иначе за чужой
 * машиной заиграла бы чужая музыка. Но выбор, сделанный минуту назад на этой самой странице,
 * — не чужая настройка, а ответ того, кто сейчас входит: пустая запись его не гасит, а
 * получает.
 */
let chosenThisVisit = false;

export const chooseSoundThisVisit = (): void => {
  chosenThisVisit = true;
};

export const soundChosenThisVisit = (): boolean => chosenThisVisit;

export function onSoundChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
