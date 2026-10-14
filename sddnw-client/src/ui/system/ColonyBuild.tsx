import { useEffect, useRef, useState, type ReactNode } from 'react';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { ColonyBuildScreen } from './ColonyBuildScreen';
import { ColonyBuyDialog } from './ColonyBuyDialog';
import { buyState } from '../colonies/BuyCoin';
import { ColonyQueue } from './ColonyQueue';
import { turnsLeft } from './colonyRules';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { KeyFigure } from '../KeyFigure';
import { ActionError } from '../feedback/ActionError';

/**
 * Стройка колонии — п. 10, правой панелью экрана колонии MOO II.
 *
 * В оригинале это узкий сегмент справа: рамка с изображением того, что строится, срок
 * под ней и кнопки CHANGE и BUY. Изображений в игре пока нет, поэтому в рамке название
 * стройки; кнопки обе — «сменить» и «купить», и вторая показывает цену выкупа прямо на
 * себе: в MOO II её узнают только из окна подтверждения, а лишнее нажатие ради числа —
 * плохой обмен.
 *
 * Колония строит один проект за раз. Здание становится доступным после того, как изучена
 * его технология, и возводится на планете один раз. Дома и товары доступны с первого хода,
 * зданиями не становятся и не заканчиваются: производство уходит в рождаемость и в казну.
 *
 * Сам выбор стройки живёт в отдельной сцене (`ColonyBuildScreen`) — окне стройки MOO II
 * во весь экран: слева постройки планеты, справа корабли, в середине описание выбранного
 * и очередь колонии.
 */
