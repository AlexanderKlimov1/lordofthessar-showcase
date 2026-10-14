import { useEffect, useState } from 'react';
import { useAdvice } from '../../state/advisor';
import { ResearchAdviceLine } from '../advisor/AdvisorLines';
import { gameApi } from '../../api/client';
import type { BuildingRef, ResearchCategory, ResearchLevel, ResearchOption, ShipCatalog } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { OverlayFrame } from '../OverlayFrame';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { ResearchField } from './ResearchField';
import { ResearchFieldList } from './ResearchFieldList';
import { givesWholeLevel, nextLevel, showsRecommendation } from './researchRules';
import { t, useT } from '../../i18n';
import { RuleNote } from '../RuleNote';
import { ActionError } from '../feedback/ActionError';

/**
 * Экран исследований — п. 9, окно CHANGE CURRENT RESEARCH из MOO II. Открывается
 * кликом по сегменту «Наука» в правой панели.
 *
 * <b>Сцена во весь экран и делится на два сегмента.</b> Справа — сетка разделов, снятая
 * со снимка оригинала: восемь клеток двумя столбцами по четыре строки, по разделу в
 * клетке, порядок совпадает с порядком разделов в дереве. Слева — картинка и описание
 * выбранной технологии: в оригинале они показываются окошком по правому щелчку, а здесь
 * стоят на виду постоянно, потому что выбор без описания — это выбор вслепую.
 *
 * <b>Пропорции сегментов взяты у оригинала.</b> Там окно с сеткой занимает примерно две
 * трети ширины экрана (в снимке 896×672 — от 134-го пикселя до 747-го), а оставшаяся
 * треть уходит полосе галактики слева и панели состояния справа. Здесь эта треть целиком
 * отдана новому сегменту, и он стоит слева — как и просили; сетке достаются те же две
 * трети, что и в оригинале, поэтому клетки не растягиваются в полосы.
 *
 * <b>Управляющих кнопок по-прежнему две:</b> плашка с названием сверху и одна широкая
 * кнопка снизу — «Отмена»: ею уходят, ничего не выбрав.
 *
 * <b>Выбор технологии закрывает окно сам</b> — как в оригинале (решение хозяина проекта,
 * 30.09.2026). Выбрав цель, в этом окне больше делать нечего, а прежде после выбора
 * приходилось ещё нажимать «Ок», который ничего не подтверждал: сервер записал цель в миг
 * щелчка. Закрывается окно ПО ОТВЕТУ сервера, а не по щелчку: отказ (цель не того уровня,
 * партия уже посчитана) должен остаться на экране строкой ошибки, а не пропасть вместе с
 * окном.
 *
 * Весь раздел целиком показывается отдельным окошком поверх сетки (`ResearchFieldList`) —
 * так же он устроен и в оригинале. Отдельного окошка с описанием больше нет: описание
 * теперь всегда на виду в левом сегменте, и второе такое же окно было бы тем же самым.
 *
 * Пропорции клетки заданы в `em` (см. `ResearchField`), а размер шрифта — от самого поля
 * под сетку (см. {@link SCALE}), поэтому сетка занимает свой сегмент целиком и никуда из
 * него не вылезает.
 *
 * Состояние исследований серверное: очки, прорыв и изученное считает сервер в фазе
 * конца хода, экран их только показывает и меняет цель. Ход исследования и шанс прорыва
 * стоят в сегменте «Наука» правой панели — в оригинале они тоже там, а не на этом экране.
 */

/**
 * Размер шрифта технологии: от него в `em` посчитана вся сетка.
 *
 * Считается от самого поля под сетку, а не от окна браузера: сверху плашка названия,
 * снизу кнопка, слева сегмент с описанием — вычитать всё это из `100vh` значило бы
 * каждый раз угадывать, и окно так и выезжало за верхнюю границу экрана.
 *
 * <b>Контейнер объявляет не тот элемент, который меряется.</b> Единицы `cq*` считаются
 * от <i>ближайшего предка</i>-контейнера, а не от самого элемента: поставив
 * `container-type` и `font-size` на один и тот же div, получаешь проценты не его, а
 * окна браузера — сетка тогда вылезала за окно и закрывала собой плашку с кнопкой.
 * Поэтому контейнером объявлено поле, а кегль задан вложенному в него блоку.
 */
