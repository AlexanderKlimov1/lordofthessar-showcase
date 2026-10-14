import type { Planet } from '../../api/types';
import { BuyCoin } from './BuyCoin';
import { Colonist, ResourceIcon, type ResourceKind } from '../system/colonyIcons';
import { output, turnsLeft } from '../system/colonyRules';
import { t, tf, type Key } from '../../i18n';
import { Figure, TurnsFigure } from '../Figure';
import { figureLabelClass, signed, signTone } from '../accent';
import type { JobName } from './usePopulationDrag';
import { climateName, planetSizeName } from '../planetLabels';
import { isCapitol } from '../system/buildingArt';

/**
 * Строка колонии в списке колоний империи — п. 4.1.1 и п. 10.
 *
 * Состав блоков строки взят у окна Colonies MOO II: слева название колонии, дальше
 * жители фигурками, затем еда, производство и наука, и в конце — что колония строит.
 * Порядок тот же, что в оригинале: сперва кто, потом сколько их, потом что они дают,
 * и лишь затем — куда уходит производство. Доход стоит между наукой и стройкой: это
 * единственная величина без своих жителей, и в оригинале её в этом окне нет вовсе, а
 * здесь она нужна — казна империи складывается из колоний, и видеть её надо рядом.
 *
 * Жители нарисованы по занятиям и помечены теми же значками, что и величины справа:
 * мешок у фермеров и у еды, молоток у рабочих и у производства, микроскоп у учёных и у
 * науки. Так строка читается парами «кто — что даёт», а не столбцом отдельных чисел.
 *
 * Фигурка — ручка переноса: жителя перетаскивают в другую строку, и он уезжает туда
 * грузовым флотом. Занятие при этом не переезжает: сервер раскладывает прибывших по
 * работам сам, а перевозится именно житель.
 *
 * <b>В пределах своей колонии грузовой флот не нужен</b>: бросок на другую группу занятий
 * той же строки и щелчок по фигурке просто меняют занятие (как на экране колонии), а
 * перевозку по щелчку вызывает Shift.
 *
 * Справа от названия стройки стоит монетка выкупа (`BuyCoin`, п. 10): решают о выкупе,
 * глядя на всю империю разом — у кого стройка вот-вот кончится и на что хватит казны, — а
 * до этой правки выкуп жил только на экране колонии, и на каждую покупку уходило открыть
 * колонию, купить и вернуться.
 */

/**
 * Ширины столбцов: шапка списка и строки берут их отсюда, иначе разъедутся.
 *
 * Первый столбец — пометка выделения: по ней колонию берут в набор, которому потом
 * закладывают стройку разом (п. 10). Узкий и стоит слева от названия, чтобы пометки
 * выстраивались в столбик и набор читался одним взглядом.
 */
export const COLUMNS =
  'grid grid-cols-[1.5rem_14rem_minmax(14rem,1fr)_4.5rem_4.5rem_4.5rem_4.5rem_15rem]'
  + ' items-center gap-x-3 px-2';

const JOB_ORDER: JobName[] = ['farmers', 'workers', 'scientists'];

const JOB_LABELS: Record<JobName, Key> = {
  farmers: 'colony.job.farmers',
  workers: 'colony.job.workers',
  scientists: 'colony.job.scientists',
};

const JOB_ICONS: Record<JobName, ResourceKind> = {
  farmers: 'grain',
  workers: 'hammer',
  scientists: 'microscope',
};

