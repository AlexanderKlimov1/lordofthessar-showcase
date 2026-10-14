import { useState, type ReactNode } from 'react';
import { BuyCoin } from '../colonies/BuyCoin';
import type { Colony, ColonyProject } from '../../api/types';
import { queueEta, queueStalled, turnsLeft } from './colonyRules';
import { t, tf } from '../../i18n';
import { Figure, TurnsFigure } from '../Figure';
import { figureLabelClass } from '../accent';
import { justTouched } from '../touch';

/**
 * Очередь стройки колонии — п. 10, ОДНИМ списком на оба экрана.
 *
 * Показывают её двое: узкая панель стройки на экране колонии (`ColonyBuild`) и окно
 * стройки во весь экран (`ColonyBuildScreen`). Раньше у каждого был свой список, и они
 * разошлись в главном: на экране колонии текущая стройка стояла ПЕРВОЙ СТРОКОЙ той же
 * очереди, а в окне стройки — отдельной панелью, и очередь там начиналась сразу со
 * второго дела. Игрок переучивался при переходе между экранами, хотя показывают они одно
 * и то же. Теперь список один, а экраны задают ему только размер.
 *
 * <b>Строится сейчас — верхняя строка списка</b>, и в оригинале так же: очередь там не
 * что-то рядом со стройкой, а её продолжение. Стрелок и крестика у этой строки нет —
 * стройку меняют кнопкой «сменить», а убрать её из списка нельзя вовсе: пустой стройки
 * у колонии не бывает (`ProductionPhase.advanceQueue` сменяет её товарами).
 *
 * <b>Знак текущей стройки — точка, а не «▸».</b> «▸» занят кнопкой «строить сейчас» в
 * строках очереди, и одним значком нельзя обозначать и состояние, и действие: в прежнем
 * списке та же стрелка была то меткой, то кнопкой — смотря по строке.
 *
 * <b>Повторы схлопываются в одну строку</b> — «Фрегат ×3»: очередь держит семь мест, и
 * три из них, занятые одним и тем же кораблём, съедали половину списка, ничего не сообщая
 * сверх того, что сообщает число. Стрелки двигают ВЕСЬ повтор целиком (это по-прежнему
 * один запрос: переставляется соседняя строка, а не каждая копия), крестик убирает ОДНУ
 * копию, а «+» у последней строки добавляет ещё одну.
 *
 * Перетаскивать повтор нельзя — только одиночные строки: перенос трёх копий разом
 * потребовал бы трёх запросов, посчитанных один от другого, а стрелки делают то же самое
 * одним. Это намеренный предел, а не недосмотр.
 *
 * <b>Строку выбирают, и выбранную правят с клавиатуры</b> — <b>D</b> убирает её из
 * очереди, <b>R</b> переключает повтор очереди ({@code useBuildHotkeys}). Без выбора D не
 * делает ничего: удалять «последнее попавшееся» по нажатию буквы — плохой обмен.
 *
 * <b>ДВА ЩЕЛЧКА ПЕРЕСТАВЛЯЮТ СТРОКУ</b> — решение хозяина проекта (30.09.2026), и так же
 * это сделано в оригинале. Первый щелчок по строке её ВЫБИРАЕТ, второй — по ДРУГОЙ строке
 * — вставляет выбранное на её место, а занимавшее это место опускается на строку вниз.
 * Щелчок по той же строке выбор снимает. Прежде порядок правили только стрелками (шаг за
 * нажатие, и поднять пятое дело первым стоило четырёх) и перетаскиванием, которое требует
 * вести мышь с зажатой кнопкой через весь список.
 *
 * <b>Схлопнутый повтор так не переставляется</b>: в строке «Фрегат ×3» три места очереди,
 * и одним запросом их не перенести — тот же предел, по которому повтор нельзя тащить
 * мышью. Щелчок по другой строке тогда просто выбирает её, а двигают повтор стрелками:
 * они переставляют СОСЕДНЮЮ строку и потому справляются одним запросом.
 *
 * <b>Мышью очередь и пополняют, и чистят.</b> Проект из списка стройки перетаскивают на
 * очередь — он встаёт в неё; строку очереди тащат ПРОЧЬ из списка — она из очереди
 * уходит. Обе дороги остаются и кнопками («в очередь», «✕»): перетаскивание — это
 * быстрее, а не «единственный способ».
 *
 * <b>В широком окне очередь перетаскивают мышью.</b> Стрелки двигают на одну позицию, и
 * поднять пятый проект первым стоило четырёх нажатий с четырьмя ответами сервера. В узкой
 * панели колонии стрелки остаются: там строка в палец шириной и тащить некуда, — а в окне
 * во весь экран места хватает. Стрелки при этом никуда не делись и в широком окне: мышь
 * есть не у всех действий, а привычка — у всех.
 *
 * <b>Срок — ОЖИДАНИЕ, а не длина проекта</b> ({@link queueEta}): остаток текущей стройки
 * плюс всё, что стоит впереди. Прежде каждая строка называла свой срок в отрыве от
 * остальных, и числа не складывались ни во что — а спрашивают у очереди именно «когда».
 */