const SCALE = 'clamp(6px, min(2.45cqh, 2.4cqw), 26px)';

/** Технология, о которой рассказывает левый сегмент, и раздел, откуда она взята. */
/** Разделы, чей первый уровень оригинал выдаёт всем с первого хода (двигатель, топливо, физика). */
const FREE_IN_ORIGINAL = ['power', 'chemistry', 'physics'];

type Highlighted = {
  category: ResearchCategory;
  level: ResearchLevel;
  option: ResearchOption;
};

export function ResearchScreen({ onClose }: { onClose: () => void }) {
  const tree = useGameStore((state) => state.researchTree);
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const research = useGameStore((state) => state.research);
  const setResearch = useGameStore((state) => state.setResearch);

  /** Раздел, раскрытый лестницей поверх сетки; `null` — открыта сама сетка. */
  const [listed, setListed] = useState<ResearchCategory | null>(null);
  const [choosing, setChoosing] = useState(false);
  /**
   * О чём рассказывает левый сегмент. Ставится наведением и выбором: пока игрок водит
   * курсором по сетке, сегмент показывает то, на что он смотрит, — так описание успевает
   * прочитаться до нажатия, а не после.
   */
  const [highlighted, setHighlighted] = useState<Highlighted | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Экран модальный и перекрывает карту. Пока поверх него открыт список раздела, экран Esc
  // не слушает вовсе: окошко и экран рождаются в разных отрисовках, и полагаться на
  // порядок их появления в очереди useModalEscape нельзя — одно нажатие закрывало
  // оба разом. Условие надёжнее порядка.
  useModalEscape(listed === null, onClose, true);
  useT();
  const advice = useAdvice();

  const categories = tree?.categories ?? [];

  if (!tree || categories.length === 0 || !research || !game || !credentials) {
    return (
      <OverlayFrame title={t('research.title')} onClose={onClose}>
        <p className="text-xs text-ink-dim">{t('research.notLoaded')}</p>
      </OverlayFrame>
    );
  }

  const highlight = (category: ResearchCategory, option: ResearchOption) => {
    const level = nextLevel(category, research);
    if (level) {
      setHighlighted({ category, level, option });
    }
  };

  const choose = (category: ResearchCategory, option: ResearchOption) => {
    const level = nextLevel(category, research);
    if (!level) {
      return;
    }
    // Уровень, который выдаётся целиком (общий у всех, любой у изобретательной расы —
    // п. 7), выбирается целиком, и какая из его технологий записана целью — безразлично:
    // прорыв выдаёт все. Чтобы цель не зависела от того, по какой строке щёлкнули,
    // отправляем всегда первую.
    const target = givesWholeLevel(research, level) ? level.options[0] : option;

    setChoosing(true);
    setError(null);
    gameApi
      .chooseResearch(game.id, credentials.accessToken, category.code, level.order, target.code)
      .then((chosen) => {
        setResearch(chosen);
        // Цель записана — окну больше делать нечего, и оно закрывается само, как в
        // оригинале. Ждать «Ок», который ничего не подтверждает, незачем.
        onClose();
      })
      .catch((failure: Error) => {
        setError(failure.message);
        setChoosing(false);
      });
  };

  return (
    // Enter закрывает окно, но на кнопке технологии он — выбор (data-keeps-enter).
    <div className="fixed inset-0 z-40 flex flex-col bg-space-950 text-xs" data-keeps-enter>
      {/*
        Плашка с названием по центру сверху — как в оригинале. Надписей на ней ДВЕ, и это
        не украшение: в MOO II окна тоже два — «SELECT NEW RESEARCH» (docs/moo2, снимок
        сцены выбора исследования) и «CHANGE CURRENT RESEARCH». Цели нет — значит, экран
        открылся сам после прорыва и ждёт выбора; цель есть — игрок пришёл её менять.
        Плашка это и говорит, а другого места объяснить, почему экран всплыл, на нём нет.
      */}
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="border border-space-600 bg-space-800 px-6 py-0.5 uppercase tracking-[0.3em] text-ink-bright">
          {research.categoryCode ? t('research.changeCurrent') : t('research.selectNew')}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        {/*
          Левый сегмент — треть ширины, как в оригинале досталось всему, что не сетка.
          Картинка сверху, под ней название, уровень с ценой и описание.
        */}
        <div className="flex w-1/3 flex-none flex-col">
          {/* Советник — пункт 23: «взять» делает то же, что щелчок по этой технологии в сетке. */}
          <div className="flex-none px-4 pt-4">
            <ResearchAdviceLine advice={advice} busy={choosing} onTake={(categoryCode, optionCode) => {
              const category = categories.find((one) => one.code === categoryCode);
              const option = category ? nextLevel(category, research)?.options.find((one) => one.code === optionCode) : undefined;
              if (category && option) {
                highlight(category, option);
                choose(category, option);
              }
            }} />
          </div>
          <TechnologyPreview highlighted={highlighted} />
        </div>

        {/*
          Поле под сетку объявлено контейнером: от его размеров считается кегль клеток,
          поэтому сетка подстраивается под то место, которое ей осталось после плашки,
          кнопки и левого сегмента, а не под размер окна браузера.
        */}
        <div
          className="relative min-h-0 flex-1 overflow-hidden border-l border-space-700"
          style={{ containerType: 'size' }}
        >
          <div className="flex h-full flex-col justify-center" style={{ fontSize: SCALE }}>
            {/*
              Ширина сетки задана в `em`, как и высота клетки: иначе на широком экране
              клетки растягивались бы в полосы, а в оригинале они почти квадратные
              (306×147 px).
            */}
            <div className="mx-auto grid w-[41.29em] max-w-full grid-cols-2 gap-x-[0.79em] gap-y-[0.13em]">
              {categories.map((category) => (
                <ResearchField
                  key={category.code}
                  category={category}
                  research={research}
                  busy={choosing}
                  onChoose={(option) => {
                    highlight(category, option);
                    choose(category, option);
                  }}
                  onHighlight={(option) => highlight(category, option)}
                  highlightedCode={highlighted?.category.code === category.code ? highlighted.option.code : undefined}
                  onOpenList={() => setListed(category)}
                />
              ))}
            </div>
          </div>

          {/*
            Раздел целиком ложится поверх сетки, отступив от её краёв: в MOO II это
            отдельное окно поверх экрана, а не часть его разметки.
          */}
          {listed ? (
            <div className="absolute inset-[6%] z-10">
              <ResearchFieldList
                category={listed}
                research={research}
                onClose={() => setListed(null)}
              />
            </div>
          ) : null}
        </div>
      </div>

      {/*
        Одна широкая кнопка по центру снизу — «CANCEL» оригинала. Ошибка сервера
        показывается рядом: другого места для неё на экране нет, а молчать о ней нельзя.
      */}
      <footer
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center gap-4 border-t border-space-600 bg-space-800 px-3"
      >
        {error ? <ActionError as="span" className="absolute left-3" message={error} /> : null}
        <button
          type="button"
          className="w-1/3 border border-space-600 bg-space-900 py-0.5 uppercase tracking-[0.3em] text-ink hover:text-accent"
          onClick={onClose}
        >
          {t('common.ok')}
        </button>
      </footer>
    </div>
  );
}

