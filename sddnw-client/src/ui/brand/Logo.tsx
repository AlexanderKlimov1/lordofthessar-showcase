import { useId } from 'react';
import { useT } from '../../i18n';
import { LEITMOTIF } from '../../audio/leitmotif';
import { reducedMotion } from '../motion';

/**
 * Знак игры и надпись LORD OF THESSAR / ВЛАДЫКА ТЕССАРА.
 *
 * **Знак — Звездоплаватель** (05.10.2026, решение хозяина проекта): фигура в скафандре, в
 * профиль, держит на вытянутой перчатке маленькую спиральную галактику. Ядро галактики —
 * звезда Тессар, особая звезда партии, которую держат, чтобы победить (имя игры называет её
 * цель); в рукавах — три цветные звезды, оранжевая, красная и жёлтая, те же, что стояли на
 * ободе прежнего знака. Лица за стеклом шлема не видно — в нём отражается галактика: владыка —
 * это сам игрок. Скафандр, а не плащ: плащ с капюшоном читался фэнтези, а игра — о космосе.
 * Фигура тёмная с обводкой светом лампы (`lamp`), галактика светится и подсвечивает руку —
 * та же пара «холодная ночь и тёплый свет», на которой стоит вся атмосфера (`index.css`).
 *
 * **Запасной знак — «Венец»** (вариант D1c: корона, над ней Тессар, на ободе три звезды).
 * Хозяин держит его про запас, поэтому рисунок не удалён, а включается одной строкой —
 * {@link MARK}. Промежуточный вариант — та же поза в плаще с капюшоном — отвергнут: плащ
 * читался фэнтези; наброски лежат в `atmosphere-review/thessar-cloak/` (вне git).
 *
 * **Связь с музыкой — ритмом:** в заставке три цветные звезды загораются на первых трёх
 * нотах лейтмотива, а Тессар — на его вершине (`leitmotif.ts`). Там же галактика медленно
 * поворачивается в ладони — оборот за минуту: движение, которое замечаешь, только
 * задержав взгляд. Кому движение мешает, тому она стоит.
 *
 * Рисуется своим SVG, а не картинкой: знак собирается в заставке по частям. **Малый знак**
 * (меньше 40 точек по высоте) — без складок и с линиями толще, иначе в мелком размере
 * детали сливаются. Надпись — кривые из макета (`public/brand/`): шрифты Cinzel и
 * Cormorant SC ради одной строки не грузятся.
 */

/** Какой знак стоит в игре: Звездоплаватель или запасной Венец. */
const MARK: 'astronaut' | 'crown' = 'astronaut';

export interface LogoProps {
  /** `stack` — знак над надписью (заставка), `inline` — рядом (шапки экранов), `mark` — один знак. */
  variant?: 'stack' | 'inline' | 'mark';
  /** Высота знака в rem — следует за размером текста игрока. */
  height?: number;
  /** Заставка: знак собирается по частям ритмом темы; восьмая темы длится `eighth` секунд. */
  animated?: boolean;
  eighth?: number;
  className?: string;
  /** Какой знак рисовать; без него — знак игры ({@link MARK}). Экран входа ставит Венец. */
  mark?: 'astronaut' | 'crown';
}

const LAMP = 'rgb(var(--lamp))';
const BODY = '#101a33';
const VOID = '#05070f';
const CORE = '#fff4e0';
/** Три цветные звезды: оранжевая, красная, насыщенная жёлтая. */
const STAR_COLORS = ['rgb(255,150,60)', 'rgb(235,80,70)', 'rgb(255,206,0)'];
/** Сколько секунд прорисовывается фигура, прежде чем зазвучит тема. */
const DRAW = 1.2;

/** Четырёхлучевая звезда: лучи вверх, вниз и в стороны, перетяжка `k` у сердцевины. */
function star4(cx: number, cy: number, up: number, down: number, side: number, k: number): string {
  return `M${cx},${cy - up} Q${cx + k},${cy - k} ${cx + side},${cy} Q${cx + k},${cy + k} ${cx},${cy + down}`
    + ` Q${cx - k},${cy + k} ${cx - side},${cy} Q${cx - k},${cy - k} ${cx},${cy - up}Z`;
}

/** Когда что загорается в заставке: три звезды — на первых трёх нотах темы, Тессар — на вершине. */
function timeline(eighth: number) {
  const notes = LEITMOTIF.notes;
  const peak = notes.reduce((best, note) => (note.degree > best.degree ? note : best), notes[0]);
  return {
    stars: notes.slice(0, 3).map((note) => DRAW + note.start * eighth),
    peak: DRAW + peak.start * eighth,
  };
}