/** Выделение текущей стройки — верхней строки списка; строки очереди выделяются своим номером. */
export const CURRENT = -1;

export function ColonyQueue({
  colony,
  own,
  busy,
  wide,
  onRemove,
  onMove,
  onPromote,
  selected,
  onSelect,
  onDropProject,
  onDropCurrent,
  credits,
  onBuy,
}: {
  colony: Colony;
  own: boolean;
  /** Ответ сервера ещё не пришёл: вторым нажатием очередь можно перепутать. */
  busy: boolean;
  /** Окно стройки во весь экран: там крупнее кегль и шире строка. */
  wide?: boolean;
  onRemove: (index: number) => void;
  onMove: (index: number, toIndex: number) => void;
  /**
   * Взяться за проект очереди сейчас: он уходит из очереди на стапель. `keepCurrent` —
   * прежняя стройка не пропадает, а встаёт первой в очередь: так кладут строку, брошенную
   * на текущую стройку.
   */
  onPromote: (project: ColonyProject, keepCurrent?: boolean) => void;
  /** Выбранная строка очереди: её правят с клавиатуры. */
  selected?: number | null;
  onSelect?: (index: number | null) => void;
  /**
   * Проект, притащенный из списка стройки. `end` — в конец очереди (бросок на очередь),
   * `top` — первым в очередь, `current` — на стапель, а прежняя стройка первой в очередь
   * (бросок на текущую стройку, см. `dropFront`), номер — на место этой строки очереди, а
   * она и всё ниже сдвигаются вниз (бросок на строку).
   */
  onDropProject?: (code: string, place: 'end' | 'top' | 'current' | number) => void;
  /**
   * Снять текущую стройку (07.10.2026): на стапель встаёт первое из очереди, а пустая
   * очередь — товары. Без этого стройку, которую игрок передумал строить, можно было лишь
   * подменить другой, а дома и товары в очереди за ней не начались бы никогда.
   */
  onDropCurrent?: () => void;
  /** Казна империи — по ней монетка выкупа знает, хватает ли денег (п. 10). */
  credits?: number;
  /**
   * Выкупить текущую стройку — п. 10: монетка у верхней строки, та же, что в списке
   * колоний (`BuyCoin`). Подтверждение спрашивает тот, кто её показывает; без обработчика
   * монетки нет.
   */
  onBuy?: () => void;
}) {
  const queue = colony.queue;
  const left = turnsLeft(colony);
  /** Что тащат и куда целятся: номера строк, обе — только на время перетаскивания. */
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);

  const drop = (to: number) => {
    if (dragging !== null && dragging !== to) {
      onMove(dragging, to);
    }
    setDragging(null);
    setOver(null);
  };

  /*
    Оплаченная стройка — выкупом или с избытком вложенного — с места не сдвигается: вложенное
    принадлежит колонии и ушло бы в новый проект, а купленное снова стало бы недостроенным.
  */
  const paid = colony.projectCost !== undefined && colony.projectPoints >= colony.projectCost;
  const PROJECT = 'application/x-sddnw-project';

  /**
   * Бросок на текущую стройку или над ней — «на первое место» (06.10.2026). Новое здание из
   * списка и строка очереди встают на стапель, а прежняя стройка — первой в очередь; у
   * оплаченной стройки на стапель не встаёт ничего, и брошенное становится первым в очереди.
   * Прежде бросок проекта из списка сюда не принимал никто, кроме очереди целиком, и
   * проект уходил в её конец — а целились в начало.
   */
  const dropFront = (event: React.DragEvent) => {
    const code = event.dataTransfer.getData(PROJECT);
    const from = dragging;
    setDragging(null);
    setOver(null);
    if (code && onDropProject) {
      event.preventDefault();
      event.stopPropagation();
      onDropProject(code, paid ? 'top' : 'current');
      return;
    }
    if (from === null) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    const project = queue[from];
    if (!project) {
      return;
    }
    if (!paid) {
      onPromote(project, true);
    } else if (from !== 0) {
      onMove(from, 0);
    }
  };
  const frontOver = (event: React.DragEvent) => {
    if (dragging !== null || event.dataTransfer.types.includes(PROJECT)) {
      event.preventDefault();
      setOver(-1);
    }
  };

  /**
   * Перетащили что-то НА очередь.
   *
   * Из списка стройки приходит код проекта — он встаёт в очередь; изнутри очереди приходит
   * номер строки, и это перестановка, которую разбирает сама строка. Поэтому здесь
   * разбирается только первый случай: очередь как целое принимает пополнение.
   */
  const dropHere = (event: React.DragEvent) => {
    const code = event.dataTransfer.getData(PROJECT);
    if (code && onDropProject) {
      event.preventDefault();
      onDropProject(code, 'end');
    }
    // Строка очереди, брошенная в промежуток между строками, остаётся на месте: бросок
    // внутри очереди — не «мимо», и убирать её за промах на полточки нельзя.
    if (dragging !== null) {
      event.preventDefault();
    }
    setDragging(null);
    setOver(null);
  };
  // Снимать нечего, когда колония и так на товарах без очереди: туда снятие и привело бы.
  const canDropCurrent = own && onDropCurrent !== undefined && Boolean(colony.projectCode)
    && !(colony.projectCode === 'TRADE_GOODS' && queue.length === 0);
  const stalled = queueStalled(colony) && queue.length > 0;
  const runs = collapse(queue);
  /*
    Выбранное можно вставить в другое место только если это ОДИНОЧНАЯ строка: у
    схлопнутого повтора мест в очереди несколько, и перенести их разом одним запросом
    нечем — тот же предел, что и у перетаскивания.
  */
  const movable = selected !== null && selected !== undefined
    && runs.some((run) => run.first === selected && run.count === 1);
  // Узкой панели колонии длинная подпись не по росту: там «через 14», а в окне во весь
  // экран — «через 14 ходов». Значит подпись одно и то же, и объясняет её одна подсказка.
  const etaKey = wide ? 'build.eta' : 'build.eta.short';

  return (
    <div
      className={wide ? '' : 'border-t border-space-800 pt-1'}
      onDragOver={(event) => {
        // Разрешаем бросок, только когда тащат проект: иначе очередь принимала бы что
        // угодно, включая текст со страницы.
        if (dragging !== null || event.dataTransfer.types.includes(PROJECT)) {
          event.preventDefault();
        }
      }}
      onDrop={dropHere}
    >
      {/*
        Заголовок без счётчика мест (09.10.2026, решение хозяина проекта): «2 из 10» было лишним —
        о полной очереди скажет строка состояния, а не число в каждом взгляде.
      */}
      <div className={`text-18 uppercase tracking-[0.2em] text-ink-faint`}
           onDragOver={frontOver} onDrop={dropFront}>
        {t('build.queue')}
      </div>

      <ol className="mt-1 flex flex-col gap-0.5">
        <li
          /*
            Текущая стройка тоже принимает брошенную строку очереди (01.10.2026): бросок сюда —
            «на первое место». Строка встаёт на стапель, а прежняя стройка сдвигается первой
            в очередь. Прежде бросок сюда не принимал никто, браузер считал его броском МИМО
            очереди, и строка не вставала первой, а исчезала — «бросок мимо» значит «убрать».
          */
          onDragOver={frontOver}
          onDragLeave={() => setOver((now) => (now === -1 ? null : now))}
          onDrop={dropFront}
          /*
            Щелчок по текущей стройке, когда выбрана строка очереди, — та же «вставка на первое
            место», что и бросок сюда: щелчки и перетаскивание обязаны уметь одно и то же. У
            оплаченной стройки выбранное встаёт первым в очереди.
          */
          onClick={() => {
            if (!own || busy) {
              return;
            }
            /*
              Текущая стройка выделяется, как любая строка (09.10.2026, баг хозяина проекта): иначе
              её не убрать клавишей D. Выделение у неё — номер −1 (`CURRENT`).
            */
            if (selected === null || selected === undefined || selected === CURRENT) {
              onSelect?.(selected === CURRENT ? null : CURRENT);
              return;
            }
            if (!movable) {
              onSelect?.(CURRENT);
              return;
            }
            const project = queue[selected];
            onSelect?.(null);
            if (!project) {
              return;
            }
            if (!paid) {
              onPromote(project, true);
            } else if (selected !== 0) {
              onMove(selected, 0);
            }
          }}
          title={canDropCurrent ? t('build.dropCurrent.title') : undefined}
          className={'flex items-baseline gap-2 ' + (own ? 'cursor-pointer ' : '')
            + (selected === CURRENT ? 'bg-space-800 ' : '')
            + (over === -1 ? (paid ? 'border-b border-accent' : 'border-t border-accent') : '')}
        >
          {/*
            Оплаченная стройка сереет, как недоступная строка, и подписана: место за ней, а
            брошенное сюда встаёт второй позицией — первым в очереди.
          */}
          <span className={'w-4 shrink-0 text-right text-18 ' + (paid ? 'text-ink-faint' : 'text-accent')}>●</span>
          <span className={'min-w-0 flex-1 break-words text-20 leading-tight '
                + (paid ? 'text-ink-faint' : 'text-ink-bright')}
                title={paid ? t('build.paid.title') : undefined}>
            {colony.projectName ?? t('build.nothing')}
            {paid ? <span className="ml-2 text-14 text-ink-faint">{t('build.paid')}</span> : null}
          </span>
          {/*
            Вложенное — числом и с объяснением: накопленное производство остаётся КОЛОНИИ,
            а не проекту (`ColonyService.setProject` его не трогает). Пока об этом молчали,
            смены стройки боялись — казалось, что вложенное пропадёт.
          */}
          {/* В узкой панели вложенное уже стоит строкой над очередью («13 / 582 производства»), а
              место нужно сроку — он вдвое крупнее (12.10.2026). */}
          {colony.projectCost === undefined || !wide ? null : (
            <span className={'shrink-0 ' + figureLabelClass('value')}
                  title={t('build.invested.title')}>
              {tf('build.invested', {
                points: <Figure accent="value">{colony.projectPoints}</Figure>,
                cost: <Figure accent="value">{colony.projectCost}</Figure>,
              })}
              {' ·'}
            </span>
          )}
          <span className={'shrink-0 ' + figureLabelClass('value')}>
            {left === null
              ? colony.projectCost === undefined ? t('build.endless') : '—'
              : tf('build.turns', { n: <TurnsFigure accent="value">{left}</TurnsFigure> })}
          </span>
          {/*
            Выкуп текущей стройки — та же монетка, что в строке списка колоний: четыре её
            состояния (можно, не хватает казны, уже оплачено, выкупать нечего) читаются
            одинаково на обоих экранах.
          */}
          {own && onBuy ? (
            <span className="self-center">
              <BuyCoin colony={colony} credits={credits ?? 0} colonyName={colony.projectName ?? ''}
                       busy={busy} onBuy={onBuy} />
            </span>
          ) : null}
          {canDropCurrent ? (
            <span className="shrink-0 text-18">
              {/* Щелчок по крестику не должен заодно стать щелчком по строке: тот вставил бы
                  выбранное на первое место. */}
              <span onClick={(event) => event.stopPropagation()}>
                <QueueButton label="✕" title={t('build.dropCurrent.title')} disabled={busy}
                             onClick={() => onDropCurrent?.()} />
              </span>
            </span>
          ) : null}
        </li>

        {runs.map((run) => (
          <li
            key={`${run.project.code}-${run.first}`}
            draggable={own && !busy && run.count === 1}
            onDragStart={(event) => {
              setDragging(run.first);
              // Номер строки — чтобы её можно было вынести из очереди, бросив мимо.
              event.dataTransfer.setData('application/x-sddnw-queue', String(run.first));
              event.dataTransfer.effectAllowed = 'move';
            }}
            onDragEnd={(event) => {
              // Бросок МИМО очереди — это «убрать»: `dropEffect` там остаётся `none`,
              // потому что принять его было некому. Так очередь чистят мышью, не целясь
              // в крестик размером с букву.
              if (event.dataTransfer.dropEffect === 'none' && dragging === run.first) {
                onRemove(run.first);
              }
              setDragging(null);
              setOver(null);
            }}
            onDragOver={(event) => { event.preventDefault(); setOver(run.first); }}
            onDrop={(event) => {
              event.preventDefault();
              // Проект из списка, брошенный на строку, встаёт на её место, а она — ниже.
              const code = event.dataTransfer.getData(PROJECT);
              if (code && onDropProject) {
                event.stopPropagation();
                setDragging(null);
                setOver(null);
                onDropProject(code, run.first);
                return;
              }
              drop(run.first);
            }}
            /*
              Щелчок: выбрать, снять выбор или ВСТАВИТЬ выбранное сюда — п. 10. Порядок
              разбора именно такой, потому что «вставить» возможно лишь тогда, когда
              выбрано что-то другое и это что-то — одиночная строка.
            */
            onClick={() => {
              if (!own || busy) {
                return;
              }
              if (selected === null || selected === undefined) {
                onSelect?.(run.first);
                return;
              }
              if (selected === run.first) {
                onSelect?.(null);
                return;
              }
              if (!movable) {
                // Выбран схлопнутый повтор: переставить его одним запросом нечем, и
                // щелчок делает то единственное, что имеет смысл, — выбирает эту строку.
                onSelect?.(run.first);
                return;
              }
              onMove(selected, run.first);
              onSelect?.(null);
            }}
            // Скрытое действие названо (field-backlog, пункт 72): одиночную строку можно
            // утащить прочь — и она уйдёт из очереди.
            title={t(selected === null || selected === undefined || selected === run.first
              ? (own && run.count === 1 ? 'build.queue.pick.dragTitle' : 'build.queue.pick.title')
              : movable ? 'build.queue.insert.title' : 'build.queue.pick.title')}
            className={
              'flex items-baseline gap-2 '
              + (own && !busy && run.count === 1 ? 'cursor-grab ' : '')
              + (selected === run.first ? 'bg-space-800 ' : '')
              + (dragging === run.first ? 'opacity-40 ' : '')
              // Куда упадёт: строку под указателем подчёркиваем, а не подсвечиваем целиком —
              // очередь это порядок, и место вставки важнее самой строки.
              + (over === run.first && dragging !== null && dragging !== run.first
                ? 'border-b border-accent ' : '')
            }
          >
            <span className="w-4 shrink-0 text-right text-18 text-ink-faint">{run.first + 1}</span>
            {/* Название строки — кеглем ступени её чисел: «Фрегат ×3» и срок рядом читаются одной строкой. */}
            <span className={'min-w-0 flex-1 break-words ' + figureLabelClass('value')}>
              {run.project.name}
              {run.count > 1 ? <>{' '}<Figure accent="value">×{run.count}</Figure></> : null}
            </span>
            {/* Срок повтора — когда достроится ПОСЛЕДНЯЯ копия: очередь обещает конец дела. */}
            <span className={'shrink-0 ' + figureLabelClass('value')} title={t('build.eta.title')}>
              {eta(colony, queue, run.last, etaKey)}
            </span>
            {/*
              Справа — цена в единицах производства и крестик (09.10.2026, решение хозяина проекта):
              порядок правят щелчками, перетаскиванием и клавишами (↑ ↓, Shift, T), а стрелки,
              «▸» и «+» у каждой строки загромождали список.
            */}
            {run.project.cost !== undefined ? (
              <span className={'shrink-0 ' + figureLabelClass('value')}>
                {tf('build.queue.cost', { n: <Figure accent="value">{run.project.cost * run.count}</Figure> })}
              </span>
            ) : null}
            {own ? (
              <span className="shrink-0 text-18" onClick={(event) => event.stopPropagation()}>
                <QueueButton label="✕" title={run.count > 1 ? t('build.removeOne.title') : t('build.remove.title')}
                             disabled={busy}
                             onClick={() => onRemove(run.last)} />
              </span>
            ) : null}
          </li>
        ))}
      </ol>

      {/*
        Очередь за бесконечной стройкой не двинется никогда — правило сервера, о котором
        экран молчал. Молчащая очередь, которая «не работает», читается как поломка игры.
      */}
      {stalled ? (
        <p className={`mt-1 text-18 leading-tight text-warn`}>
          {t('build.queue.stalled', { name: colony.projectName ?? '' })}
        </p>
      ) : null}
      {/* Клавиши очереди названы там, где действуют (пункт 72): иначе о R и D не узнать. */}
      {wide && own && queue.length > 0 && !justTouched() ? (
        <p className="mt-1 text-14 text-ink-faint">{t('build.queue.keys')}</p>
      ) : null}
    </div>
  );
}