/**
 * Левый сегмент: картинка и описание выбранной технологии — п. 9.
 *
 * Картинок технологий в игре пока нет, поэтому рамка стоит пустой и говорит, чего в ней
 * не хватает: разметка под спрайт готова, останется заменить содержимое рамки. Пока
 * ничего не выбрано, сегмент объясняет, что делать, — пустой прямоугольник читался бы
 * как поломка.
 */
/**
 * Что откроет технология — field-backlog, пункт 12: в миг выбора видно, ЧТО изменится, а не
 * только когда. Названия берутся из справочников зданий и кораблей по их же ссылке на
 * технологию (`requiredTechCode`), — второго списка «что открывает» в игре нет. Справочники
 * читаются один раз за открытие экрана.
 */
/** Что открывает технология: название вещи и что она делает — из её же справочника. */
interface Unlock {
  name: string;
  description?: string;
}

function useUnlocks(): (code: string) => Unlock[] {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [buildings, setBuildings] = useState<BuildingRef[]>([]);
  const [ships, setShips] = useState<ShipCatalog | null>(null);
  useEffect(() => {
    gameApi.reference.buildings().then(setBuildings).catch(() => undefined);
    if (game && credentials) {
      gameApi.getShipCatalog(game.id, credentials.accessToken).then(setShips).catch(() => undefined);
    }
  }, [game?.id, credentials?.accessToken]);
  const unlock = (one: { name: string; description?: string }): Unlock =>
    ({ name: one.name, description: one.description });
  return (code) => {
    const all = [
      ...buildings.filter((one) => one.requiredTechCode === code).map(unlock),
      ...(ships?.hulls ?? []).filter((one) => one.requiredTechCode === code).map(unlock),
      ...(ships?.components ?? []).filter((one) => one.requiredTechCode === code).map(unlock),
      ...(ships?.modifications ?? []).filter((one) => one.requiredTechCode === code).map(unlock),
    ];
    // Одна вещь в двух справочниках (корпус и его часть) — одной строкой.
    return all.filter((one, index) => all.findIndex((other) => other.name === one.name) === index);
  };
}