interface MarkProps {
  small: boolean;
  animated: boolean;
  glow: string;
  times: ReturnType<typeof timeline>;
}

const delayOf = (animated: boolean, seconds: number) => (animated ? { animationDelay: `${seconds}s` } : undefined);

/**
 * Подробности фигуры (`Detail`: путь, толщина, прозрачность) — по слоям, в порядке рисования.
 * В малом знаке их нет вовсе: в сорока точках они сливаются в шум.
 */
interface Detail { d: string; w: number; o: number }
/** Искры над ладонью: пыль, которую галактика роняет вокруг себя. */
const SPARKS = [[96, 50, 0.7], [113.5, 50, 0.6], [95, 33, 0.6], [115, 31, 0.7], [109, 25, 0.5], [90, 41, 0.5]];

function details(list: Detail[], animated: boolean) {
  return list.map((item) => (
    <path key={item.d} d={item.d} fill="none" stroke={LAMP} strokeWidth={item.w} strokeOpacity={item.o}
      strokeLinecap="round" strokeLinejoin="round" className={animated ? 'sddnw-fade-in' : undefined} />
  ));
}

/** Галактика над ладонью: центр, радиус и наклон диска. */
const GALAXY = { cx: 104, cy: 40, r: 19.5, tilt: -16 };