export function ColonyRow({
  planet,
  systemName,
  color,
  hovered,
  refused,
  hoverZone,
  source,
  selected,
  credits,
  busy,
  rowRef,
  zoneRef,
  onStartDrag,
  held,
  onOpen,
  onOpenBuild,
  onTransfer,
  onMoveJob,
  onSelect,
  onBuy,
}: {
  planet: Planet;
  systemName: string;
  /** Казна империи: по ней монетка выкупа и гаснет — п. 10. */
  credits: number;
  /** Запрос к серверу ещё идёт: второе нажатие выкупа списало бы казну дважды. */
  busy: boolean;
  /** Цвет расы владельца: тот же, каким жители нарисованы на экране колонии. */
  color: string;
  /** Над этой строкой сейчас держат жителя — она и примет его. */
  hovered: boolean;
  /**
   * Сюда группу не довезти (нет грузовиков, места или колония осталась бы пустой) — строка
   * горит красным, и бросок вернёт жителей на место.
   */
  refused: boolean;
  /** «колония:занятие» группы, над которой держат жителя: бросок сюда сменит занятие. */
  hoverZone: string | null;
  /** Из этой строки жителя несут. */
  source: boolean;
  /** Колония в наборе: приказ стройкой достанется и ей — п. 10. */
  selected: boolean;
  rowRef: (element: HTMLDivElement | null) => void;
  /** Рамка группы занятия — приёмник броска внутри своей колонии. */
  zoneRef: (job: JobName) => (element: HTMLElement | null) => void;
  /** Взять жителя и всех правее него в группе: `count` — сколько их, считая взятого. */
  onStartDrag: (job: JobName, count: number) => (event: React.PointerEvent<HTMLElement>) => void;
  /** Группа, взятая из этой строки: её фигурки бледнеют на месте. */
  held: { job: JobName; count: number } | null;
  onOpen: () => void;
  /** Щелчок по текущей стройке — колония открывается сразу на окне стройки (очереди). */
  onOpenBuild: () => void;
  /** Перевозка жителя в другую колонию (Shift + щелчок): диалог с грузовиками. */
  onTransfer: () => void;
  /** Житель меняет занятие в этой же колонии: от одного к другому, без грузовиков. */
  onMoveJob: (from: JobName, to: JobName) => void;
  /** С Shift — набор до этой строки от последней помеченной. */
  onSelect: (range: boolean) => void;
  /** Выкупить стройку этой колонии — п. 10; подтверждение спрашивает сам экран. */
  onBuy: () => void;
}) {
  const colony = planet.colony;
  if (!colony) {
    return null;
  }

  // Остаток еды считается как на экране колонии: своя выработка минус едоки плюс подвоз
  // грузового флота — п. 4.1.1. Сервер шлёт остаток до подвоза, а голодает колония или
  // нет, решает подвоз.
  // Жители «на руках» уже сняты с работы (09.10.2026, решение хозяина проекта): строка считает
  // выработку без них, пока группу не положат. Правило выработки — то же, что у экрана колонии
  // (`colonyRules.output`), а к серверным числам прибавляется только разница: подвоз, уборку и
  // доход сервер знает лучше одной строки.
  const working = { farmers: colony.farmers, workers: colony.workers, scientists: colony.scientists };
  const lifted = held ? { ...working, [held.job]: Math.max(0, working[held.job] - held.count) } : working;
  const before = output(colony, working);
  const after = output(colony, lifted);
  const food = colony.food + after.food - before.food;
  const production = colony.production + after.production - before.production;
  const research = colony.research + after.research - before.research;
  const balance = colony.foodBalance + after.food - before.food + colony.foodDelivered;
  // Последнего жителя увезти в другую колонию нельзя (колония без населения перестала бы
  // существовать) — но занятие он менять вправе, поэтому фигурка живая всегда, а отказ
  // на перевозке даёт сам экран.

  return (
    <div
      ref={rowRef}
      data-colony-row={planet.id}
      className={
        `${COLUMNS} border-b border-space-800 py-3 ` +
        (hovered && !source && refused
          ? 'bg-space-800 outline outline-1 -outline-offset-1 outline-danger'
          : hovered && !source
          ? 'bg-space-800'
          : source
            ? 'bg-space-900/60'
            : selected
              ? 'bg-space-900'
              : '')
      }
    >
      {/*
        Пометка выделения — п. 10. Квадратик, а не флажок браузера: экран нарисован
        своими средствами, и родной флажок в нём выглядел бы чужим. Нажатие с Shift
        помечает всё от прошлой помеченной строки до этой — списком колоний правят
        целыми кусками.
      */}
      <button
        type="button"
        aria-pressed={selected}
        aria-label={t('colonyRow.select.aria', { name: planet.name })}
        className={
          'flex h-5 w-5 items-center justify-center border text-14 leading-none '
          + (selected
            ? 'border-accent bg-accent/20 text-accent'
            : 'border-space-700 text-transparent hover:border-space-600')
        }
        onClick={(event) => onSelect(event.shiftKey)}
      >
        ✔
      </button>

      {/* Колония: отсюда открывается экран управления планетой — как в оригинале. */}
      <button type="button" className="link min-w-0 text-left" onClick={onOpen}>
        {/* Население и предел планеты — у названия (12.10.2026, решение хозяина проекта). */}
        <span className="block truncate text-19 text-ink-bright"
              title={t('colonyRow.population.title', { n: planet.population, max: colony.maxPopulation, growth: signed(colony.growthK) })}>
          {planet.name}{' '}
          <span className="text-ink-soft">{planet.population}</span>
          <span className="text-ink-dim">/{colony.maxPopulation}</span>
        </span>
        <span className="block truncate text-15 text-ink-faint no-underline">
          {systemName} · {planetSizeName(planet.size, planet.sizeLabel)} · {climateName(planet.climate, planet.climateLabel)}
          {planet.homeworld ? t('colonyRow.homeworld') : ''}
          {isCapitol(colony) ? t('colonyRow.capitol') : ''}
        </span>
      </button>

      {/*
        Жители: три группы по занятиям, каждая фигурка — ручка переноса. Группы — РАВНЫЕ
        доли столбца (01.10.2026, решение хозяина проекта), а не по числу жителей: иначе
        рабочие одной колонии стояли под фермерами другой, и столбцы занятий не читались
        сверху вниз. Не влезло в долю — фигурки переносятся строкой ниже внутри своей группы.
      */}
      <div className="flex flex-col gap-0.5"
           title={t('colonyRow.population.title', { n: planet.population, max: colony.maxPopulation, growth: signed(colony.growthK) })}>
        <div className="grid grid-cols-3 gap-x-3">
          {JOB_ORDER.map((job) => {
            // Фермеры там, где еду не вырастить (12.10.2026): группа гаснет и жителей не
            // принимает — без своей зоны бросок туда считается броском на строку колонии.
            const closed = job === 'farmers' && colony.foodPerFarmer <= 0;
            return (
            <div
              key={job}
              ref={closed ? undefined : zoneRef(job)}
              title={closed ? t('colony.jobs.farmersClosed.title') : undefined}
              aria-disabled={closed || undefined}
              className={
                'flex min-h-[26px] min-w-0 flex-wrap items-center gap-1 px-1 '
                + (hoverZone === `${planet.id}:${job}` ? 'bg-space-800 ring-1 ring-accent' : '')
                + (closed ? ' opacity-50' : '')
              }
            >
              <ResourceIcon
                kind={JOB_ICONS[job]}
                label={t(JOB_LABELS[job])}
                className="h-[19px] w-[19px] shrink-0 text-ink-faint"
              />
              {colony[job] === 0 ? (
                <span className="text-15 text-ink-off">{closed ? t('colony.jobs.unavailable') : '—'}</span>
              ) : (
                Array.from({ length: colony[job] }).map((_, index) => (
                  <button
                    key={index}
                    type="button"
                    data-colonist={planet.id}
                    className={
                      'cursor-pointer'
                      + (held?.job === job && index >= colony[job] - held.count ? ' opacity-30' : '')
                    }
                    draggable={false}
                    aria-label={t('colonyRow.colonist.aria', { job: t(JOB_LABELS[job]), name: planet.name, n: index + 1 })}
                    // Скрытые действия названы (field-backlog, пункт 72): щелчок берёт группу,
                    // Shift + щелчок открывает перевозку.
                    title={t('colonyRow.colonist.title')}
                    onPointerDown={onStartDrag(job, colony[job] - index)}
                    onDragStart={(event) => event.preventDefault()}
                    onClick={(event) => {
                      if (event.shiftKey) {
                        onTransfer();
                        return;
                      }
                      // Смена занятия по кругу — только с клавиатуры (detail = 0): мышь
                      // берёт группу нажатием, и её щелчок здесь лишний.
                      if (event.detail === 0) {
                        // По кругу — мимо закрытой группы фермеров.
                        const open = JOB_ORDER.filter((other) => other !== 'farmers' || colony.foodPerFarmer > 0 || other === job);
                        onMoveJob(job, open[(open.indexOf(job) + 1) % open.length]);
                      }
                    }}
                  >
                    <Colonist color={color} className="h-[20px] w-[14px]" />
                  </button>
                ))
              )}
            </div>
            );
          })}
        </div>
      </div>

      {/*
        Еда стоит с остатком: сама по себе выработка ничего не говорит — колония живёт
        или голодает по тому, что осталось после едоков и подвоза.
      */}
      <Value
        value={balance}
        tone={balance < 0 ? 'danger' : 'good'}
        signedValue
        title={t('colonyRow.food.title', { food, delivered: colony.foodDelivered })}
      />

      {/*
        Производство идёт с уборкой (п. 10): в столбце стоит то, что достаётся стройке,
        а под ним — сколько колония тратит на то, чтобы убрать за собой. Иначе
        одинаковые по жителям колонии различались бы выработкой без всякой видимой
        причины — грязь зависит от размера планеты.
      */}
      <Value
        value={production}
        warn={colony.pollution > 0}
        title={colony.pollution > 0 ? t('colonyRow.production.cleanup', { n: colony.pollution }) : undefined}
      />

      <Value value={research} />

      {/* Доход — единственная величина без своих жителей: она из налогов и содержания. */}
      <Value
        value={colony.income}
        tone={signTone(colony.income)}
        signedValue
        title={colony.upkeep > 0 ? t('colonyRow.income.upkeep', { n: colony.upkeep }) : undefined}
      />

      {/*
        Стройка: что строится и сколько осталось — тот же срок, что на экране колонии, — а
        справа монетка выкупа (п. 10). Монетка прижата к правому краю столбца, а не стоит
        встык за названием: названия разной длины, и монетки плясали бы по строкам, тогда
        как в столбик они читаются одним взглядом.
      */}
      <div className="flex min-w-0 items-center gap-2">
        {/*
          Щелчок по стройке открывает очередь колонии (07.10.2026): стройку меняют, глядя на
          всю империю, и путь «название → колония → сменить» был на два шага длиннее нужного.
        */}
        <button type="button" className="link-quiet min-w-0 flex-1 text-left"
                title={t('colonies.openBuild.title')} onClick={onOpenBuild}>
          <span className="block truncate text-18 text-ink">
            {colony.projectName ?? t('build.nothing')}
            {/*
              Очередь названа числом, а не списком: в строке списка колоний ей места нет,
              а знать, что колония занята надолго вперёд, отсюда и нужно — п. 10.
            */}
            {colony.queue.length > 0 ? (
              <> <Figure accent="value">+{colony.queue.length}</Figure></>
            ) : null}
          </span>
          <span className={'block truncate ' + figureLabelClass('note')}>{progress(planet)}</span>
        </button>
        <BuyCoin
          colony={colony}
          credits={credits}
          colonyName={planet.name}
          busy={busy}
          onBuy={onBuy}
        />
      </div>
    </div>
  );
}