/**
 * Клавиатура очереди — трек техдолга, пункт 21: ↑ и ↓ ходят по строкам (схлопнутый повтор —
 * одна строка, выбор стоит на его первом месте, как и у щелчка). Без выбора ↓ встаёт на
 * первую строку, ↑ — на последнюю. Пустая очередь выбора не даёт.
 */
export const queueStep = (queue: ColonyProject[], selected: number | null, delta: number): number | null => {
  const runs = collapse(queue);
  if (runs.length === 0) {
    return null;
  }
  const at = runs.findIndex((run) => run.first === selected);
  const next = at < 0 ? (delta > 0 ? 0 : runs.length - 1) : Math.min(runs.length - 1, Math.max(0, at + delta));
  return runs[next].first;
};

/**
 * Shift + ↑ / ↓ — та же перестановка, что у стрелок строки: переставляется СОСЕДНЯЯ строка,
 * поэтому весь повтор едет одним запросом. Отдаёт пару «откуда, куда» для `onMove` и место,
 * где выбор окажется после переноса; у края очереди — ничего.
 */
export const queueShift = (queue: ColonyProject[], selected: number | null, delta: number):
  { from: number; to: number; selected: number } | null => {
  const run = collapse(queue).find((candidate) => candidate.first === selected);
  if (!run) {
    return null;
  }
  if (delta < 0) {
    return run.first === 0 ? null : { from: run.first - 1, to: run.last, selected: run.first - 1 };
  }
  return run.last === queue.length - 1 ? null : { from: run.last + 1, to: run.first, selected: run.first + 1 };
};

