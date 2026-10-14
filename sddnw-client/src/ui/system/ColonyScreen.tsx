import type { Colony, Planet } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { ColonyLeader } from './ColonyLeader';
import { growthK } from './colonyRules';
import { useColonyJobs } from './useColonyJobs';
import { ColonyBuild } from './ColonyBuild';
import { ColonyBuildings } from './ColonyBuildings';
import { ColonyJobs } from './ColonyJobs';
import { Colonist, ResourceIcon } from './colonyIcons';
import { useState, type ReactNode } from 'react';
import { gameApi } from '../../api/client';
import { isCapitol } from './buildingArt';
import { Figure } from '../Figure';
import { figureLabelClass, signTone, signed } from '../accent';
import { t, useT } from '../../i18n';
import { climateName, mineralsName, planetSizeName } from '../planetLabels';

/**
 * Экран управления колонией — п. 4.1 и п. 10, разметкой экрана колонии MOO II.
 *
 * Открывается двойным кликом по планете на экране звёздной системы и ложится поверх
 * него: закрыл колонию — вернулся к системе, как в оригинале.
 *
 * Экран занимает всё окно и разложен так:
 *
 *   ┌ Колония «имя» ─────────────────────────────────────────────┐
 *   │ Large · Terran · орбита 1 · владелец           доход +N gc │
 *   ├──────────────────────────────────────────┬─────────────────┤
 *   │ ЖИТЕЛИ       N из M жителей · прирост +K  │                 │
 *   │ мешок  фермеры                       еда │     стройка     │
 *   │ молот  рабочие              производство │    (во всю      │
 *   │ микро  учёные                      наука │     высоту)     │
 *   ├──────────────────────────────────────────┤                 │
 *   │  постройки колонии (поверхность планеты) │                 │
 *   │                                            закрыть (Esc)   │
 *   └────────────────────────────────────────────────────────────┘
 *
 * Отдельной панели выработки нет: каждая величина стоит там, где её делают. Еда,
 * производство и наука — в конце строки тех жителей, что их дают; население с приростом —
 * в заголовке жителей; доход — при параметрах колонии в шапке. Так число и его причина
 * оказываются в одной строке, и глазами не приходится ходить между двумя панелями.
 *
 * Списка планет системы здесь тоже нет: соседние планеты выбираются на экране системы,
 * а экран колонии остаётся про одну колонию.
 *
 * Жители стоят сразу под шапкой: распределение меняют первым делом, и от него зависит
 * всё остальное. Под ними самая большая область — поверхность планеты с застройкой,
 * справа во всю высоту стройка.
 *
 * Своей логики у экрана нет: он раскладывает сегменты и держит распределение жителей,
 * от которого зависят сразу две панели. Считает и меняет всё сервер.
 */
export function ColonyScreen() {
  const map = useGameStore((state) => state.map);
  const openedColonyId = useGameStore((state) => state.openedColonyId);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);

  // Экран лежит поверх того, откуда его открыли, поэтому Esc забирает себе — см. useModalEscape.
  useModalEscape(openedColonyId !== null, () => setOpenedColony(null));

  /*
    Планета ищется по всей карте, а не в открытой системе: колонию открывают и
    со списка колоний империи, где никакой системы не открыто. И достаётся каждый раз
    заново: после действий игрока она заменяется в карте, и держать на неё ссылку нельзя.
  */
  const planet = openedColonyId
    ? (map?.systems
        .flatMap((system) => system.planets)
        .find((candidate) => candidate.id === openedColonyId) ?? null)
    : null;
  if (!planet) {
    return null;
  }

  return <ColonyLayout planet={planet} onOpen={setOpenedColony} />;
}

/**
 * Разметка экрана отделена от его открытия: распределение жителей считает хук, а хуки
 * нельзя звать после проверки «планета не найдена».
 */
function ColonyLayout({
  planet,
  onOpen,
}: {
  planet: Planet;
  onOpen: (planetId: string | null) => void;
}) {
  const map = useGameStore((state) => state.map);
  const game = useGameStore((state) => state.game);
  const { jobs, working, lift, move, error, own } = useColonyJobs(planet);
  // Система колонии: губернатор служит ей, а не планете. Ищется по карте — планета своего
  // родителя не называет.
  const system = (map?.systems ?? []).find((one) =>
    one.planets.some((candidate) => candidate.id === planet.id)) ?? null;
  const turn = game?.turn ?? 1;
  useT();
  const colony = planet.colony;

  return (
    // Экран занимает окно целиком: в MOO II колония — полноэкранный вид, а не окошко.
    <div className="absolute inset-0 z-40 flex flex-col overflow-auto bg-space-950 p-4">
      <ColonyHeader planet={planet} colony={colony} own={own}
                    growth={colony ? growthK(planet, colony, working) : 0} />

      {/*
        Стройка — столбец во всю высоту справа: там же, где стояла выработка. Слева
        жители и под ними застройка во всю ширину.

        Строка растёт до своего содержимого, а лишнее уходит в прокрутку всего экрана:
        на невысоком окне крупные надписи не влезают, и лучше прокрутить экран, чем
        срезать нижнюю панель — со срезом стройка уезжала под нижнюю полосу.
      */}
      <div className="flex flex-1 gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <ColonyJobs planet={planet} colony={colony} jobs={jobs} working={working} onLift={lift} move={move}
                      error={error} own={own} />
          <ColonyBuildings planet={planet} colony={colony} own={own}
                           governor={own && system ? <ColonyLeader systemId={system.id} turn={turn} compact /> : null} />
        </div>

        <div className="flex w-[22rem] shrink-0 flex-col gap-3 2xl:w-[28rem]">
          <ColonyBuild planet={planet} own={own} />
        </div>
      </div>

      <div className="mt-3 flex justify-end border-t border-space-700 pt-2 text-24">
        <button type="button" className="link-quiet" onClick={() => onOpen(null)}>
          {t('common.closeEsc')}
        </button>
      </div>
    </div>
  );
}