/*
  Звездоплаватель — профиль вправо, рука вытянута, галактика над перчаткой. Поле 128×128,
  детали — `Detail`.
*/
const SUIT = {
  /** Ранец жизнеобеспечения за спиной. */
  pack: 'M29,47 Q29,44 32,44 L41,44 Q44,44 44,47 L44,80 Q44,83 41,83 L32,83 Q29,83 29,80Z',
  /** Корпус: грудь, живот, таз. */
  torso: 'M43,50 C50,46.5 62,46 69,49.5 C72,57 73,67 71,80 L45,82 C42.5,70 41.5,59 43,50Z',
  backLeg: 'M46,82 L44.5,100 L44,112 L53,112.5 L54.5,100 L57,82Z',
  frontLeg: 'M58,82 L62,99 L64.5,111 L73.5,110.5 L71.5,98 L70.5,81Z',
  /** Ботинки скафандра: манжета на щиколотке, голенище, стопа с носком вперёд, толстая подошва. */
  backBoot: 'M43,104.5 L54.8,104.5 L55.4,109.6 C58.6,110 61.8,111 63.4,113 C64,113.8 64.2,114.8 64,115.6 L41,115.6 C40.6,111.8 41.4,107.8 43,104.5Z',
  backSole: 'M40.6,115.4 L64.4,115.4 Q65,115.4 64.8,116.4 L64.2,119.6 L41.4,119.6 Q40.6,119.6 40.6,118.6Z',
  frontBoot: 'M64,103 L75.8,103 L76.4,108.1 C79.6,108.5 82.8,109.5 84.4,111.5 C85,112.3 85.2,113.3 85,114.1 L62,114.1 C61.6,110.3 62.4,106.3 64,103Z',
  frontSole: 'M61.6,113.9 L85.4,113.9 Q86,113.9 85.8,114.9 L85.2,118.1 L62.4,118.1 Q61.6,118.1 61.6,117.1Z',
  /** Шейное кольцо, на котором сидит шлем. */
  collar: 'M45,47.5 Q57,52.5 68.5,47 L68,50.8 Q57,56 45.5,51.5Z',
  helmet: 'M57.5,15.5 C67,15.5 74,22.5 74,31.5 C74,40.5 67,47.5 57.5,47.5 C48,47.5 41,40.5 41,31.5 C41,22.5 48,15.5 57.5,15.5Z',
  /** Стекло шлема — тёмное, в профиль смотрит вперёд. */
  visor: 'M60.5,20.5 C68,20.5 72.5,26 72.5,32.5 C72.5,38.5 68.5,42.5 63,42.5 C60,42.5 58.5,40 58.5,36 L58.5,25 C58.5,22.5 59,20.5 60.5,20.5Z',
  arm: 'M59,51 C67,49 77,52 85,54 L93,54.6 L94.6,66.4 L86,66.6 C78,66.6 68,64.5 61.5,62.5 C57.5,59.5 57,54 59,51Z',
  glove: 'M93.5,55.6 Q99,53 103.5,55.4 L108,54.4 Q111,55 109.6,57.8 L106.4,59.4 Q110.4,60.6 108.8,63.8 Q102.4,67.6 94.4,66.2Z',
};
/** Ботинок: кольцо манжеты, накладка на носке, ремень подъёма, протектор подошвы. */
const BACK_BOOT_DETAILS: Detail[] = [
  { d: 'M43.2,107 L55,107', w: 1.6, o: 0.7 },
  { d: 'M58.5,110.3 C60.5,111.4 61.4,113.2 61.4,115.4', w: 1, o: 0.55 },
  { d: 'M50.6,108.4 L55.2,112.6', w: 1.2, o: 0.5 },
  { d: 'M44,119.6 L44,121 M48,119.6 L48,121 M52,119.6 L52,121 M56,119.6 L56,121 M60,119.6 L60,121', w: 1, o: 0.45 },
];
const FRONT_BOOT_DETAILS: Detail[] = [
  { d: 'M64.2,105.5 L76,105.5', w: 1.6, o: 0.7 },
  { d: 'M79.5,108.8 C81.5,109.9 82.4,111.7 82.4,113.9', w: 1, o: 0.55 },
  { d: 'M71.6,106.9 L76.2,111.1', w: 1.2, o: 0.5 },
  { d: 'M65,118.1 L65,119.5 M69,118.1 L69,119.5 M73,118.1 L73,119.5 M77,118.1 L77,119.5 M81,118.1 L81,119.5', w: 1, o: 0.45 },
];
/** Корпус: ранец с рёбрами и вентилем, нагрудная панель, пояс с пряжкой, швы, колени, антенна. */
const BODY_DETAILS: Detail[] = [
  { d: 'M32.5,52 L40.5,52 M32.5,58 L40.5,58 M32.5,64 L40.5,64', w: 1, o: 0.45 },
  { d: 'M36.5,72 m-2.6,0 a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0', w: 1.1, o: 0.6 },
  { d: 'M57,56 L66.5,55.5 L67,64 L57.5,64.5Z', w: 1.1, o: 0.65 },
  { d: 'M44.5,77.5 C53,79 62,79 71.2,77', w: 2.4, o: 0.75 },
  { d: 'M55.5,76.8 L59.5,76.8 L59.5,80.4 L55.5,80.4Z', w: 1, o: 0.7 },
  { d: 'M47,56 C46,63 46,70 47,76', w: 1, o: 0.35 },
  { d: 'M44.5,97.5 L55,98 M62.2,96.5 L71.8,95.5', w: 1.3, o: 0.55 },
  { d: 'M50,85 L49.4,95 M65.5,85 L67,94', w: 0.9, o: 0.3 },
  { d: 'M48.5,19.5 L45,11.5', w: 1.3, o: 0.7 },
];
/** Рукав: гофры плеча и локтя, кольцо перчатки. */
const ARM_DETAILS: Detail[] = [
  { d: 'M64,51 C65.5,55 65.5,59 64,63.5 M67,51.2 C68.5,55.2 68.5,59.6 67,64', w: 1, o: 0.5 },
  { d: 'M78,52.8 C79.6,56.8 79.6,61.4 78,65.8 M81,53.4 C82.6,57.4 82.6,61.8 81,66.2', w: 1, o: 0.5 },
  { d: 'M90,54.4 L91.4,66.5', w: 1.6, o: 0.7 },
];
/** Перчатка: большой палец и пальцы. */
const GLOVE_DETAILS: Detail[] = [
  { d: 'M97,55.2 C97.8,52.4 100.4,51.4 102.2,52.6', w: 1.4, o: 0.9 },
  { d: 'M103.6,56.4 L108.2,55.2 M104.2,58.6 L109,57.8 M104,60.8 L108.6,61.4', w: 0.8, o: 0.65 },
];