/**
 * Подряд идущие одинаковые проекты — одной строкой.
 *
 * Схлопываются только СОСЕДНИЕ: «Фрегат, Завод, Фрегат» — это три дела в своём порядке, и
 * сводить их в «Фрегат ×2» значило бы соврать о порядке стройки.
 */
const collapse = (queue: ColonyProject[]) => {
  const runs: { project: ColonyProject; first: number; last: number; count: number }[] = [];
  queue.forEach((project, index) => {
    const previous = runs[runs.length - 1];
    if (previous && previous.project.code === project.code) {
      previous.last = index;
      previous.count += 1;
      return;
    }
    runs.push({ project, first: index, last: index, count: 1 });
  });
  return runs;
};

/** Ожидание строки очереди словами; прочерк — считать нечем (нет производства). */
const eta = (
  colony: Colony,
  queue: ColonyProject[],
  index: number,
  key: 'build.eta' | 'build.eta.short',
): ReactNode => {
  if (queue[index].cost === undefined) {
    return t('build.endless');
  }
  const turns = queueEta(colony, queue, index);
  return turns === null ? '—' : tf(key, { n: <TurnsFigure accent="value">{turns}</TurnsFigure> });
};

/** Кнопка строки очереди: значок и подсказка, гаснет на время ответа сервера. */
function QueueButton({
  label,
  title,
  disabled,
  onClick,
}: {
  label: string;
  title: string;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      // Зона нажатия не мельче пальца (пункт 172): значок мелкий, а цель — 24 точки.
      className="link-quiet hit-24 disabled:cursor-not-allowed disabled:text-ink-off"
      disabled={disabled}
      title={title}
      onClick={onClick}
    >
      {label}
    </button>
  );
}
