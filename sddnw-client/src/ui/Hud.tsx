import type { GameDetails } from '../api/types';
import type { Section as ProfileSection } from './profile/ProfileScreen';
import { useGameStore, type OverlayPanel } from '../state/gameStore';
import { GameMenu } from './GameMenu';
import { hotkeyLabel, useHotkeys } from './hotkeys';
import { HUD_FONT_SIZE, SIDE_PANEL_WIDTH } from './layout';
import { t, useT } from '../i18n';
import { clickSound } from '../audio/uiSfx';
import { useEffect, type ReactNode } from 'react';
import { markHud } from '../state/hudPresence';
import { noviceHintsOn } from '../state/hints';

/** Какие разделы игрок уже открывал в этой партии — пункт 27, для подсветки «новое». */
const seenPanels = (gameId: string | undefined): string[] => {
  try {
    return JSON.parse(localStorage.getItem(`sddnw.hud.seen.${gameId ?? ''}`) ?? '[]') as string[];
  } catch {
    return [];
  }
};
import { ColoniesIcon, FleetIcon, InfoIcon, LeadersIcon, PlanetsIcon, RacesIcon } from './panelIcons';

/**
 * Нижнее меню — п. 11.1: все разделы игры текстовыми ссылками, включая саму «Игру».
 *
 * Меню занимает ровно ширину галактики и не заходит на правую панель, а пункты делят
 * эту ширину поровну.
 *
 * <b>«Игра» переехала сюда с верхней полосы</b>, а кнопки «Зум+» и «Зум-» убраны вовсе:
 * ряд во всю ширину экрана ради одной ссылки стоил карте верхней полосы, а масштаб и так
 * меняется колесом мыши — в оригинале на этом месте нижнего меню стоит `ZOOM`.
 */