export function ColonyBuild({ planet, own }: { planet: Planet; own: boolean }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const updatePlanet = useGameStore((state) => state.updatePlanet);
  const setLobby = useGameStore((state) => state.setLobby);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  // Казна нужна окошку выкупа: по ней видно, хватает ли на покупку.
  const credits = useGameStore((state) => selectSelf(state)?.credits ?? 0);
  const colony = planet.colony;

  const [saving, setSaving] = useState(false);
  /** Цепочка запросов очереди: они правят её по номерам строк и обгонять друг друга не должны. */
  const pending = useRef<Promise<unknown>>(Promise.resolve());
  const [picking, setPicking] = useState(false);
  /**
   * Выбранная строка очереди — п. 10: щелчок выбирает, второй щелчок по другой строке
   * переставляет выбранное туда. Правило одно на оба экрана, поэтому состояние есть и у
   * узкой панели, хотя клавиш (R и D) у неё нет.
   */
  const [selectedQueue, setSelectedQueue] = useState<number | null>(null);
  const [buying, setBuying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Пропущенное из шаблона стройки — пункт 97; гаснет со следующим действием.
  useT();

  // Переход к другой планете закрывает диалоги: они про стройку прежней колонии.
  // Открытая ради стройки (щелчок по ней в списке колоний) колония встаёт сразу с окном стройки.
  useEffect(() => {
    setPicking(useGameStore.getState().colonyBuildDirect);
    setBuying(false);
    setSelectedQueue(null);
    setError(null);
  }, [planet.id]);

  if (!colony) {
    return null;
  }

  /**
   * Любое действие над стройкой отвечает планетой целиком: у колонии от смены проекта
   * меняются и доход, и прирост. Поэтому все они идут одной дорогой.
   */
  const act = (call: () => Promise<Planet>, onDone?: () => void) => {
    if (!game || !credentials) {
      return;
    }
    setSaving(true);
    setError(null);
    call()
      .then((updated) => {
        updatePlanet(updated);
        onDone?.();
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };

  const choose = (project: ColonyProject, keepCurrent?: boolean) =>
    act(
      () => gameApi.setProject(game!.id, planet.id, credentials!.accessToken, project.code, keepCurrent),
      // Перестановка перетаскиванием окна не закрывает: игрок ещё правит очередь.
      () => {
        if (!keepCurrent) {
          setPicking(false);
        }
      },
    );

  /**
    * В очередь — п. 10. `top` ставит проект первым: так кладёт щелчок по списку стройки,
    * а кнопка «в конец очереди» дописывает в хвост.
    */
  const enqueue = (project: ColonyProject, top?: boolean, at?: number) =>
    act(async () => {
      const placed = await gameApi.enqueueProject(game!.id, planet.id, credentials!.accessToken,
        project.code, top);
      /*
        Брошен на строку очереди — встаёт на её место, а строка и всё ниже сдвигаются вниз
        (09.10.2026, баг хозяина проекта: биосфера строится, звёздная база ждёт, колониальный
        корабль, брошенный между ними, уходил в хвост). Сервер кладёт в конец, перестановка —
        тем же запросом, что и щелчковая вставка. Колония на бесконечной стройке ставит проект
        на стапель, а не в очередь, — тогда переставлять нечего.
      */
      const queue = placed.colony?.queue ?? [];
      const last = queue.length - 1;
      if (at === undefined || last <= at || queue[last]?.code !== project.code) {
        return placed;
      }
      return gameApi.moveInQueue(game!.id, planet.id, credentials!.accessToken, last, at);
    });

  /**
   * Выкуп идёт своей дорогой: он тратит казну, а казна — состояние игрока, и клиент
   * сам её не пересчитывает. Не забрать состав партии свежим — и в правой панели
   * останется прежняя сумма (те же грабли, что с грузовиками при перевозке жителей).
   */
  const buy = () => {
    if (!game || !credentials) {
      return;
    }
    setSaving(true);
    setError(null);
    gameApi
      .buyProject(game.id, planet.id, credentials.accessToken)
      .then(async (updated) => {
        updatePlanet(updated);
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        setBuying(false);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };

  const buildMode = (mode: { repeatBuild?: boolean; autoBuild?: boolean }) =>
    act(() => gameApi.setBuildMode(game!.id, planet.id, credentials!.accessToken, mode));

  /**
   * Снять текущую стройку (07.10.2026): на стапель встаёт первое из очереди — тот же запрос,
   * что у «▸», без возврата прежней в очередь; при пустой очереди — товары. Окно стройки при
   * этом не закрывается: игрок правит очередь.
   */
  const dropCurrent = () => {
    const next = colony.queue[0];
    act(() => gameApi.setProject(game!.id, planet.id, credentials!.accessToken,
      next ? next.code : 'TRADE_GOODS'));
  };

  const dequeue = (index: number) =>
    act(() => gameApi.removeFromQueue(game!.id, planet.id, credentials!.accessToken, index));

  /**
   * Порядок очереди меняется СРАЗУ, а ответ сервера догоняет — п. 10.
   *
   * Прежде каждое движение стрелкой уходило на сервер и на время ответа гасило всю
   * очередь: поднять пятый проект первым стоило четырёх нажатий с четырьмя замираниями.
   * Между тем переставить список клиент умеет и сам — правило тут простое, а последнее
   * слово всё равно за сервером: его ответ заменяет планету целиком.
   *
   * Запросы идут ЦЕПОЧКОЙ, а не вперегонки: сервер правит очередь по номерам строк, и
   * два движения, посчитанные от одного состояния, переставили бы не то. Сорвалось —
   * возвращаем ту планету, что была до первой догадки: врать игроку о состоянии его
   * колонии хуже, чем мигнуть списком.
   */
  const move = (index: number, toIndex: number) => {
    if (!game || !credentials || index === toIndex) {
      return;
    }
    const before = planet;
    const reordered = [...colony.queue];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(toIndex, 0, moved);
    updatePlanet({ ...planet, colony: { ...colony, queue: reordered } });
    setError(null);
    pending.current = pending.current
      .then(() => gameApi.moveInQueue(game.id, planet.id, credentials.accessToken, index, toIndex))
      .then((updated) => updatePlanet(updated))
      .catch((failure: Error) => {
        setError(failure.message);
        updatePlanet(before);
      });
  };

  return (
    <section className="flex min-h-0 flex-1 flex-col border border-space-700 bg-space-950/60">
      <div className="border-b border-space-800 px-2 py-1 text-20 uppercase tracking-[0.2em] text-ink-dim">
        {t('build.title')}
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-2 p-2">
        {/*
          Место под изображение стройки — в MOO II оно занимает верх этой панели.
          Пока в рамке название: она уже размечена, и с появлением спрайтов останется
          заменить её содержимое.

          Рамка занимает остаток высоты панели, а не держит пропорцию 4:3: с крупными
          надписями пропорция уводила стройку за нижний край экрана — панель считала
          высоту от своей ширины, а не от того, сколько места ей осталось.
        */}
        {/*
          ТЕКУЩАЯ СТРОЙКА — КРУПНОЙ КАРТОЧКОЙ (редизайн колонии, 07.10.2026, решение хозяина
          проекта): название, главное число экрана — срок (пункт 13), вложенное с полоской и
          выкуп. Очередь — под ней. Прежде название стояло в пустой рамке во всю высоту, а
          вложенное — отдельной строкой под ней.
        */}
        <div className="flex flex-none flex-col gap-2 rounded border border-space-600 bg-space-900/70 p-3">
          <span className="break-words text-24 leading-tight text-ink-bright">
            {colony.projectName ?? t('build.nothing')}
          </span>
          <BuildHeadline colony={colony} />
          <span className={figureLabelClass('value')}>{progress(colony)}</span>
          {colony.projectCost ? (
            <div className="h-1.5 w-full bg-space-700" aria-hidden>
              <div className="h-full bg-accent"
                   style={{ width: `${Math.min(100, (100 * colony.projectPoints) / colony.projectCost)}%` }} />
            </div>
          ) : null}
        </div>

        <ColonyQueue
          colony={colony}
          own={own}
          busy={saving}
          onRemove={dequeue}
          onMove={move}
          onPromote={choose}
          onDropCurrent={dropCurrent}
          selected={selectedQueue}
          onSelect={setSelectedQueue}
          credits={credits}
          onBuy={() => setBuying(true)}
        />

        {/*
          Подсказки с действием — backlog-promo, пункт 18. Новичок оставлял родной мир на
          товарах, хотя в системе была свободная планета, а колониальная база не требует
          технологий; и копил казну, не зная, что стройку можно выкупить. Обе строки — только
          когда это правда сейчас, и каждая сразу делает то, о чём говорит.
        */}
        {own && !saving ? <BuildNudges colony={colony}
          onSettle={(base) => choose(base)} /> : null}

        {own ? (
          /*
            РЯД ЗНАЧКОВ (редизайн колонии, 07.10.2026): сменить, купить, повтор очереди и
            автострой — то, что прежде было двумя ссылками здесь и двумя флажками только в окне
            стройки. Повтор и автострой — переключатели: включённый горит янтарём и вжат.
            Выкуп гаснет по тому же правилу, что монетка у стройки (`buyState`).
          */
          // Ряд кнопок — на всю ширину панели, кнопки вдвое крупнее (09.10.2026, решение хозяина проекта).
          <span className="mt-auto grid w-full auto-cols-fr grid-flow-col gap-2">
            <ToolButton label={t('build.change')} onClick={() => setPicking(true)}>
              <path d="M4 20h4L19 9l-4-4L4 16Z" /><path d="m13.5 6.5 4 4" />
            </ToolButton>
            <ToolButton
              label={buyState(colony, credits) === 'ready'
                ? t('build.buy.title', { n: colony.buyCost ?? 0 })
                : buyState(colony, credits) === 'poor'
                  ? t('build.buy.noCredits', { n: colony.buyCost ?? 0, have: credits })
                  : buyState(colony, credits) === 'paid'
                    ? t('build.buy.paid')
                    : t('build.buy.nothing')}
              disabled={buyState(colony, credits) !== 'ready'}
              onClick={() => setBuying(true)}
              caption={colony.buyCost === undefined ? undefined : t('common.credits', { n: colony.buyCost })}
            >
              <ellipse cx="12" cy="7" rx="7" ry="2.6" /><path d="M5 7v5c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6V7" /><path d="M5 12v5c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6v-5" />
            </ToolButton>
            <ToolButton label={t('buildScreen.repeat') + ' — ' + t('buildScreen.repeat.title')} pressed={colony.repeatBuild}
                        onClick={() => buildMode({ repeatBuild: !colony.repeatBuild })}>
              <path d="M4 12a8 8 0 0 1 13.7-5.6L20 8.5" /><path d="M20 4v4.5h-4.5" />
              <path d="M20 12a8 8 0 0 1-13.7 5.6L4 15.5" /><path d="M4 20v-4.5h4.5" />
            </ToolButton>
            <ToolButton label={t('buildScreen.auto') + ' — ' + t('buildScreen.auto.title')} pressed={colony.autoBuild}
                        onClick={() => buildMode({ autoBuild: !colony.autoBuild })}>
              <path d="M5 19 12 5l7 14" /><path d="M8 13h8" />
            </ToolButton>
          </span>
        ) : (
          <p className="mt-auto text-20 text-ink-faint">{t('build.ownerOnly')}</p>
        )}
        {error ? <ActionError className="text-20" message={error} /> : null}
      </div>

      {picking ? (
        <ColonyBuildScreen
          planet={planet}
          colony={colony}
          saving={saving}
          error={error}
          onChoose={choose}
          onBuildMode={buildMode}
          onEnqueue={enqueue}
          onRemove={dequeue}
          onDropCurrent={dropCurrent}
          onMove={move}
          onBuy={() => setBuying(true)}
          // Пришли из списка колоний ради стройки — туда и возвращаемся.
          onClose={() => (useGameStore.getState().colonyBuildDirect ? setOpenedColony(null) : setPicking(false))}
        />
      ) : null}

      {/*
        Окно выкупа — ПОСЛЕ окна стройки: зовут его и оттуда (монетка в очереди), а при
        равном слое выше оказывается тот, кто позже в разметке.
      */}
      {buying ? (
        <ColonyBuyDialog
          colony={colony}
          credits={credits}
          saving={saving}
          error={error}
          onBuy={buy}
          onClose={() => setBuying(false)}
        />
      ) : null}
    </section>
  );
}

/**
 * Кнопка-значок панели стройки — редизайн колонии (07.10.2026). Подпись — во всплывающей
 * подсказке и для читалки; у выкупа рядом цена, потому что её решают глазами.
 */
function ToolButton({ label, children, onClick, disabled = false, pressed, caption }: {
  label: string;
  children: ReactNode;
  onClick: () => void;
  disabled?: boolean;
  /** Переключатель: включён — горит янтарём и вжат. */
  pressed?: boolean;
  caption?: string;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className={'flex h-20 items-center justify-center gap-2 rounded border px-3 text-24 '
        + (pressed ? 'border-accent bg-space-700 text-accent shadow-inner ' : 'border-space-600 bg-space-900 text-ink hover:border-accent hover:text-accent ')
        + 'disabled:cursor-not-allowed disabled:border-space-700 disabled:text-ink-off'}
    >
      <svg viewBox="0 0 24 24" aria-hidden className="h-10 w-10 flex-none" fill="none" stroke="currentColor"
           strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
        {children}
      </svg>
      {caption ? <span>{caption}</span> : null}
    </button>
  );
}

/**
 * Главное число стройки — backlog-promo, пункт 13: у проекта — ходов до готовности, у
 * бесконечной стройки (дома, товары) срока нет, и главным становится её отдача за ход.
 * Без производства срока нет тоже — тогда число красное: это и есть ответ.
 */
function BuildHeadline({ colony }: { colony: Colony }) {
  if (!colony.projectCode) {
    return null;
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? <KeyFigure label={t('key.housing')} value={`+${colony.housingBonusPercent ?? 0}%`} />
      : <KeyFigure label={t('key.tradeGoods')} value={`+${Math.floor(colony.production / 2)}`} />;
  }
  const turns = colony.production > 0 ? turnsLeft(colony) : null;
  return turns === null
    ? <KeyFigure label={t('key.turnsToBuild')} value="—" tone="danger" double />
    // Оплаченное целиком (выкуп, возврат от продажи) достраивается в конце ЭТОГО хода — и это
    // тоже один ход, а не ноль.
    : <KeyFigure label={t('key.turnsToBuild')} value={String(Math.max(1, turns))} double />;
}

/**
 * Сколько осталось: у здания — вложенное и срок, у домов и товаров — их отдача,
 * потому что они не заканчиваются.
 */
const progress = (colony: Colony): ReactNode => {
  const value = (n: number) => <Figure accent="value">{n}</Figure>;
  if (!colony.projectCode) {
    return tf('build.wasted', { n: value(colony.production) });
  }
  if (colony.projectCost === undefined) {
    return colony.projectCode === 'HOUSING'
      ? tf('build.housing', { n: value(colony.housingBonusPercent ?? 0) })
      : tf('build.tradeGoods', { n: value(Math.floor(colony.production / 2)) });
  }
  if (colony.production <= 0) {
    return tf('build.noProduction', { points: value(colony.projectPoints), cost: value(colony.projectCost) });
  }
  // Срок здесь не повторяется: он главное число панели и стоит крупно над этой строкой
  // (BuildHeadline, пункт 13), а два одинаковых числа рядом спорили бы, какое читать.
  // До половины — «сделано», с половины — «осталось» (пункт 52).
  // Коротко «18 / 60 производства» (09.10.2026, решение хозяина проекта): срок стоит крупно
  // над строкой, а здесь — сколько вложено из скольких.
  return tf('build.progressShort', { points: value(colony.projectPoints), cost: value(colony.projectCost) });
};

/**
 * Что колонии стоит сделать прямо сейчас — backlog-promo, пункт 18.
 *
 * Колония на товарах или домах при свободной планете в системе — её ждёт колониальная база;
 * стройка, которую казна покрывает целиком, — её можно выкупить. Это не правила, а
 * подсказки по уже показанным числам: база видна в `available` (сервер кладёт её туда,
 * только пока есть куда селиться), а «по карману» — тот же `buyState`, что у монетки.
 */
function BuildNudges({
  colony,
  onSettle,
}: {
  colony: Colony;
  onSettle: (base: ColonyProject) => void;
}) {
  const idle = colony.projectCode === 'TRADE_GOODS' || colony.projectCode === 'HOUSING';
  const base = colony.available.find((project) => project.code === 'COLONY_BASE');
  const queued = colony.queue.some((project) => project.code === 'COLONY_BASE');
  if (idle && base && !queued) {
    return (
      <button type="button" className="link-quiet text-left text-16 text-warn" onClick={() => onSettle(base)}>
        {t('build.nudge.colonyBase')}
      </button>
    );
  }
  // Подсказки «казны хватает — выкупите» больше нет (09.10.2026, решение хозяина проекта): о
  // выкупе говорит сама монетка в ряду кнопок, с ценой на ней.
  return null;
}