/**
 * Шапка — редизайн колонии (07.10.2026, решение хозяина проекта): название, строка параметров мелко, а под ними ОДНА строка значков с
 * числами — еда, производство, наука, доход, население с вместимостью, мораль и загрязнение.
 * Пояснения — во всплывающих подсказках, а не словами в строке: прежде доход, содержание,
 * мораль и грязь стояли фразами и занимали две строки.
 *
 * Губернатор служит СИСТЕМЕ (п. 6) и помогает всем её колониям: его портрет стоит в правом
 * нижнем углу построек (`ColonyBuildings`, 09.10.2026), как в оригинале.
 */
function ColonyHeader({
  planet,
  colony,
  own,
  growth,
}: {
  planet: Planet;
  colony?: Colony;
  own: boolean;
  growth: number;
}) {
  /*
    Заголовок — одно название планеты (09.10.2026, решение хозяина проекта): слово «колония»,
    орбита и имя хозяина ничего не говорили — чужой колонией всё равно не управляют. Родной мир
    и столица — значками у названия, слово — в подсказке.
  */
  return (
    <div className="mb-3 border-b border-space-700 pb-2">
      <div className="min-w-0">
        <h2 className="flex items-center gap-2 text-22 uppercase tracking-[0.25em] text-ink-bright">
          {planet.name}
          {/* Население и предел планеты — у названия (12.10.2026, решение хозяина проекта): это
              первое, что спрашивают у колонии, и в ряду показателей его не находили. */}
          {colony ? (
            <span className="flex items-center gap-1 normal-case tracking-normal"
                  title={t('colony.stat.population', { growth: signed(growth) })}>
              <Colonist color="rgb(var(--lamp))" className="h-5 w-[14px]" />
              <Figure accent="lead">{planet.population}</Figure>
              <span className="text-ink-dim">/{colony.maxPopulation}</span>
            </span>
          ) : null}
          {planet.homeworld ? (
            <span className="text-accent" title={t('colony.homeworld.title')} aria-label={t('colony.homeworld.title')}>⌂</span>
          ) : null}
          {isCapitol(colony) ? (
            <span className="text-accent" title={t('colony.capitol.title')} aria-label={t('colony.capitol.title')}>♛</span>
          ) : null}
        </h2>
        <p className="text-16 text-ink-faint">
          {t('colony.paramsShort', { size: planetSizeName(planet.size, planet.sizeLabel), climate: climateName(planet.climate, planet.climateLabel), minerals: mineralsName(planet.minerals, planet.mineralsLabel) })}
        </p>
      </div>

      {colony ? (
        <div className={'mt-2 flex flex-wrap items-center gap-x-6 gap-y-1 ' + figureLabelClass('lead')}>
          <HeaderStat title={t('colony.stat.food')} icon={<ResourceIcon kind="grain" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead" tone={signTone(colony.foodBalance + colony.foodDelivered)}>
              {signed(colony.foodBalance + colony.foodDelivered)}
            </Figure>
          </HeaderStat>
          <HeaderStat title={t('colony.stat.production')} icon={<ResourceIcon kind="hammer" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead">{colony.production}</Figure>
          </HeaderStat>
          <HeaderStat title={t('colony.stat.research')} icon={<ResourceIcon kind="microscope" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead">{colony.research}</Figure>
          </HeaderStat>
          <HeaderStat
            title={t('colony.stat.income') + (colony.upkeep > 0 ? ' · ' + t('colony.stat.upkeep', { n: colony.upkeep }) : '')}
            icon={<ResourceIcon kind="coins" className="h-6 w-6 text-lamp" />}
          >
            <Figure accent="lead" tone={signTone(colony.income)}>{signed(colony.income)}</Figure>
          </HeaderStat>
          <MoraleFigure colony={colony} />
          {/*
            Загрязнение (п. 10) — вторая постоянная утечка колонии: платит она производством.
            Пока грязи нет, значок говорит, сколько промышленности планета ещё стерпит.
          */}
          <HeaderStat
            title={colony.pollutionFree
              ? t('colony.pollution.none')
              : colony.pollution > 0
                ? t('colony.stat.pollution', { n: colony.pollution })
                : t('colony.stat.tolerates', { n: colony.pollutionTolerance })}
            icon={<DropIcon warn={colony.pollution > 0} />}
          >
            {colony.pollutionFree
              ? <span className="text-ink-dim">—</span>
              : colony.pollution > 0
                ? <Figure accent="lead" tone="warn">−{colony.pollution}</Figure>
                : <span className="text-ink-dim">{colony.pollutionTolerance}</span>}
          </HeaderStat>
          {own ? <AutoJobsToggle planet={planet} colony={colony} /> : null}
        </div>
      ) : null}
    </div>
  );
}

/** Значок с числом в шапке колонии; что это за число — в подсказке. */
function HeaderStat({ title, icon, children }: { title: string; icon: ReactNode; children: ReactNode }) {
  return (
    <span className="flex items-center gap-1.5" title={title}>
      {icon}
      <span className="sr-only">{title}</span>
      {children}
    </span>
  );
}

/** Загрязнение — капля; есть уборка — тревожным цветом. */
function DropIcon({ warn }: { warn: boolean }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden className={`h-6 w-6 ${warn ? 'text-warn' : 'text-lamp'}`} fill="none"
         stroke="currentColor" strokeWidth={1.7} strokeLinejoin="round">
      <path d="M12 3.5C9 8 6.5 11 6.5 14.5a5.5 5.5 0 0 0 11 0C17.5 11 15 8 12 3.5Z" />
    </svg>
  );
}

/**
 * Мораль колонии — п. 10.1, рядом с доходом: это процент ко всему, что дают жители, и
 * доход в шапке уже посчитан с ним. Слагаемые — подсказкой: «−20 %» без причины читается
 * как поломка, а причина почти всегда одна и та же — нет казарм.
 */
function MoraleFigure({ colony }: { colony: Colony }) {
  if (colony.moraleIgnored) {
    return <span title={t('colony.morale.ignoredHint')}>{t('colony.morale.ignored')}</span>;
  }
  const lines = [
    t('colony.morale.hint'),
    ...colony.moraleParts.map((part) =>
      `${t(`colony.morale.source.${part.source}` as const)}: ${signed(part.percent)} %`),
  ];
  return (
    <span className="flex items-center gap-1.5" title={[t('colony.morale'), ...lines].join('\n')}>
      <svg viewBox="0 0 24 24" aria-hidden className="h-6 w-6 text-lamp" fill="none" stroke="currentColor"
           strokeWidth={1.7} strokeLinecap="round">
        <circle cx="12" cy="12" r="8.5" />
        <path d="M8.5 14.5c1 1.3 2.2 2 3.5 2s2.5-.7 3.5-2" />
        <path d="M9 9.5h.01M15 9.5h.01" strokeWidth={2.4} />
      </svg>
      <span className="sr-only">{t('colony.morale')}</span>
      <Figure accent="lead" tone={signTone(colony.moralePercent)}>
        {signed(colony.moralePercent)} %
      </Figure>
    </span>
  );
}

/**
 * «Жители сами» — field-backlog, пункт 91, значком в шапке колонии (09.10.2026, решение хозяина
 * проекта): колония держит фермеров ровно на прокорм, а остальных — в той доле рабочих и учёных,
 * что задал игрок. Расстановка руками остаётся: ею игрок и задаёт долю. Включённый значок горит
 * янтарём и вжат, как переключатели под стройкой.
 */
function AutoJobsToggle({ planet, colony }: { planet: Planet; colony: Colony }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const [error, setError] = useState<string | null>(null);
  const toggle = () => {
    if (!game || !credentials) {
      return;
    }
    setError(null);
    gameApi.setBuildMode(game.id, planet.id, credentials.accessToken, { autoJobs: !colony.autoJobs })
      .then(updatePlanet)
      .catch((failure: Error) => setError(failure.message));
  };
  const label = `${t('colony.jobs.auto')} — ${t('colony.jobs.auto.title')}${error ? ` ⚠ ${error}` : ''}`;
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={colony.autoJobs}
      onClick={toggle}
      className={'hit-24 flex h-9 w-9 items-center justify-center rounded border '
        + (error ? 'border-danger text-danger '
          : colony.autoJobs ? 'border-accent bg-space-700 text-accent shadow-inner '
            : 'border-space-600 bg-space-900 text-ink-soft hover:border-accent hover:text-accent ')}
    >
      {/* Житель в круговой стрелке: колония расставляет жителей сама. */}
      <svg viewBox="0 0 24 24" aria-hidden className="h-6 w-6" fill="none" stroke="currentColor"
           strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="8.5" r="2.2" fill="currentColor" stroke="none" />
        <path d="M9 16.5v-2.3a3 3 0 0 1 6 0v2.3Z" fill="currentColor" stroke="none" />
        <path d="M4.5 12a7.5 7.5 0 0 1 13-5.1" /><path d="M17.8 4.2v2.9h-2.9" />
        <path d="M19.5 12a7.5 7.5 0 0 1-13 5.1" /><path d="M6.2 19.8v-2.9h2.9" />
      </svg>
    </button>
  );
}
