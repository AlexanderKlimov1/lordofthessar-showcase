import { useEffect, useRef, useState } from 'react';
import { useColonistPick } from './useColonistPick';
import type { Planet } from '../../api/types';
import type { Colony } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { Colonist, HeldColonists, ResourceIcon, UnitIcons, type ResourceKind } from './colonyIcons';
import { output, type Jobs } from './colonyRules';
import { OutputBreakdown, type OutputResource } from './OutputBreakdown';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass, signed } from '../accent';
import { FirstTimeNote } from '../FirstTimeNote';
import { ActionError } from '../feedback/ActionError';

/**
 * Жители колонии — п. 4.1, панелью распределения экрана колонии MOO II.
 *
 * Три строки фигурок: фермеры, рабочие, учёные. Жители переносятся ЩЕЛЧКАМИ, как в
 * оригинале ({@link useColonistPick}): щелчок по фигурке берёт её и всех правее, второй
 * щелчок кладёт группу в строку под курсором, мимо строк — возвращает на место. Изменение
 * применяется сразу: подтверждать нечего, а вернуть жителей обратно — те же два щелчка.
 *
 * Каждая строка кончается тем, что эти жители дают: фермеры — едой, рабочие —
 * производством, учёные — наукой. Отдельной панели выработки нет: величина и те, кто её
 * делает, стоят в одной строке, и перенос жителя видно тут же, без взгляда на соседний
 * сегмент. Население с приростом — в заголовке панели: это тоже про жителей.
 *
 * Считает распределение {@link useColonyJobs} — этажом выше, потому что от него зависит
 * и стройка.
 *
 * С клавиатуры (Enter или пробел на фигурке) житель переходит на следующую работу по
 * кругу. Этого в MOO II нет, но фигурки — обычные кнопки, и без такого хода они были бы
 * недоступны с клавиатуры. Щелчок мышью этот ход не делает: у мыши свой путь, через группу.
 */

type JobName = keyof Jobs;

const JOB_ORDER: JobName[] = ['farmers', 'workers', 'scientists'];

const JOB_LABELS: Record<JobName, Key> = {
  farmers: 'colony.job.farmers',
  workers: 'colony.job.workers',
  scientists: 'colony.job.scientists',
};

