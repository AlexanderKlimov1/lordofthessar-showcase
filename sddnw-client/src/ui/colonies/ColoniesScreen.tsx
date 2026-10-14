import { useState } from 'react';
import { gameApi } from '../../api/client';
import type { ColonyProject, Planet } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { HeldColonists } from '../system/colonyIcons';
import { ColonyBuyDialog } from '../system/ColonyBuyDialog';
import { TransferDialog } from '../system/TransferDialog';
import { useModalEscape } from '../useModalEscape';
import { ColoniesQueueDialog } from './ColoniesQueueDialog';
import { COLUMNS, ColonyRow } from './ColonyRow';
import { usePopulationDrag, type Drop, type JobName } from './usePopulationDrag';
import { t, useT } from '../../i18n';
import { Colonist, ResourceIcon } from '../system/colonyIcons';
import { ColoniesIcon, TransportIcon } from '../panelIcons';
import { buyState } from './BuyCoin';
import { Figure } from '../Figure';
import { figureLabelClass, signed, signTone } from '../accent';
import type { ColonistJob } from '../../api/types';
import { ActionError } from '../feedback/ActionError';

/** Занятие экрана — так же, как его называет сервер. */
const SERVER_JOB: Record<JobName, ColonistJob> = {
  farmers: 'FARMERS',
  workers: 'WORKERS',
  scientists: 'SCIENTISTS',
};

/** Значок с числом в сводке империи; что это за число — в подсказке. */
function Summary({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <span className="flex items-center gap-1.5" title={title}>
      <span className="text-lamp">{icon}</span>
      <span className="sr-only">{title}</span>
      {children}
    </span>
  );
}

/**
 * Колонии империи — экран нижнего меню, окном Colonies MOO II (п. 4.1.1, п. 10, п. 11.1).
 *
 * Экран занимает всё окно, как и в оригинале: колоний у империи бывает под десяток, и
 * каждая занимает строку в семь блоков — колония, её жители, еда, производство, наука,
 * доход и стройка. Шапка списка и строки берут ширины столбцов из одного места
 * (`COLUMNS`), иначе они разъезжаются на первой же правке.
 *
 * Два действия, ради которых в оригинале и открывают это окно:
 *
 * * **перевозка жителей** — житель перетаскивается из одной строки в другую, и рейс
 *   уходит грузовым флотом (п. 4.1.1). Грузовик резервируется на каждого жителя, поэтому
 *   свободные грузовики стоят в шапке: без них перевозки не будет, и узнавать об этом из
 *   ошибки сервера поздно. Нажатие на жителя открывает тот же рейс диалогом — так можно
 *   отправить сразу нескольких и так до жителей добираются с клавиатуры;
 * * **переход к колонии** — нажатие на название открывает экран управления планетой.
 *   Он ложится поверх списка: закрыл колонию — вернулся к списку, как в оригинале;
 * * **выкуп стройки** (п. 10) — монетка справа от названия стройки. Решают о выкупе,
 *   глядя на всю империю: у кого стройка вот-вот кончится, у кого встала и на что хватит
 *   казны, — поэтому монетка стоит здесь, а не только на экране колонии. Подтверждение
 *   спрашивает то же окошко, что и там (`ColonyBuyDialog`): выкуп тратит казну, и
 *   покупка одним нажатием в списке из десяти строк — это покупка не в той колонии.
 *
 * Своих правил у экрана нет: он показывает то, что уже пришло в карте (колония приходит
 * с каждой планетой целиком), и просит сервер о перевозке. Считает всё сервер.
 */

/**
 * Ключи порядка списка — как полоса SORT в консоли колоний оригинала
 * (`docs/moo2/colonies.png`: Name, Population, Food, Industry, Science, Producing, BC).
 *
 * Своей полосы мы не заводим: ключом служит сам ЗАГОЛОВОК столбца, по нему и нажимают.
 * Числа идут по убыванию (сверху лучшие), имена — по алфавиту: у порядка по величине
 * смысл «покажи, где густо», а у имени — «найди вот эту колонию».
 */