export function Hud({ onLeave, onLoad, onStudents, onPreferences, onRegister, onReport, onEnding, onConceded }: {
  /** «Новая» и «Выход» меню «Игра» ведут на один экран — создания партии. */
  onLeave: () => void;
  /** Список сохранений — п. 3: полоса только передаёт вызов, рисует окно `App`. */
  onLoad: () => void;
  /** Империи учеников — пункт 184: полоса только передаёт вызов, рисует окно `App`. */
  onStudents: () => void;
  /** Настройки игрока — п. 11.1: полоса только передаёт вызов, рисует экран `App`. */
  /** Личный кабинет; с разделом — «Настройки» открывают его сразу на звуке. */
  onPreferences: (section?: ProfileSection) => void;
  onRegister: () => void;
  onReport: () => void;
  /** Итоги оконченной партии — field-backlog, пункт 33; сцену рисует `App`. */
  onEnding: () => void;
  /** Поражение признано — field-backlog, пункт 81; сцену рисует `App`. */
  onConceded: (details: GameDetails) => void;
}) {
  const game = useGameStore((state) => state.game);
  const overlay = useGameStore((state) => state.overlay);
  const setOverlay = useGameStore((state) => state.setOverlay);
  // Раскладку игрок правит сам (п. 11.1), поэтому клавиша в подсказке спрашивается хуком:
  // иначе на кнопке осталась бы прежняя буква, а работала бы новая — и поверят кнопке.
  const keys = useHotkeys();
  const { t } = useT();
  // Полоса отмечает себя: значки звука и наблюдения на карте живут в ней, а не в углу.
  useEffect(() => (game ? markHud() : undefined), [game !== null]);

  const toggle = (panel: OverlayPanel) => {
    seen(panel);
    setOverlay(overlay === panel ? 'none' : panel);
  };
  /*
    Разделы по мере надобности — field-backlog, пункт 27 (решение хозяина проекта 08.10.2026):
    «Расы» появляются после первого знакомства (вторая глава партии и дальше), «Лидеры» — после
    первого предложения службы. Ветерану — всё сразу; горячая клавиша работает всегда, и открытый
    ею раздел говорит о пустоте одной строкой. Появившийся раздел подсвечен «новое», пока его не
    откроют (`sddnw.hud.seen.<партия>`).
  */
  const self = useGameStore((state) => state.players.find((player) => player.id === state.credentials?.playerId));
  const veteran = !noviceHintsOn();
  const showRaces = veteran || (self?.chapter ?? 1) >= 2 || overlay === 'races';
  const showLeaders = veteran || Boolean(self?.leadersSeen) || overlay === 'leaders';
  const fresh = (panel: OverlayPanel) => !veteran && !seenPanels(game?.id).includes(panel);
  function seen(panel: OverlayPanel) {
    const list = seenPanels(game?.id);
    if (!list.includes(panel)) {
      try {
        localStorage.setItem(`sddnw.hud.seen.${game?.id ?? ''}`, JSON.stringify([...list, panel]));
      } catch {
        // Хранилище недоступно — подсветка «новое» просто останется до перезагрузки.
      }
    }
  }
  // Открытый горячей клавишей раздел тоже считается увиденным.
  useEffect(() => {
    if (overlay === 'races' || overlay === 'leaders') {
      seen(overlay);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [overlay, game?.id]);

  if (!game) {
    return null;
  }


  return (
    // data-map-inset — метка для сцены: панель лежит поверх карты,
    // и «вся галактика» вписывает поле в свободную от панелей полосу.
    <div
      data-map-inset="bottom"
      style={{ right: SIDE_PANEL_WIDTH, fontSize: HUD_FONT_SIZE }}
      /*
        Полоса ПЕРЕНОСИТСЯ на вторую строку, когда не помещается (`flex-wrap`), — это
        плата за крупный текст (п. 11.1). При 130 % на окне в тысячу точек карте остаётся
        полтысячи, а пунктов восемь: в одну строку они налезали друг на друга буквами.
        Высоту полосы сцена меряет сама по метке `data-map-inset`, поэтому вторая строка
        карту не закрывает.
      */
      className="absolute bottom-0 left-0 z-10 flex flex-wrap items-stretch border-t border-space-700 bg-space-950/90"
      // Нажатия полосы и меню «Игра» звучат щелчком (07.10.2026) — одним обработчиком на всю полосу.
      onClickCapture={clickSound}
    >
      {/*
        У каждого пункта есть и горячая клавиша (п. 11.1) — она названа во всплывающей подсказке:
        иначе узнать о ней негде, а клавиша, о которой игрок не знает, для игры всё равно
        что её нет.
      */}
      <MenuItem
        active={overlay === 'colonies'}
        icon={<ColoniesIcon />}
        hotkey={hotkeyLabel(keys.colonies)}
        onClick={() => toggle('colonies')}
      >
        {t('hud.colonies')}
      </MenuItem>
      <MenuItem
        active={overlay === 'planets'}
        icon={<PlanetsIcon />}
        hotkey={hotkeyLabel(keys.planets)}
        onClick={() => toggle('planets')}
      >
        {t('hud.planets')}
      </MenuItem>
      <MenuItem
        active={overlay === 'fleet'}
        icon={<FleetIcon />}
        hotkey={hotkeyLabel(keys.fleet)}
        onClick={() => toggle('fleet')}
      >
        {t('hud.fleet')}
      </MenuItem>
      {/*
        «Игра» стоит там, где прежде были кнопки масштаба, — и там же, где `ZOOM` в
        нижнем меню оригинала. Меню раскрывается вверх: полоса у нижнего края экрана.
      */}
      <GameMenu onLeave={onLeave} onLoad={onLoad} onStudents={onStudents} onPreferences={onPreferences} onRegister={onRegister} onReport={onReport} onEnding={onEnding} onConceded={onConceded} />
      {showLeaders ? (
        <MenuItem
          active={overlay === 'leaders'}
          icon={<LeadersIcon />}
          hotkey={hotkeyLabel(keys.leaders)}
          fresh={fresh('leaders')}
          onClick={() => toggle('leaders')}
        >
          {t('hud.leaders')}
        </MenuItem>
      ) : null}
      {/*
        Отдельного пункта «Дипломатия» в нижнем меню нет — его нет и в оригинале:
        разговор с правителем открывается аудиенцией с пульта «Расы», а не своей
        кнопкой карты. Сам экран дипломатии никуда не делся, туда ведёт «аудиенция»
        (`RaceRelationsScreen`).
      */}
      {showRaces ? (
        <MenuItem
          active={overlay === 'races'}
          icon={<RacesIcon />}
          hotkey={hotkeyLabel(keys.races)}
          fresh={fresh('races')}
          onClick={() => toggle('races')}
        >
          {t('hud.races')}
        </MenuItem>
      ) : null}
      {/*
        Инфо — п. 11.1, окно Information MOO II: летопись империй графиком, свойства
        рас знакомых соседей и обе страницы технологий.
      */}
      <MenuItem
        active={overlay === 'info'}
        icon={<InfoIcon />}
        hotkey={hotkeyLabel(keys.info)}
        onClick={() => toggle('info')}
      >
        {t('hud.info')}
      </MenuItem>
    </div>
  );
}

/**
 * Пункт меню: все пункты одной ширины — flex-1 от общей ширины полосы.
 *
 * Основа в `ch` — ширина самой длинной подписи: пункт уже неё не сжимается, а вместо
 * этого полоса переносит его на вторую строку. `basis-0` такого не умеет вовсе: он делит
 * ширину поровну независимо от того, влезают ли буквы.
 */
function MenuItem({
  children,
  icon,
  active = false,
  hotkey,
  fresh = false,
  onClick,
}: {
  children: string;
  /** Раздел только что появился (пункт 27) — подсвечен «новое», пока его не откроют. */
  fresh?: boolean;
  /** Значок рядом со словом — редизайн главной сцены (07.10.2026), как на наброске хозяина. */
  icon?: ReactNode;
  active?: boolean;
  /** Горячая клавиша этого раздела — п. 11.1; пусто, если её нет. */
  hotkey?: string;
  onClick: () => void;
}) {
  // Пункт сам зовёт `t` ради подсказки — значит, сам и подписывается на язык: без этого
  // он остался бы с прежним словарём до первой перерисовки по другой причине.
  useT();
  return (
    <button
      type="button"
      /*
        РЕДИЗАЙН (07.10.2026): значок и слово, полоса вдвое выше, без пунктирного подчёркивания —
        у восьми ссылок подряд оно было шумом. Открытый раздел подчёркнут янтарём снизу: это и
        есть примета «где я», а не цвет одного слова. Основа — по слову (backlog-promo, пункт
        32): пункты растягиваются поровну, а не влезающий переносится на вторую строку.
      */
      className={'flex flex-auto items-center justify-center gap-2 whitespace-nowrap border-b-2 px-2 py-3 '
        + (active ? 'border-accent text-accent' : 'border-transparent text-ink hover:text-accent')}
      title={hotkey ? t('hotkey.title', { action: children, key: hotkey }) : undefined}
      onClick={onClick}
    >
      {icon ? <span className={active ? 'text-accent' : 'text-lamp'}>{icon}</span> : null}
      {children}
      {fresh ? <span className="ml-1 bg-lamp px-1 text-11 uppercase text-space-950">{t('hud.new')}</span> : null}
    </button>
  );
}