export function ColonyJobs({
  planet,
  colony,
  jobs,
  working,
  onLift,
  move,
  error,
  own,
}: {
  planet: Planet;
  colony?: Colony;
  jobs: Jobs;
  /** Распределение без жителей «на руках» — от него и считаются итоги строк. */
  working: Jobs;
  /** Сообщить наверх, кого сняли с работы щелчком (или что группу положили). */
  onLift: (group: { from: JobName; count: number } | null) => void;
  move: (from: JobName, to: JobName, count?: number) => void;
  error: string | null;
  own: boolean;
}) {
  const players = useGameStore((state) => state.players);
  useT();

  // Величины считаются от черновика распределения: фигурки переставляются не дожидаясь
  // сервера, и число в строке должно меняться вместе с ними. Взятые щелчком уже сняты с
  // работы (09.10.2026, решение хозяина проекта): итоги падают в миг подъёма группы, а не после
  // того, как её положили, — видно, сколько стоит снять этих жителей.
  const produced = colony ? output(colony, working) : null;
  // Остаток еды после прокорма: он же и решает, растёт колония или голодает. Подвоз
  // грузового флота империи (п. 4.1.1) считает сервер — он знает все колонии, а не одну.
  const delivered = colony?.foodDelivered ?? 0;
  const balance = produced ? produced.food - planet.population + delivered : 0;
  const values: Record<
    JobName,
    { value: number; balance?: number; delivered?: number; waste?: number } | null
  > = {
    farmers: produced
      ? { value: produced.food, balance, delivered }
      : null,
    // Уборка за собой (п. 10) стоит в строке рабочих: грязь делают они, и видно сразу,
    // сколько из добытого до стройки не доедет.
    workers: produced ? { value: produced.production, waste: produced.pollution } : null,
    scientists: produced ? { value: produced.research } : null,
  };

  // Перевозка жителей в другую колонию — п. 4.1.1: диалог открывается отсюда, из
  // сегмента жителей, потому что везут именно их.
  // Разбивка итога строки — пункт 40: какая строка раскрыта.
  const [breakdown, setBreakdown] = useState<JobName | null>(null);

  const rows = useRef<Partial<Record<JobName, HTMLDivElement | null>>>({});

  const color = players.find((player) => player.id === planet.ownerPlayerId)?.color ?? '#7fd4ff';

  /*
    Фермеры там, где еду не вырастить (12.10.2026, баг хозяина проекта): токсичная планета и
    прочие, где фермер не даёт ни единицы еды, — строка гаснет, подписана «недоступно» и жителей
    не принимает. Правило то же, что у сервера (`ColonyService.canFarm`): еда с фермера с
    климатом, расой и зданиями.
  */
  const closed = (job: JobName): boolean => job === 'farmers' && !!colony && colony.foodPerFarmer <= 0;

  /** Строка, над которой сейчас курсор: рамки строк известны, попадание считаем по ним. */
  const rowUnder = (x: number, y: number): JobName | null =>
    JOB_ORDER.filter((job) => !closed(job)).find((job) => {
      const rect = rows.current[job]?.getBoundingClientRect();
      return rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    }) ?? null;

  const { held, pick } = useColonistPick<JobName, JobName>(
    (x, y) => rowUnder(x, y),
    (target, group) => move(group.from, target, group.count),
  );
  const hover = held ? rowUnder(held.x, held.y) : null;
  const heldFrom = held?.from ?? null;
  const heldCount = held?.count ?? 0;
  useEffect(() => {
    onLift(heldFrom ? { from: heldFrom, count: heldCount } : null);
  }, [heldFrom, heldCount]);

  return (
    <section className="flex flex-col border border-space-700 bg-space-950/60">
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 border-b border-space-800 px-2 py-1">
        <span className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('colony.jobs.title')}</span>
        {colony ? (
          <span className={figureLabelClass('value')}>
            {/* Население с приростом — в шапке колонии значком (редизайн, 07.10.2026). */}
            {/* Подданные — п. 12: взятые с боем жители работают по правилам своей прежней
                расы, пока не станут своими. */}
            {colony.alienPopulation > 0 ? (
              <>
                {' · '}
                <span
                  className="text-warn"
                  title={t('colony.jobs.subjects.title')}
                >
                  {tf('colony.jobs.subjects', { n: <Figure accent="value" tone="warn">{colony.alienPopulation}</Figure> })}
                  {colony.assimilationTurns !== undefined
                    ? tf('colony.jobs.nextAssimilation', { n: <Figure accent="value" tone="warn">{colony.assimilationTurns}</Figure> })
                    : ''}
                </span>
              </>
            ) : null}
          </span>
        ) : null}
      </div>

      <div className="flex-1">
        {JOB_ORDER.map((job) => (
          <div
            key={job}
            ref={(element) => {
              rows.current[job] = element;
            }}
            className={
              'flex touch-none select-none items-center gap-2 border-b border-space-800'
              + ' px-2 py-1.5 last:border-b-0 ' +
              (hover === job && held?.from !== job ? 'bg-space-800' : '') +
              (closed(job) ? ' opacity-50' : '')
            }
            aria-disabled={closed(job) || undefined}
            title={closed(job) ? t('colony.jobs.farmersClosed.title') : undefined}
          >
            <JobIcon job={job} />
            {closed(job) && jobs[job] === 0 ? (
              <span className="flex min-h-[36px] flex-1 items-center text-16 text-ink-off">
                {t('colony.jobs.unavailable')}
              </span>
            ) : null}
            <ul className={'flex min-h-[36px] flex-1 flex-wrap content-center gap-[7px]'
              + (closed(job) && jobs[job] === 0 ? ' hidden' : '')}>
              {Array.from({ length: jobs[job] }).map((_, index) => (
                <li key={index}>
                  <button
                    type="button"
                    className={
                      // Фигурка 14×20, а цель — не мельче пальца (пункт 172).
                      'hit-24 block ' +
                      (own ? 'cursor-pointer' : 'cursor-default') +
                      // Взятые остаются на месте бледными: видно, откуда группа ушла и куда
                      // вернётся, если положить её мимо строк.
                      (held?.from === job && index >= jobs[job] - held.count ? ' opacity-30' : '')
                    }
                    disabled={!own}
                    draggable={false}
                    aria-label={t('colony.jobs.colonist.aria', { job: t(JOB_LABELS[job]), n: index + 1 })}
                    // Скрытое действие названо (пункт 72): щелчок берёт и всех правее.
                    title={own ? t('colony.jobs.colonist.title') : undefined}
                    onPointerDown={own ? pick(job, jobs[job] - index) : undefined}
                    onDragStart={(event) => event.preventDefault()}
                    onClick={(event) => {
                      // Только клавиатура: у её «щелчка» нет точки нажатия (detail = 0).
                      if (event.detail !== 0) {
                        return;
                      }
                      // По кругу — мимо закрытой строки: на токсичной планете фермеров не бывает.
                      const open = JOB_ORDER.filter((other) => !closed(other) || other === job);
                      move(job, open[(open.indexOf(job) + 1) % open.length]);
                    }}
                  >
                    <Colonist color={color} className="h-[34px] w-[24px]" />
                  </button>
                </li>
              ))}
            </ul>

            {/*
              Итог строки: что эти жители дают за ход. Межстрочный интервал сжат
              (leading-none): иначе высоту полосы задавало бы это число, а не фигурки,
              и уменьшать значки было бы бессмысленно.
            */}
            {values[job] ? (
              <span className="flex shrink-0 items-baseline gap-2">
                {job === 'workers' ? (
                  /*
                    Производство — молотками, по одному на единицу, а загрязнение — бочками
                    отходов следом (12.10.2026, решение хозяина проекта, как в оригинале,
                    `docs/moo2/colony.png`): видно разом, сколько добыто и сколько из этого
                    съела уборка. Числа — в подсказке.
                  */
                  <span
                    className="flex items-center gap-1"
                    title={t('colony.jobs.units.title', {
                      mined: produced!.mined, net: values[job]!.value, waste: values[job]!.waste ?? 0,
                    })}
                  >
                    <UnitIcons kind="hammer" count={values[job]!.value} className="h-5 w-5 shrink-0 text-lamp" />
                    <UnitIcons kind="barrel" count={values[job]!.waste ?? 0} className="h-5 w-5 shrink-0 text-warn" />
                  </span>
                ) : null}
                {/*
                  Итог — кнопка «из чего сложилось» (пункт 40): слагаемые раскрываются под
                  строками. Нажатием, а не наведением: у пальца наведения нет (пункт 60).
                */}
                {/* Итог строки — значком с числом справа (редизайн колонии, 07.10.2026). */}
                <button
                  type="button"
                  className="link-quiet hit-24 flex min-w-[5ch] items-center justify-end gap-1.5 leading-none"
                  title={t('breakdown.open')}
                  aria-expanded={breakdown === job}
                  onClick={() => setBreakdown(breakdown === job ? null : job)}
                >
                  <ResourceIcon kind={RESOURCE_ICON[job]} className="h-6 w-6 shrink-0 text-lamp" />
                  {/*
                    У фермеров итог строки — ОСТАТОК еды после прокорма (09.10.2026, решение хозяина
                    проекта): «едят N» и выход по отдельности не нужны, решает остаток; из чего он
                    сложился — в разбивке по нажатию.
                  */}
                  {job === 'farmers' && values[job]!.balance !== undefined ? (
                    <Figure accent="lead" tone={values[job]!.balance! < 0 ? 'danger' : 'good'}>
                      {signed(values[job]!.balance!)}
                    </Figure>
                  ) : (
                    <Figure accent="lead">{values[job]!.value}</Figure>
                  )}
                </button>
              </span>
            ) : null}
          </div>
        ))}
      </div>

      {breakdown && colony && values[breakdown] ? (
        <OutputBreakdown
          colony={colony}
          resource={RESOURCE_OF[breakdown]}
          units={jobs[breakdown]}
          total={values[breakdown]!.value}
          onClose={() => setBreakdown(null)}
        />
      ) : null}
      {/* Первое загрязнение — одной строкой правила (field-backlog, пункты 28 и 111). */}
      {produced?.pollution ? <FirstTimeNote meeting="pollution" className="px-2 py-1 text-14" /> : null}

      {error ? <ActionError className="px-2 pb-1 text-20" message={error} /> : null}


      {/* Группа на руках: летит за курсором и не перехватывает события. */}
      {held ? <HeldColonists held={held} color={color} className="h-[34px] w-[24px]" /> : null}
    </section>
  );
}

/**
 * Значок занятия слева от строки: мешок зерна, молоток и микроскоп — ровно те же
 * рисунки, что стоят у еды, производства и науки в панели выработки. Житель и то, что
 * он даёт, помечены одинаково, и строку жителей с величиной выработки видно парой.
 */
function JobIcon({ job }: { job: JobName }) {
  const kinds: Record<JobName, ResourceKind> = {
    farmers: 'grain',
    workers: 'hammer',
    scientists: 'microscope',
  };

  // На 30% меньше прежнего: полоса жителей стала ниже, а разобрать значок это не мешает.
  return <ResourceIcon kind={kinds[job]} label={t(JOB_LABELS[job])}
                       className="h-[26px] w-[26px] shrink-0 text-ink-dim" />;
}

/** Значок ресурса у итога строки — тот же, что у занятия слева. */
const RESOURCE_ICON: Record<JobName, ResourceKind> = {
  farmers: 'grain',
  workers: 'hammer',
  scientists: 'microscope',
};

/** Какой ресурс даёт дело — для разбивки итога (пункт 40). */
const RESOURCE_OF: Record<JobName, OutputResource> = {
  farmers: 'FOOD',
  workers: 'PRODUCTION',
  scientists: 'RESEARCH',
};