function AstronautMark({ small, animated, glow, times }: MarkProps) {
  const line = small ? 3.4 : 2.4;
  const part = (d: string, draw = false) => (
    <path d={d} fill={BODY} stroke={LAMP} strokeWidth={line} strokeLinejoin="round"
      pathLength={draw ? 1 : undefined} className={animated ? (draw ? 'sddnw-logo-line' : 'sddnw-fade-in') : undefined} />
  );
  return (
    <g transform="translate(-12 6)">
      {part(SUIT.pack)}
      {part(SUIT.backLeg)}
      {part(SUIT.backBoot)}
      {part(SUIT.backSole)}
      {small ? null : details(BACK_BOOT_DETAILS, animated)}
      {part(SUIT.torso, true)}
      {part(SUIT.frontLeg)}
      {part(SUIT.frontBoot)}
      {part(SUIT.frontSole)}
      {small ? null : details(FRONT_BOOT_DETAILS, animated)}
      {part(SUIT.collar)}
      {part(SUIT.helmet, true)}
      <path d={SUIT.visor} fill={VOID} stroke={LAMP} strokeWidth={small ? 2 : 1.2} strokeOpacity=".8" />
      {small ? null : (
        <>
          {/* В стекле — отражение галактики: тёплый блик и маленький Тессар. */}
          <path d="M63,24 C67.5,24.5 70.5,27.5 71,31.5" fill="none" stroke={LAMP} strokeWidth="1.4" strokeOpacity=".55" strokeLinecap="round" />
          <path d={star4(67.5, 31, 2.6, 2.6, 2.6, 0.5)} fill={LAMP} fillOpacity=".85"
            className={animated ? 'sddnw-logo-peak' : undefined} style={delayOf(animated, times.peak)} />
          <circle cx="45.5" cy="11" r="1.4" fill={LAMP} />
          {details(BODY_DETAILS, animated)}
        </>
      )}
      {part(SUIT.arm)}
      {small ? null : details(ARM_DETAILS, animated)}
      <path d={SUIT.glove} fill={BODY} stroke={LAMP} strokeWidth={small ? 2.6 : 1.8} strokeLinejoin="round" />
      {small ? null : details(GLOVE_DETAILS, animated)}
      <HeldGalaxy small={small} animated={animated} glow={glow} times={times} />
    </g>
  );
}

/**
 * Галактика над ладонью — общая у всех фигур: свечение, наклонённый диск с двумя рукавами,
 * три цветные звезды, искры вокруг и Тессар в ядре. В заставке диск медленно вращается.
 */
function HeldGalaxy({ small, animated, glow, times }: MarkProps) {
  const { cx, cy, r, tilt } = GALAXY;
  const k = r / 14;
  const p = (x: number, y: number) => `${cx + x * k},${cy + y * k}`;
  const arm = (width: number, opacity: number, d: string) => (
    <path d={d} fill="none" stroke={LAMP} strokeWidth={width * k} strokeOpacity={opacity} strokeLinecap="round" />
  );
  const spin = animated && !reducedMotion();
  return (
    <>
      <defs>
        <radialGradient id={`${glow}-g`} cx={cx} cy={cy} r={r * 1.7} gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor={LAMP} stopOpacity=".55" />
          <stop offset=".45" stopColor={LAMP} stopOpacity=".16" />
          <stop offset="1" stopColor={LAMP} stopOpacity="0" />
        </radialGradient>
      </defs>
      {small ? null : SPARKS.map(([x, y, radius]) => (
        <circle key={`${x}-${y}`} cx={x} cy={y} r={radius} fill={LAMP} fillOpacity=".75"
          className={animated ? 'sddnw-logo-star' : undefined} style={delayOf(animated, times.peak + 0.3)} />
      ))}
      <g className={animated ? 'sddnw-logo-peak' : undefined} style={delayOf(animated, times.peak - 0.6)}>
        <circle cx={cx} cy={cy} r={r * 1.7} fill={`url(#${glow}-g)`} />
      </g>
      {/* Диск наклонён и сплющен; вращается он в своей плоскости — до сплющивания. */}
      <g transform={`rotate(${tilt} ${cx} ${cy}) translate(${cx} ${cy}) scale(1 .58) translate(${-cx} ${-cy})`}>
        <g>
          {spin ? (
            <animateTransform attributeName="transform" type="rotate" from={`0 ${cx} ${cy}`} to={`-360 ${cx} ${cy}`}
              dur="60s" repeatCount="indefinite" />
          ) : null}
          <g className={animated ? 'sddnw-fade-in' : undefined} style={delayOf(animated, DRAW * 0.5)}>
            {arm(2.4, 1, `M${p(0, 0)} C${p(6, -4)} ${p(12, 0)} ${p(10, 6)} C${p(8, 11)} ${p(0, 12)} ${p(-6, 9)}`)}
            {arm(2.4, 1, `M${p(0, 0)} C${p(-6, 4)} ${p(-12, 0)} ${p(-10, -6)} C${p(-8, -11)} ${p(0, -12)} ${p(6, -9)}`)}
            {small ? null : arm(1.3, 0.6, `M${p(-6, 9)} C${p(-11, 7)} ${p(-14, 2)} ${p(-13, -2)}`)}
            {small ? null : arm(1.3, 0.6, `M${p(6, -9)} C${p(11, -7)} ${p(14, -2)} ${p(13, 2)}`)}
          </g>
          {[[10.5, 4], [-7, -10], [-9, 6]].map(([x, y], index) => (
            <circle key={index} cx={cx + x * k} cy={cy + y * k} r={1.7 * k} fill={STAR_COLORS[index]}
              className={animated ? 'sddnw-logo-star' : undefined} style={delayOf(animated, times.stars[index])} />
          ))}
        </g>
      </g>
      <g className={animated ? 'sddnw-logo-peak' : undefined} style={delayOf(animated, times.peak)}>
        <path d={star4(cx, cy, 5 * k, 5 * k, 5 * k, 0.9 * k)} fill={LAMP} />
        <circle cx={cx} cy={cy} r={1.5 * k} fill={CORE} />
      </g>
    </>
  );
}

