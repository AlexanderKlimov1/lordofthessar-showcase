import { useEffect, useMemo, useState } from 'react';
import { useT } from '../../i18n';
import { MUSIC_AVAILABLE, SOUND_DEFAULT, chooseSoundThisVisit, setSoundLevel, soundLevel } from '../../audio/soundSettings';
import { Logo, logoAssemblySeconds } from './Logo';

/**
 * Заставка — первое, что видит игрок (атмосфера игры, 04.10.2026).
 *
 * **Атмосферу она создаёт косвенно, а не называет** (05.10.2026, решение хозяина проекта):
 * прежде под знаком стояло приглашение «Проведи со мной вдумчивый спокойный вечер», и хозяин
 * его снял — призыв к настроению его не создаёт, а спокойствие, о котором говорят прямо,
 * читается рекламой. Настроение держат темп (ничего не торопится и не пружинит), тёмное небо
 * с тёплым светом снизу, знак, собирающийся ритмом темы, и музыка. Слов на сцене нет, кроме
 * двух кнопок.
 *
 * Порядок сцены — тишина, звёзды, знак, выбор:
 *
 * * на тёмном небе прорисовывается звездоплаватель с галактикой на ладони, её три звезды
 *   загораются РИТМОМ ЛЕЙТМОТИВА — той самой мелодии, которую игрок услышит следом
 *   (`Logo`), — а Тессар в ядре вспыхивает тёплым светом лампы последним, на вершине темы;
 *   дальше галактика медленно поворачивается, пока игрок не выберет;
 * * нажатие «начать» и есть первое нажатие, которое браузер требует перед звуком. Поэтому
 *   у кнопки два ответа — «начать ♪» с музыкой и «без звука». Звук по умолчанию выключен
 *   (решение хозяина проекта: заигравший сам раздражает), и здесь он тоже не включается сам —
 *   его выбирают, ровно один раз и прямо там, где игра начинается.
 *
 * **Одна заставка на вечер.** Заставка показывается, если с прошлой прошло больше
 * `EVENING_HOURS` часов: зовут один раз, а не на каждую перезагрузку страницы — вернувшийся
 * через десять минут к своему ходу второй раз заставки не ждёт (трение входа,
 * `field-backlog.html`). Любое нажатие во время сборки знака показывает его целиком сразу.
 */

/**
 * **Заставка пока убрана** (07.10.2026, решение хозяина проекта): игра открывается сразу главным
 * меню (или входом), без промежуточного экрана. Одна строка вернёт её — как `MUSIC_AVAILABLE`.
 * Звук от этого не страдает: он и так выключен по умолчанию, а включают его значком на сцене.
 */
export const INTRO_AVAILABLE = false;

const STORAGE_KEY = 'sddnw.evening';
const EVENING_HOURS = 6;
/** Восьмая темы в заставке — медленнее, чем в музыке меню: звёзды загораются, а не мелькают. */
const EIGHTH = 0.22;
const FADE_OUT_MS = 1200;

/** Пора ли звать на вечер: в этом браузере давно не звали. */
export function inviteTonight(): boolean {
  if (!INTRO_AVAILABLE) {
    return false;
  }
  try {
    const last = Number(window.localStorage.getItem(STORAGE_KEY));
    return !Number.isFinite(last) || Date.now() - last > EVENING_HOURS * 3600 * 1000;
  } catch {
    return true;
  }
}

function rememberEvening(): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, String(Date.now()));
  } catch {
    // Хранилище закрыто — пригласим ещё раз, беды в этом нет.
  }
}

/**
 * Пыль дальних звёзд — считанным жребием от номера, а не `Math.random`: сцена
 * перерисовывается, и перетряхнутые звёзды мигали бы.
 */
function dust(count: number) {
  return Array.from({ length: count }, (_, index) => {
    const a = Math.sin(index * 12.9898) * 43758.5453;
    const b = Math.sin(index * 78.233) * 12345.6789;
    const c = Math.sin(index * 37.719) * 9631.217;
    return {
      x: (a - Math.floor(a)) * 100,
      y: (b - Math.floor(b)) * 100,
      size: 1 + Math.floor((c - Math.floor(c)) * 3) * 0.5,
      opacity: 0.15 + (c - Math.floor(c)) * 0.45,
    };
  });
}

export function EveningIntro({ onDone }: { onDone: () => void }) {
  const { t } = useT();
  const [assembled, setAssembled] = useState(false);
  const [skipped, setSkipped] = useState(false);
  const [leaving, setLeaving] = useState(false);
  // Пока музыки в игре нет (`MUSIC_AVAILABLE`), «Начать ♪» включает звуковые эффекты.
  const channel = MUSIC_AVAILABLE ? 'music' : 'sfx';
  const silent = soundLevel(channel) === 0;
  const stars = useMemo(() => dust(140), []);

  useEffect(() => {
    const timer = window.setTimeout(() => setAssembled(true), logoAssemblySeconds(EIGHTH) * 1000);
    return () => window.clearTimeout(timer);
  }, []);

  const begin = (withMusic: boolean) => {
    if (leaving) {
      return;
    }
    if (withMusic && silent) {
      setSoundLevel(channel, SOUND_DEFAULT);
      chooseSoundThisVisit();
    }
    rememberEvening();
    setLeaving(true);
    window.setTimeout(onDone, FADE_OUT_MS);
  };

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!assembled) {
        setSkipped(true);
        setAssembled(true);
        return;
      }
      if (event.key === 'Enter') {
        begin(true);
      } else if (event.key === 'Escape') {
        begin(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  return (
    <div
      className="relative flex h-full w-full flex-col items-center justify-center overflow-hidden px-4 transition-opacity ease-in"
      style={{
        opacity: leaving ? 0 : 1,
        transitionDuration: `${FADE_OUT_MS}ms`,
        // Небо ночное, а снизу — едва заметное тепло: свет лампы на краю поля зрения.
        background: 'radial-gradient(ellipse 80% 45% at 50% 115%, rgb(var(--lamp) / 0.09), transparent 70%), #05070f',
      }}
      onPointerDown={() => {
        if (!assembled) {
          setSkipped(true);
          setAssembled(true);
        }
      }}
    >
      <div className="pointer-events-none absolute inset-0" aria-hidden>
        {stars.map((star, index) => (
          <span
            key={index}
            className="absolute rounded-full bg-ink-bright"
            style={{ left: `${star.x}%`, top: `${star.y}%`, width: star.size, height: star.size, opacity: star.opacity }}
          />
        ))}
      </div>

      <Logo key={skipped ? 'still' : 'moving'} variant="stack" height={6.5} animated={!skipped} eighth={EIGHTH} />

      <div className="mt-16 flex min-h-[9rem] flex-col items-center gap-8">
        {assembled ? (
          <>
            <div className="sddnw-fade-in flex flex-col items-center gap-3">
              <button
                type="button"
                autoFocus
                className="border border-lamp/60 px-8 py-2 text-15 tracking-[0.25em] text-lamp hover:bg-lamp/10"
                onClick={() => begin(true)}
              >
                {silent ? t('evening.beginWithMusic') : t('evening.begin')}
              </button>
              {silent ? (
                <button type="button" className="link-quiet text-13 text-ink-dim hover:text-ink" onClick={() => begin(false)}>
                  {t('evening.inSilence')}
                </button>
              ) : null}
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}