type ColonyOrder = 'name' | 'colonists' | 'food' | 'production' | 'research' | 'income' | 'build';

type ColonyEntry = { planet: Planet; systemId: string; systemName: string };

function compare(order: ColonyOrder) {
  const byName = (left: ColonyEntry, right: ColonyEntry) =>
    left.systemName.localeCompare(right.systemName) || left.planet.orbit - right.planet.orbit;
  if (order === 'name') {
    return byName;
  }
  if (order === 'build') {
    return (left: ColonyEntry, right: ColonyEntry) =>
      (left.planet.colony?.projectName ?? '').localeCompare(right.planet.colony?.projectName ?? '')
      || byName(left, right);
  }
  const value = (entry: ColonyEntry) => {
    const colony = entry.planet.colony;
    if (!colony) {
      return 0;
    }
    switch (order) {
      case 'colonists':
        return entry.planet.population;
      case 'food':
        return colony.food;
      case 'production':
        return colony.production;
      case 'research':
        return colony.research;
      default:
        return colony.income;
    }
  };
  return (left: ColonyEntry, right: ColonyEntry) =>
    value(right) - value(left) || byName(left, right);
}

export function ColoniesScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const self = useGameStore(selectSelf);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const setLobby = useGameStore((state) => state.setLobby);
  const setMap = useGameStore((state) => state.setMap);
  const setResearch = useGameStore((state) => state.setResearch);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  const openedColonyId = useGameStore((state) => state.openedColonyId);

  const [transferFrom, setTransferFrom] = useState<Planet | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /*
    Набор колоний — п. 10: пометки живут в самом экране и уходят вместе с ним. Хранить
    их дольше незачем: набирают их ради одного приказа, а список колоний меняется каждый
    ход — сохранённый набор пришлось бы чистить от потерянных колоний.
  */
  const [selected, setSelected] = useState<string[]>([]);
  /** Последняя помеченная строка: от неё Shift набирает кусок списка. */
  const [anchor, setAnchor] = useState<string | null>(null);
  const [queueing, setQueueing] = useState(false);
  /** Колония, для которой открыто окошко выкупа, — п. 10. */
  const [buying, setBuying] = useState<Planet | null>(null);

  /*
    Пока поверх открыт экран колонии или диалог перевозки, Esc список не слушает вовсе.
    Диалог рождается в той же отрисовке, что и экран, и в очереди оказался бы ниже —
    одно нажатие закрыло бы оба разом. На этом уже обжигались на экране исследований.
  */
  useModalEscape(
    openedColonyId === null && transferFrom === null && !queueing && buying === null,
    onClose,
  );
  const [order, setOrder] = useState<ColonyOrder>('name');
  useT();

  // Колонии берутся из карты: колония приходит с каждой планетой целиком, и своего
  // запроса этому экрану не нужно. Порядок — по системе и орбите, как на карте.
  const colonies = (map?.systems ?? [])
    .flatMap((system) =>
      system.planets
        .filter((planet) => planet.ownerPlayerId === self?.id && planet.colony)
        .map((planet) => ({
          planet,
          systemId: system.id,
          systemName: system.name ?? t('colonies.unnamedSystem'),
        })),
    )
    .sort(compare(order));

  /** Помечены все колонии списка: по этому и переключается пометка в шапке. */
  const allSelected = colonies.length > 0 && selected.length >= colonies.length;

  const freighters = self?.freighters ?? 0;
  const free = Math.max(0, freighters - (self?.freightersReserved ?? 0));

  /**
   * Житель меняет занятие в своей колонии — п. 4.1: тот же запрос, что и на экране колонии
   * (`useColonyJobs.move`). Грузовой флот здесь не нужен вовсе: рейса нет.
   */
  const moveJob = (planetId: string, from: JobName, to: JobName, count = 1) => {
    const planet = colonies.find((entry) => entry.planet.id === planetId)?.planet;
    const colony = planet?.colony;
    const moving = Math.min(count, colony?.[from] ?? 0);
    if (!game || !credentials || !planet || !colony || busy || from === to || moving <= 0) {
      return;
    }
    const next = { farmers: colony.farmers, workers: colony.workers, scientists: colony.scientists };
    next[from] -= moving;
    next[to] += moving;
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .setPopulation(game.id, planet.id, credentials.accessToken,
        next.farmers, next.workers, next.scientists)
      .then(async (updated) => {
        updatePlanet(updated);
        // Учёные колонии — часть дохода очков всей империи (п. 9).
        setResearch(await gameApi.getResearch(game.id, credentials.accessToken));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /**
   * Отправка одного жителя — п. 4.1.1.
   *
   * <b>Внутри своей системы житель переходит сразу и без грузовиков</b> — правило
   * оригинала; между системами он сходит с колонии сейчас, а прибывает в конце хода, и
   * рейс держит по грузовику на жителя.
   *
   * Нехватку грузовиков и последнего жителя проверяем и здесь: сервер их тоже не
   * пропустит, но игрок должен узнать об этом от того места, где тянул фигурку.
   */
  /**
   * Можно ли довезти группу из `count` жителей — п. 4.1.1. Пусто — можно; иначе причина
   * словами.
   *
   * Спрашивается ДВАЖДЫ одним правилом: пока группа летит над строкой (строка горит красным,
   * у курсора — причина) и в миг броска. Узнавать о нехватке грузовиков после щелчка — плохой
   * обмен: решают, куда класть, глядя на строку, и ответ должен быть там же. Последнее слово
   * всё равно за сервером (он проверяет то же самое), но до него доходит только законное.
   */
  const tripRefusal = (fromPlanetId: string, toPlanetId: string, count: number): string | null => {
    const fromEntry = colonies.find((entry) => entry.planet.id === fromPlanetId);
    const toEntry = colonies.find((entry) => entry.planet.id === toPlanetId);
    if (!fromEntry || !toEntry) {
      return null;
    }
    const from = fromEntry.planet;
    const to = toEntry.planet;
    if (from.population - count < 1) {
      return t('colonies.lastColonist', { name: from.name });
    }
    if (fromEntry.systemId === toEntry.systemId) {
      // Своя система: грузовики не нужны, но жители переходят сразу — и место на планете
      // назначения спрашивается тоже сразу (`moveWithinSystem` на сервере).
      const room = Math.max(0, (to.colony?.maxPopulation ?? 0) - to.population);
      return room < count ? t('colonies.noRoom', { name: to.name, room }) : null;
    }
    // Между системами — по грузовику на жителя, из свободных (занятые рейсом еду не возят
    // и второй раз не нанимаются).
    return free < count ? t('colonies.noFreighters', { n: count, free }) : null;
  };

  const send = ({ fromPlanetId, toPlanetId, fromJob, toJob, count }: Drop) => {
    // Своя колония — смена занятия: ни грузовиков, ни рейса, ни второй строки.
    if (fromPlanetId === toPlanetId) {
      if (toJob) {
        moveJob(fromPlanetId, fromJob, toJob, count);
      }
      return;
    }
    const fromEntry = colonies.find((entry) => entry.planet.id === fromPlanetId);
    const toEntry = colonies.find((entry) => entry.planet.id === toPlanetId);
    const from = fromEntry?.planet;
    const to = toEntry?.planet;
    if (!game || !credentials || !from || !to || busy) {
      return;
    }
    // Нельзя довезти — группа возвращается на место, а причина остаётся строкой ошибки.
    const refusal = tripRefusal(fromPlanetId, toPlanetId, count);
    if (refusal) {
      setError(refusal);
      return;
    }
    const sameSystem = fromEntry?.systemId === toEntry?.systemId;

    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      /*
        Уезжают ТЕ, кого взяли, и встают на то же дело (или на то, на чью полосу их
        положили), — это решает сервер по занятию в запросе. Фермеров туда, где еду не
        растят, он сам ставит на производство: правило одно на рейс и на переход в своей
        системе, и клиенту его повторять незачем.
      */
      .transferPopulation(game.id, from.id, credentials.accessToken, to.id, count,
        SERVER_JOB[fromJob], toJob ? SERVER_JOB[toJob] : undefined)
      .then(async (updated) => {
        updatePlanet(updated);
        if (sameSystem) {
          // Внутри системы изменились обе колонии сразу, а ответ приходит один: карту
          // берём свежей, иначе колония назначения осталась бы с прежним населением.
          setMap(await gameApi.getMap(game.id, credentials.accessToken));
          setNotice(t('colonies.movedSameSystem', { from: from.name, to: to.name, n: count }));
          return;
        }
        // Рейс занял грузовик — это состояние игрока, а не планеты, и клиент его сам
        // не пересчитывает: забираем состав партии свежим.
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        setNotice(t('colonies.movedTrip', { from: from.name, to: to.name, n: count }));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /**
   * Пометить колонию или кусок списка — п. 10.
   *
   * Простое нажатие переключает одну, нажатие с Shift берёт всё от прошлой помеченной
   * строки до этой: колоний у империи бывает под десяток, и отмечать их по одной ради
   * общего приказа — работа впустую.
   */
  const toggle = (planetId: string, range: boolean) => {
    setNotice(null);
    setError(null);
    const order = colonies.map((entry) => entry.planet.id);
    if (range && anchor !== null && anchor !== planetId) {
      const from = order.indexOf(anchor);
      const to = order.indexOf(planetId);
      if (from >= 0 && to >= 0) {
        const piece = order.slice(Math.min(from, to), Math.max(from, to) + 1);
        setSelected((current) => [...new Set([...current, ...piece])]);
        setAnchor(planetId);
        return;
      }
    }
    setAnchor(planetId);
    setSelected((current) =>
      current.includes(planetId)
        ? current.filter((id) => id !== planetId)
        : [...current, planetId],
    );
  };

  // Набор чистится от того, чего в списке уже нет: колонию можно потерять, а пометка
  // пережила бы её и ушла бы в приказ несуществующей планетой.
  const selectedPlanets = colonies
    .map((entry) => entry.planet)
    .filter((planet) => selected.includes(planet.id));

  /**
   * Один проект всем выделенным колониям — п. 10.
   *
   * Проект встаёт первым в очередь каждой из них, сдвигая её вниз: приказ отсюда —
   * про всю империю разом, и ждать за тем, что каждая колония успела набрать, он не
   * может. Отвечает сервер всеми изменёнными колониями, и каждая кладётся в карту:
   * у них поменялась не только очередь, но и сама стройка.
   */
  const enqueueAll = (project: ColonyProject) => {
    if (!game || !credentials || selectedPlanets.length === 0 || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .enqueueProjects(
        game.id,
        selectedPlanets.map((planet) => planet.id),
        credentials.accessToken,
        project.code,
      )
      .then((updated) => {
        updated.forEach(updatePlanet);
        setQueueing(false);
        setNotice(t('colonies.queued', { project: project.name, n: updated.length }));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /**
   * Выкуп стройки колонии — п. 10.
   *
   * Идёт тем же путём, что и с экрана колонии (`ColonyBuild.buy`), и по той же причине
   * заканчивается пересъёмкой состава партии: выкуп тратит КАЗНУ, а казна — состояние
   * игрока, а не планеты, и клиент сам её не пересчитывает. Не забрать состав свежим — и
   * монетки соседних колоний остались бы считать себя доступными по прежней сумме.
   */
  const buy = (planet: Planet) => {
    if (!game || !credentials || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi
      .buyProject(game.id, planet.id, credentials.accessToken)
      .then(async (updated) => {
        updatePlanet(updated);
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        setBuying(null);
        setNotice(t('colonies.bought', { name: planet.name }));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /** Выкуп у выбранных — второе нажатие подтверждает (редизайн, 07.10.2026). */
  const [confirmBuy, setConfirmBuy] = useState(false);
  /**
   * Кого из выбранных выкупить: самые дешёвые первыми, пока хватает казны, — тем же правилом
   * готовности, что у монетки строки (`buyState`).
   */
  const bulk = (() => {
    let left = self?.credits ?? 0;
    const planets: Planet[] = [];
    let cost = 0;
    const ready = selectedPlanets
      .filter((planet) => planet.colony && buyState(planet.colony, Number.MAX_SAFE_INTEGER) === 'ready')
      .sort((a, b) => (a.colony!.buyCost ?? 0) - (b.colony!.buyCost ?? 0));
    for (const planet of ready) {
      const price = planet.colony!.buyCost ?? 0;
      if (price <= left) {
        planets.push(planet);
        left -= price;
        cost += price;
      }
    }
    return { planets, cost };
  })();

  /** Выкупить по очереди: запросы цепочкой, казна и строки перечитываются в конце. */
  const buyAll = (planets: Planet[]) => {
    if (!game || !credentials || busy || planets.length === 0) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    planets.reduce<Promise<number>>((chain, planet) => chain.then((done) =>
      gameApi.buyProject(game.id, planet.id, credentials.accessToken).then((updated) => {
        updatePlanet(updated);
        return done + 1;
      })), Promise.resolve(0))
      .then(async (done) => {
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        setNotice(t('colonies.boughtAll', { n: done }));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  const { held, hover, hoverZone, start, register, registerZone } = usePopulationDrag(send);
  const color = self?.color ?? '#7fd4ff';

  // Группа летит над чужой строкой: довезут ли её туда — ответ у курсора и цветом строки.
  const tripTarget = held && hover && hover !== held.from.planetId ? hover : null;
  const tripBlocked = held && tripTarget ? tripRefusal(held.from.planetId, tripTarget, held.count) : null;
  const tripTargetPlanet = colonies.find((entry) => entry.planet.id === tripTarget)?.planet;
  // Фермеры туда, где еду не растят, встанут на производство (правило сервера, п. 4.1.1):
  // сказать об этом надо до броска, иначе «фермеры пропали» читается как поломка.
  const toWorkers = held?.from.job === 'farmers' && (tripTargetPlanet?.colony?.foodPerFarmer ?? 1) <= 0;
  const tripNote = !held || !tripTarget
    ? undefined
    : tripBlocked
      ?? ((colonies.find((entry) => entry.planet.id === held.from.planetId)?.systemId
        === colonies.find((entry) => entry.planet.id === tripTarget)?.systemId
        ? t('colonies.trip.sameSystem')
        : t('colonies.trip.freighters', { n: held.count, free }))
        + (toWorkers ? ` · ${t('colonies.trip.toWorkers')}` : ''));

  const totals = colonies.reduce(
    (sum, { planet }) => ({
      population: sum.population + planet.population,
      food: sum.food + (planet.colony?.foodBalance ?? 0) + (planet.colony?.foodDelivered ?? 0),
      production: sum.production + (planet.colony?.production ?? 0),
      research: sum.research + (planet.colony?.research ?? 0),
      income: sum.income + (planet.colony?.income ?? 0),
    }),
    { population: 0, food: 0, production: 0, research: 0, income: 0 },
  );

  return (
    // Экран во всё окно: в MOO II список колоний — полноэкранный вид, а не окошко.
    <div className="absolute inset-0 z-40 flex flex-col bg-space-950 p-4">
      {/*
        СВОДКА ИМПЕРИИ — ОДНОЙ СТРОКОЙ НАВЕРХУ (редизайн, 07.10.2026, решение хозяина проекта):
        колонии, жители, грузовики, еда, производство, наука и доход значками с числами. Прежде
        итоги стояли внизу фразой, а счёт колоний — наверху другой фразой.
      */}
      <div className="mb-2 flex flex-none flex-wrap items-center gap-x-8 gap-y-2 border-b border-space-700 pb-2">
        {/* Отступ слева — под угловой значок звука, который виден поверх полноэкранных сцен. */}
        <h2 className="mr-4 pl-8 text-28 uppercase tracking-[0.3em] text-ink-bright">{t('colonies.title')}</h2>
        <div className={'flex flex-wrap items-center gap-x-6 gap-y-1 ' + figureLabelClass('lead')}>
          <Summary title={t('colonies.summary.colonies')} icon={<ColoniesIcon className="h-6 w-6" />}>
            <Figure accent="lead">{colonies.length}</Figure>
          </Summary>
          <Summary title={t('colonies.summary.colonists')} icon={<Colonist color="rgb(var(--lamp))" className="h-6 w-[17px]" />}>
            <Figure accent="lead">{totals.population}</Figure>
          </Summary>
          <Summary title={t('colonies.summary.freighters', { free, all: freighters })} icon={<TransportIcon className="h-6 w-6" />}>
            <Figure accent="lead" tone={free === 0 && freighters > 0 ? 'danger' : undefined}>{free}</Figure>
            <span className="text-ink-dim">/{freighters}</span>
          </Summary>
          {/* Главное число списка — баланс еды империи (пункт 13): минус здесь значит голод. */}
          <Summary title={t('key.food')} icon={<ResourceIcon kind="grain" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead" tone={signTone(totals.food)}>{signed(totals.food)}</Figure>
          </Summary>
          <Summary title={t('colonies.totals.production')} icon={<ResourceIcon kind="hammer" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead">{totals.production}</Figure>
          </Summary>
          <Summary title={t('colonies.totals.research')} icon={<ResourceIcon kind="microscope" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead">{totals.research}</Figure>
          </Summary>
          <Summary title={t('colonies.totals.income')} icon={<ResourceIcon kind="coins" className="h-6 w-6 text-lamp" />}>
            <Figure accent="lead" tone={signTone(totals.income)}>{signed(totals.income)}</Figure>
          </Summary>
        </div>
        <button type="button" className="link-quiet ml-auto text-19" onClick={onClose}>
          {t('common.closeEsc')}
        </button>
      </div>

      {/*
        Шапка списка стоит внутри прокрутки, а не над ней: строка шире узкого окна,
        и при боковой прокрутке подписи уехали бы от своих столбцов. По вертикали она
        остаётся на месте (sticky) — колоний бывает больше, чем влезает в экран.
      */}
      {/*
        Прокрутка со списком не выделяется мышью (select-none): жителя тащат по строкам,
        и браузер вместо переноса выделял бы текст от одной колонии до другой.
      */}
      <div className="min-h-0 flex-1 select-none overflow-auto">
        <div
          className={`${COLUMNS} sticky top-0 z-10 border-b border-space-700 bg-space-950 pb-1 text-15 uppercase tracking-[0.2em] text-ink-faint`}
        >
          {/*
            Пометка в шапке берёт или отпускает весь список разом — им и начинают, когда
            приказ касается всей империи («выделить все колонии и заложить автолабораторию»).
          */}
          <button
            type="button"
            aria-pressed={allSelected}
            aria-label={allSelected ? t('colonies.deselectAll.aria') : t('colonies.selectAll.aria')}
            title={allSelected ? t('colonies.deselectAll') : t('colonies.selectAll')}
            className={
              'flex h-5 w-5 items-center justify-center border text-14 leading-none '
              + (allSelected
                ? 'border-accent bg-accent/20 text-accent'
                : 'border-space-700 text-transparent hover:border-space-600')
            }
            onClick={() =>
              setSelected(allSelected ? [] : colonies.map((entry) => entry.planet.id))
            }
          >
            ✔
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.colony') })}
            aria-pressed={order === 'name'}
            className={'truncate uppercase tracking-[0.2em] '
              + (order === 'name' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('name')}
          >
            {t('colonies.col.colony')}{order === 'name' ? ' ▾' : ''}
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.colonists') })}
            aria-pressed={order === 'colonists'}
            className={'truncate uppercase tracking-[0.2em] '
              + (order === 'colonists' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('colonists')}
          >
            <span className="inline-flex items-center gap-1">
              <Colonist color="rgb(var(--lamp))" className="h-6 w-[17px]" />
              {order === 'colonists' ? <span aria-hidden>▾</span> : null}
            </span>
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.food') })}
            aria-pressed={order === 'food'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'food' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('food')}
          >
            <span className="inline-flex items-center justify-end gap-1">
              {order === 'food' ? <span aria-hidden>▾</span> : null}
              <ResourceIcon kind="grain" label={t('colonies.col.food')} className="h-6 w-6 text-lamp" />
            </span>
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.production') })}
            aria-pressed={order === 'production'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'production' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('production')}
          >
            <span className="inline-flex items-center justify-end gap-1">
              {order === 'production' ? <span aria-hidden>▾</span> : null}
              <ResourceIcon kind="hammer" label={t('colonies.col.production')} className="h-6 w-6 text-lamp" />
            </span>
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.research') })}
            aria-pressed={order === 'research'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'research' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('research')}
          >
            <span className="inline-flex items-center justify-end gap-1">
              {order === 'research' ? <span aria-hidden>▾</span> : null}
              <ResourceIcon kind="microscope" label={t('colonies.col.research')} className="h-6 w-6 text-lamp" />
            </span>
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.income') })}
            aria-pressed={order === 'income'}
            className={'text-right truncate uppercase tracking-[0.2em] '
              + (order === 'income' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('income')}
          >
            <span className="inline-flex items-center justify-end gap-1">
              {order === 'income' ? <span aria-hidden>▾</span> : null}
              <ResourceIcon kind="coins" label={t('colonies.col.income')} className="h-6 w-6 text-lamp" />
            </span>
          </button>
          <button
            type="button"
            title={t('colonies.sort', { name: t('colonies.col.build') })}
            aria-pressed={order === 'build'}
            className={'truncate uppercase tracking-[0.2em] '
              + (order === 'build' ? 'text-accent' : 'text-ink-faint hover:text-ink-dim')}
            onClick={() => setOrder('build')}
          >
            {t('colonies.col.build')}{order === 'build' ? ' ▾' : ''}
          </button>
        </div>

        {colonies.length === 0 ? (
          <p className="px-2 py-3 text-19 text-ink-faint">
            {t('colonies.none')}
          </p>
        ) : (
          colonies.map(({ planet, systemName }) => (
            <ColonyRow
              key={planet.id}
              planet={planet}
              systemName={systemName}
              color={color}
              hovered={hover === planet.id}
              refused={tripTarget === planet.id && tripBlocked !== null}
              hoverZone={hoverZone}
              source={held?.from.planetId === planet.id}
              held={held?.from.planetId === planet.id ? { job: held.from.job, count: held.count } : null}
              selected={selected.includes(planet.id)}
              credits={self?.credits ?? 0}
              busy={busy}
              onBuy={() => setBuying(planet)}
              onSelect={(range) => toggle(planet.id, range)}
              rowRef={register(planet.id)}
              zoneRef={(job) => registerZone(planet.id, job)}
              onStartDrag={(job, count) => start(planet.id, job, count)}
              onOpen={() => setOpenedColony(planet.id)}
              onOpenBuild={() => setOpenedColony(planet.id, true)}
              onMoveJob={(from, to) => moveJob(planet.id, from, to)}
              onTransfer={() => setTransferFrom(planet)}
            />
          ))
        )}
      </div>

      {/*
        Полоса приказа появляется только с набором — п. 10: пока никто не помечен,
        показывать её нечему, и место в экране она не занимает.
      */}
      {selectedPlanets.length > 0 ? (
        /*
          ДЕЙСТВИЯ НАД ВЫБРАННЫМИ (редизайн, 07.10.2026): заложить общий проект и выкупить стройку
          у тех, кому хватает казны, — самые дешёвые первыми, пока казна не кончится. Выкуп в два
          нажатия: он тратит деньги сразу у многих колоний.
        */
        <div className="mt-2 flex flex-none flex-wrap items-center gap-x-4 gap-y-2 border-t border-space-700 pt-2 text-19">
          <span className={figureLabelClass('value')}>
            {t('colonies.selected')} <Figure accent="value">{selectedPlanets.length}</Figure> {t('colonies.of')}{' '}
            <Figure accent="value">{colonies.length}</Figure>
          </span>
          <button
            type="button"
            className="rounded border border-space-600 bg-space-900 px-3 py-1 hover:border-accent hover:text-accent disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={busy}
            onClick={() => setQueueing(true)}
          >
            {t('colonies.enqueueAll')}
          </button>
          <button
            type="button"
            className={'rounded border px-3 py-1 disabled:cursor-not-allowed disabled:border-space-700 disabled:text-ink-off '
              + (confirmBuy ? 'border-warn text-warn' : 'border-space-600 bg-space-900 hover:border-accent hover:text-accent')}
            disabled={busy || bulk.planets.length === 0}
            title={bulk.planets.length === 0 ? t('colonies.buyAll.none') : undefined}
            onClick={() => {
              if (!confirmBuy) {
                setConfirmBuy(true);
                return;
              }
              setConfirmBuy(false);
              buyAll(bulk.planets);
            }}
          >
            {confirmBuy
              ? t('colonies.buyAll.confirm', { n: bulk.planets.length, cost: bulk.cost })
              : t('colonies.buyAll', { n: bulk.planets.length, cost: bulk.cost })}
          </button>
          <button type="button" className="link-quiet text-ink-dim" onClick={() => setSelected([])}>
            {t('colonies.clearSelection')}
          </button>
        </div>
      ) : null}

      <div className="mt-2 flex-none border-t border-space-700 pt-2 text-17">
        {/*
          Подсказка, ошибка и вести о рейсе — одна строка под итогами, во всю ширину:
          рядом с итогами её обрезало на узком окне, а именно её и читают после переноса.
        */}
        <p className="mt-1 truncate text-16">
          {error ? (
            <ActionError as="span" message={error} />
          ) : notice ? (
            <span className="text-good">{notice}</span>
          ) : (
            <span className="text-ink-faint">
              {t('colonies.hint')}
            </span>
          )}
        </p>
      </div>

      {/* Приказ стройки набору колоний — п. 10: список общего им всем. */}
      {queueing ? (
        <ColoniesQueueDialog
          planets={selectedPlanets}
          saving={busy}
          error={error}
          onEnqueue={enqueueAll}
          onClose={() => setQueueing(false)}
        />
      ) : null}

      {/*
        Выкуп стройки — то же окошко, что и на экране колонии (п. 10): цену, вложенное и
        предупреждение о спешке оно считает по тем же числам, и второго такого окна в игре
        быть не должно.
      */}
      {buying?.colony ? (
        <ColonyBuyDialog
          colony={buying.colony}
          credits={self?.credits ?? 0}
          saving={busy}
          error={error}
          onBuy={() => buy(buying)}
          onClose={() => setBuying(null)}
        />
      ) : null}

      {/* Рейс на несколько жителей — тот же диалог, что и на экране колонии (п. 4.1.1). */}
      {transferFrom ? (
        <TransferDialog from={transferFrom} onClose={() => setTransferFrom(null)} />
      ) : null}

      {/* Группа на руках: летит за курсором и не перехватывает события. */}
      {held ? (
        <HeldColonists held={held} color={color} className="h-[20px] w-[14px]"
                       note={tripNote} refused={tripBlocked !== null} />
      ) : null}
    </div>
  );
}