/*
  Запасной Венец — поле 64×64 (`atmosphere-review/thessar-brand/thessar-mark.svg`), вписанное в
  128 удвоением.
*/
function CrownMark({ small, animated, glow, times }: MarkProps) {
  return (
    <g transform="scale(2)">
      <defs>
        <radialGradient id={`${glow}-t`} cx="32" cy="17" r="20" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor={LAMP} stopOpacity=".5" />
          <stop offset="1" stopColor={LAMP} stopOpacity="0" />
        </radialGradient>
      </defs>
      <path d="M12,50 L9,26 L22,38 L32,31 L42,38 L55,26 L52,50Z" fill={BODY} stroke={LAMP}
        strokeWidth={small ? 3.6 : 2.2} strokeLinejoin="round" pathLength={1}
        className={animated ? 'sddnw-logo-line' : undefined} />
      <rect x="11" y="49" width="42" height={small ? 6 : 5} rx="1.5" fill={LAMP}
        className={animated ? 'sddnw-fade-in' : undefined} />
      {small ? null : [22, 32, 42].map((x, index) => (
        <path key={x} d={star4(x, 43, 4, 4, 3.6, 0.75)} fill={STAR_COLORS[index]}
          className={animated ? 'sddnw-logo-star' : undefined} style={delayOf(animated, times.stars[index])} />
      ))}
      <g className={animated ? 'sddnw-logo-peak' : undefined} style={delayOf(animated, times.peak)}>
        {small ? null : <circle cx="32" cy="17" r="20" fill={`url(#${glow}-t)`} />}
        <path d={small ? star4(32, 17, 16, 13, 14, 3) : star4(32, 17, 15, 12, 12, 1.6)} fill={LAMP} />
        <circle cx="32" cy="17" r={small ? 3 : 2.3} fill={CORE} />
      </g>
    </g>
  );
}

export function Logo({ variant = 'inline', height = 2.25, animated = false, eighth = 0.2, className = '', mark: kind = MARK }: LogoProps) {
  const { t, locale } = useT();
  const glow = useId().replace(/:/g, '');
  const props: MarkProps = { small: height < 2.5, animated, glow, times: timeline(eighth) };

  const mark = (
    <svg
      viewBox="0 0 128 128"
      style={{ height: `${height}rem`, width: `${height}rem` }}
      className="shrink-0 overflow-visible"
      role="img"
      aria-label={t('app.name')}
    >
      {kind === 'astronaut' ? <AstronautMark {...props} /> : <CrownMark {...props} />}
    </svg>
  );

  if (variant === 'mark') {
    return <span className={className}>{mark}</span>;
  }
  // Надпись — кривые макета: «LORD OF» тише, «THESSAR» ярче; по-русски своя пара строк.
  const word = (
    <img
      src={`/brand/wordmark-${locale}.svg`}
      alt={t('app.name')}
      className={`block w-auto ${animated ? 'sddnw-logo-word' : ''}`}
      style={{ height: `${height * (variant === 'stack' ? 0.36 : 0.72)}rem`, ...delayOf(animated, props.times.peak + 0.6) }}
    />
  );
  return variant === 'stack' ? (
    <span className={`inline-flex flex-col items-center gap-[0.8em] ${className}`}>
      {mark}
      {word}
    </span>
  ) : (
    <span className={`inline-flex items-center gap-3 ${className}`}>
      {mark}
      {word}
    </span>
  );
}

/** Сколько секунд длится сборка знака в заставке — чтобы следом вывести выбор. */
export function logoAssemblySeconds(eighth: number): number {
  return timeline(eighth).peak + 0.6 + 1.6;
}