function TechnologyPreview({ highlighted }: { highlighted: Highlighted | null }) {
  const game = useGameStore((state) => state.game);
  const research = useGameStore((state) => state.research);
  const unlocks = useUnlocks();
  const opens = highlighted ? unlocks(highlighted.option.code) : [];
  return (
    <section className="flex min-h-0 flex-1 flex-col gap-3 overflow-auto p-4">
      <div className="flex aspect-[4/3] max-h-[45%] flex-none items-center justify-center border border-space-700 bg-space-900/60 px-4">
        <span className="text-center leading-tight text-ink-faint">
          {highlighted ? t('research.image') : t('research.noImage')}
        </span>
      </div>

      {highlighted ? (
        <>
          <div>
            <div className="text-base leading-tight text-accent">{highlighted.option.name}</div>
            <div className="mt-1 uppercase tracking-[0.2em] text-ink-dim">
              {highlighted.category.name} · {highlighted.level.name} · {t('research.rp', { n: highlighted.level.cost })}
            </div>
          </div>
          <p className="leading-relaxed text-ink-soft">{highlighted.option.description}</p>
          {/*
            Что технология ДЕЛАЕТ (12.10.2026, баг хозяина проекта): описание в дереве — строка в
            несколько слов («Defensive missile system»), а действие написано у того, что она
            открывает, — у здания, корпуса, части корабля или модификации, в их справочниках.
            Поэтому каждая открываемая вещь — своей строкой, с её описанием.
          */}
          {opens.length > 0 ? (
            <div className="space-y-2">
              <div className="uppercase tracking-[0.2em] text-ink-dim">{t('research.opens.title')}</div>
              {opens.map((one) => (
                <div key={one.name}>
                  <div className="text-ink-bright">{one.name}</div>
                  {one.description ? <p className="leading-relaxed text-ink">{one.description}</p> : null}
                </div>
              ))}
            </div>
          ) : null}
          {/* «Обычно берут» — пункт 120: опора для того, кто не знает, что выбрать. */}
          {research && showsRecommendation(research, highlighted.level, highlighted.option) ? (
            <p className="leading-relaxed text-lamp">{t('research.recommended.note')}</p>
          ) : null}
          {highlighted.level.general ? (
            <p className="leading-relaxed text-ink-faint">
              {t('research.generalLevel')}
            </p>
          ) : null}
          {/* Первые уровни этих трёх разделов оригинал даёт всем расам даром, у нас они
              исследуются (01.10.2026, решение хозяина проекта) — ветеран удивится, увидев их
              в выборе, поэтому сказано здесь же. */}
          {highlighted.level.order === 1 && FREE_IN_ORIGINAL.includes(highlighted.category.code) ? (
            <RuleNote hint="rule.firstLevels" text={t('rule.firstLevels')} />
          ) : null}
          {/* Гостевая партия считает науку вдвое — сказано там, где на неё смотрят. */}
          {game?.guestRules ? <RuleNote hint="rule.guestResearch" text={t('rule.guestResearch')} /> : null}
        </>
      ) : (
        <p className="leading-relaxed text-ink-faint">
          {t('research.hint')}
        </p>
      )}
    </section>
  );
}