/**
 * Число столбца — редизайн списка колоний (07.10.2026, решение хозяина проекта): в строке только
 * числа, крупно; то, что прежде стояло мелкой припиской под ними (подвоз, уборка, содержание), —
 * во всплывающей подсказке, а «есть уборка» — знаком ◆ рядом с числом.
 */
function Value({ value, tone, signedValue = false, warn = false, title }: {
  value: number;
  tone?: 'danger' | 'good' | 'warn';
  signedValue?: boolean;
  warn?: boolean;
  title?: string;
}) {
  return (
    <div className="text-right" title={title}>
      {warn ? <span aria-hidden className="mr-1 text-14 text-warn">◆</span> : null}
      <Figure accent="lead" tone={tone}>{signedValue ? signed(value) : value}</Figure>
    </div>
  );
}

/**
 * Чем кончится стройка: у здания — вложенное и срок, у домов и товаров — их отдача,
 * потому что они не заканчиваются. Короче, чем на экране колонии: здесь строка одна
 * из многих.
 */
const progress = (planet: Planet): React.ReactNode => {
  const colony = planet.colony;
  if (!colony?.projectCode) {
    return t('colonyRow.wasted');
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? tf('colonyRow.housing', { n: <Figure accent="note">{colony.housingBonusPercent ?? 0}</Figure> })
      : tf('colonyRow.tradeGoods', { n: <Figure accent="note">{Math.floor(colony.production / 2)}</Figure> });
  }
  const turns = turnsLeft(colony);
  // Срок — первым и вдвое крупнее (12.10.2026): столбец узкий, и срезаться должно вложенное,
  // а не срок.
  return (
    <>
      {turns === null ? t('colonyRow.noProduction') : (
        <>{tf('build.turns', { n: <TurnsFigure accent="note">{turns}</TurnsFigure> })} · </>
      )}
      {tf('build.progress', {
        points: <Figure accent="note">{colony.projectPoints}</Figure>,
        cost: <Figure accent="note">{colony.projectCost}</Figure>,
      })}
    </>
  );
};
