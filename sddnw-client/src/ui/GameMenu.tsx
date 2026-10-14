import type { GameDetails, GameSave, VictoryClaim } from '../api/types';
import type { Section as ProfileSection } from './profile/ProfileScreen';
import { useEffect, useRef, useState } from 'react';
import { gameApi, GameApiError } from '../api/client';
import { useGameStore } from '../state/gameStore';
import { RuleNote } from './RuleNote';
import { MentorChoice } from './mentor/MentorChoice';
import { readAccount } from '../state/session';
import { hotkeyLabel, useHotkeys } from './hotkeys';
import { fullscreenSupported, toggleFullscreen, useFullscreen } from './fullscreen';
import { t, useT } from '../i18n';
import { GameIcon } from './panelIcons';
import { SoundControls } from './SoundControls';
import { AdminWatchEye } from './AdminWatchEye';
import { InviteLink } from './invite/InviteLink';
import { VacationChoice } from './invite/VacationChoice';

interface GameMenuProps {
  /** «Новая» и «Выход» ведут на один экран — создания игры: другого выхода из партии нет. */
  onLeave: () => void;
  /**
   * Открыть список сохранений — п. 3.
   *
   * Окно, как и настройки ниже, рисует `App`: нарисованное отсюда, оно оставалось в слое
   * нижней полосы и уходило под правую панель.
   */
  onLoad: () => void;
  /** Открыть империи учеников — пункт 184; окно рисует `App`, как и все прочие. */
  onStudents: () => void;
  /**
   * Открыть настройки игрока — п. 11.1.
   *
   * <b>Экран открывает НЕ меню, а `App`</b>, и это не прихоть: нижняя полоса лежит в своём
   * слое (`z-10`), а слой — это клетка. Экран, нарисованный из меню, оставался внутри неё
   * и уходил ПОД правую панель (`z-20`), хотя у самого стоит `z-50`: номер слоя считается
   * внутри своего слоя, а не поперёк всех. Поэтому меню только говорит «открыть», а рисует
   * `App` — там же, где и все прочие экраны поверх карты.
   */
  /** Личный кабинет; с разделом — «Настройки» открывают его сразу на звуке. */
  onPreferences: (section?: ProfileSection) => void;
  /** Гость сохраняет партию за собой — field-backlog, пункт 1; окно рисует `App`. */
  onRegister: () => void;
  /** Сообщить о проблеме — field-backlog, пункт 157; окно рисует `App`. */
  onReport: () => void;
  /** Итоги оконченной партии — field-backlog, пункт 33; сцену рисует `App`. */
  onEnding: () => void;
  /** Поражение признано (пункт 81): `App` показывает сцену конца с ответом сервера. */
  onConceded: (details: GameDetails) => void;
}

/** Сколько миллисекунд висит подсказка о сохранении. */
const NOTICE_MS = 4000;

/**
 * Кто слушает горячую клавишу меню — п. 11.1. Меню держит своё «открыто» в себе (это
 * раскрывающийся список полосы, а не экран хранилища), поэтому клавиша карты не ставит
 * признак, а ПРОСИТ меню переключиться — тем же действием, что и нажатие на пункт.
 */
const toggleListeners = new Set<() => void>();

/** Открыть или закрыть меню «Игра» — горячая клавиша карты (G). */
export const toggleGameMenu = (): void => toggleListeners.forEach((listener) => listener());

/**
 * Меню «Игра» — п. 11.1: пункт НИЖНЕЙ полосы, открывающийся вверх.
 *
 * <b>Своей полосы вверху у него больше нет.</b> Прежде меню занимало ряд во всю ширину
 * экрана ради одной ссылки, а карта начиналась под ним; теперь оно стоит в нижнем меню
 * рядом с прочими разделами — там же, где в оригинале стоит `ZOOM`, — и весь верх экрана
 * отдан галактике.
 *
 * Единственный выход из партии: «Игра» → «Новая» или «Игра» → «Выход»; закрытие или
 * перезагрузка страницы партию не бросает — клиент возвращается в неё сам.
 *
 * Подсказка о сохранении и ошибка действия показываются НАД полосой: места в самой полосе
 * нет, а терять их нельзя — других мест для них внутри партии не осталось.
 */
export function GameMenu({ onLeave, onLoad, onStudents, onPreferences, onRegister, onReport, onEnding, onConceded }: GameMenuProps) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const lastError = useGameStore((state) => state.lastError);
  const setError = useGameStore((state) => state.setError);
  const [open, setOpen] = useState(false);
  // Раскладка спрашивается хуком: переназначенная клавиша обязана появиться в списке сразу.
  const keys = useHotkeys();
  const fullscreen = useFullscreen();
  const [notice, setNotice] = useState<string | null>(null);
  // «Покинуть партию» — в два нажатия: первое спрашивает, второе уводит (пункт 11).
  const [leaving, setLeaving] = useState(false);
  const humans = useGameStore((state) => state.players.filter((player) => player.playerType === 'HUMAN').length);
  // «Признать поражение» — тоже в два нажатия (пункт 81): это исход партии, его не отменить.
  const [conceding, setConceding] = useState(false);
  // «Объявить победу» (пункт 154) — тоже в два нажатия; право спрашивается у сервера при
  // открытии меню, и пункт гаснет с причиной ДО нажатия (пункт 76), а не отказом после.
  const [declaring, setDeclaring] = useState(false);
  const [claim, setClaim] = useState<VictoryClaim | null>(null);
  const selfHuman = useGameStore((state) => state.players.some((player) =>
    player.id === state.credentials?.playerId && player.playerType === 'HUMAN'));
  const menuRef = useRef<HTMLDivElement>(null);
  /*
    Сыгранное в безопасности — на виду (field-backlog, пункт 160): «партия сохранена на ходу N»
    спрашивается у сервера при открытии меню — автосохранение снимается в конце каждого хода.
  */
  const [autosave, setAutosave] = useState<GameSave | null>(null);
  useEffect(() => {
    if (!open || !game) {
      return;
    }
    let alive = true;
    gameApi.autosave(game.id)
      .then((save) => alive && setAutosave(save ?? null))
      .catch(() => alive && setAutosave(null));
    return () => {
      alive = false;
    };
  }, [open, game?.id, game?.turn]); // eslint-disable-line react-hooks/exhaustive-deps

  /** Слепок файлом — пункт 160: ответ на «а если сервер закроется»; только одиночной партии. */
  const downloadSave = () => {
    if (!autosave) {
      return;
    }
    gameApi.exportSave(autosave.id)
      .then((file) => {
        const blob = new Blob([JSON.stringify(file)], { type: 'application/json' });
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = `${autosave.name.replace(/[^\p{L}\p{N}]+/gu, '-')}-turn-${autosave.turn}.json`;
        link.click();
        URL.revokeObjectURL(link.href);
      })
      .catch((failure: Error) => setError(failure.message));
  };

  // Закрытое меню забывает недоговорённое «покинуть?»: следующее открытие начинается заново.
  useEffect(() => {
    if (!open) {
      setLeaving(false);
      setConceding(false);
      setDeclaring(false);
    }
  }, [open]);

  useEffect(() => {
    const { game: current, credentials: own } = useGameStore.getState();
    if (!open || !selfHuman || !current || current.status !== 'IN_PROGRESS' || !own) {
      setClaim(null);
      return;
    }
    let alive = true;
    gameApi.victoryClaim(current.id, own.accessToken)
      .then((answer) => { if (alive) setClaim(answer); })
      .catch(() => { if (alive) setClaim(null); });
    return () => { alive = false; };
  }, [open, selfHuman]);

  useEffect(() => {
    const toggle = () => setOpen((current) => !current);
    toggleListeners.add(toggle);
    return () => {
      toggleListeners.delete(toggle);
    };
  }, []);

  // Меню закрывается кликом мимо него и клавишей Esc — как привычное меню приложения.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (!notice) {
      return;
    }
    const timer = window.setTimeout(() => setNotice(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);

  // Ошибки действий внутри партии (например, конца хода) больше показывать негде:
  // экраны лобби и меню сюда не заходят. Гаснут они так же, как подсказка.
  useEffect(() => {
    if (!lastError) {
      return;
    }
    const timer = window.setTimeout(() => setError(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [lastError, setError]);

  const run = (action: () => void) => () => {
    setOpen(false);
    action();
  };

  // Сохранение — слепок всего состояния партии в базе сервера: в него уходит конец
  // последнего завершённого хода. Сохраняет только игрок: автосохранения нет, оно
  // писало сотню килобайт каждый ход, а пользовались им редко.
  useT();
  const save = () => {
    if (!game || !credentials) {
      return;
    }
    gameApi
      .saveGame(game.id, credentials.accessToken)
      .then((saved) => setNotice(t('gameMenu.saved', { name: game.name, turn: turnLabel(saved.turn) })))
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('gameMenu.saveFailed')),
      );
  };

  return (
    <div ref={menuRef} className={'relative flex flex-auto flex-col items-center justify-center border-b-2 px-2 '
      + (open ? 'border-accent' : 'border-transparent')}>
      <button
        type="button"
        className={`flex items-center gap-2 whitespace-nowrap pt-1 ${open ? 'text-accent' : 'text-ink hover:text-accent'}`}
        aria-haspopup="menu"
        aria-expanded={open}
        title={t('hotkey.title', { action: t('gameMenu.game'), key: hotkeyLabel(keys.game) })}
        onClick={() => setOpen(!open)}
      >
        <span className={open ? 'text-accent' : 'text-lamp'}><GameIcon /></span>
        {t('gameMenu.game')}
      </button>
      {/*
        Значок звука и око наблюдения — под «Игрой» (07.10.2026, решение хозяина проекта), а не
        в углу поверх галактики. Ряд мелкий, чтобы полоса почти не росла в высоту.
      */}
      <div className="flex h-5 items-center gap-2 pb-0.5">
        {/* Музыка — скрипичным ключом, эффекты — динамиком, у каждого свой ползунок (09.10.2026). */}
        <SoundControls />
        <AdminWatchEye inline />
      </div>

      {open ? (
        // Раскрывается ВВЕРХ: полоса стоит у нижнего края экрана, и вниз открываться некуда.
        <div
          role="menu"
          // Меню раскрывается вверх и выросло выше низкого окна: без предела верхние пункты
          // («Сохранить», «Личный кабинет») уходили за край экрана, и дотянуться до них было нечем.
          className="absolute bottom-full left-0 z-40 mb-1 max-h-[calc(100vh-5rem)] w-max min-w-[11rem] overflow-y-auto border border-space-600 bg-space-900 py-1 text-left shadow-lg shadow-black/60"
        >
          {autosave ? (
            <div className="border-b border-space-700 px-3 pb-1.5 pt-1 text-12 text-ink-dim">
              <span className="text-good" aria-hidden>✓ </span>
              {t('gameMenu.autosaved', { turn: autosave.turn })}
              {/* Выгрузить файлом — только одиночную партию: из файла поднимается лишь она. */}
              {readAccount()?.account.role !== 'GUEST' && autosave.humanPlayers === 1 ? (
                <button type="button" className="link ml-2 text-12" title={t('gameMenu.saveFile.hint')}
                        onClick={downloadSave}>
                  {t('gameMenu.saveFile')}
                </button>
              ) : null}
            </div>
          ) : null}
          <MenuItem onClick={run(onLeave)}>{t('gameMenu.new')}</MenuItem>
          <MenuItem onClick={run(save)}>{t('gameMenu.save')}</MenuItem>
          {/* Гостю сохранений не поднимают (backlog-promo, пункт 1): пункт ответил бы отказом. */}
          {readAccount()?.account.role === 'GUEST' ? (
            // Гостю — зарегистрироваться, не теряя партии (field-backlog, пункт 1).
            <MenuItem onClick={run(onRegister)}>{t('gameMenu.register')}</MenuItem>
          ) : (
            <MenuItem onClick={run(onLoad)}>{t('gameMenu.load')}</MenuItem>
          )}
          {/*
            Настройки игрока — п. 11.1: раскладка горячих клавиш. Пункт стоит и здесь, и в
            главном меню, потому что настройки принадлежат УЧЁТНОЙ ЗАПИСИ, а не партии:
            клавишу переназначают тогда, когда она помешала, то есть посреди игры, и ради
            этого выходить из партии было бы нелепо.
          */}
          {/* Партия окончена — её итоги открываются ещё раз отсюда (пункт 33). */}
          {game?.status === 'FINISHED' ? (
            <MenuItem onClick={run(onEnding)}>{t('gameMenu.ending')}</MenuItem>
          ) : null}
          <MenuItem onClick={run(() => onPreferences())}>{t('gameMenu.preferences')}</MenuItem>
          {/* Настройки (07.10.2026, решение хозяина проекта): звук и размер текста живут в кабинете. */}
          <MenuItem onClick={run(() => onPreferences('sound'))}>{t('gameMenu.settings')}</MenuItem>
          {/* Полный экран (пункт 189): F11 держит браузер, поэтому свой пункт и своя клавиша. */}
          {fullscreenSupported() ? (
            <MenuItem onClick={run(toggleFullscreen)}>
              {t(fullscreen ? 'gameMenu.fullscreen.exit' : 'gameMenu.fullscreen.enter')}
            </MenuItem>
          ) : null}
          <MenuItem onClick={run(onReport)}>{t('problem.open')}</MenuItem>
          <MenuItem onClick={run(onLeave)}>{t('gameMenu.quit')}</MenuItem>
          {/*
            Покинуть партию людей — backlog-promo, пункт 11: империю поведёт ИИ, и партия
            больше не ждёт ушедшего. Обычный выход («выйти») партию не отпускает — она ждёт,
            пока часы не сочтут игрока ушедшим. В одиночной партии пункта нет: ждать там
            некому, и уходить не от кого.
          */}
          {humans >= 2 && game?.status === 'IN_PROGRESS' ? (
            <MenuItem
              onClick={() => {
                if (!leaving) {
                  setLeaving(true);
                  return;
                }
                setLeaving(false);
                setOpen(false);
                if (game && credentials) {
                  gameApi
                    .leaveGame(game.id, credentials.accessToken)
                    .then(onLeave)
                    .catch((failure: Error) => setError(failure.message));
                }
              }}
            >
              {leaving ? t('gameMenu.leave.confirm') : t('gameMenu.leave')}
            </MenuItem>
          ) : null}
          {/*
            Признать поражение — field-backlog, пункт 81: безнадёжную партию не обязательно
            дотягивать или бросать. Империю дальше ведёт ИИ, а игрок получает сцену конца с тем,
            что у него получилось. Против одного ИИ партия на этом кончается, с людьми — идёт без
            него. Только тому, кто сам за столом: наблюдателя ведёт ИИ и так.
          */}
          {selfHuman && game?.status === 'IN_PROGRESS' ? (
            <MenuItem
              onClick={() => {
                if (!conceding) {
                  setConceding(true);
                  return;
                }
                setConceding(false);
                setOpen(false);
                if (game && credentials) {
                  gameApi
                    .concede(game.id, credentials.accessToken)
                    .then(onConceded)
                    .catch((failure: Error) => setError(failure.message));
                }
              }}
            >
              {conceding
                ? t(humans >= 2 ? 'gameMenu.concede.confirmShared' : 'gameMenu.concede.confirm')
                : t('gameMenu.concede')}
            </MenuItem>
          ) : null}
          {/*
            Объявить победу — field-backlog, пункт 154: хвост партии, где исход известен, не
            обязан доигрываться. Пункт виден всегда, а погашен с причиной и числами, пока все
            прочие живые империи вместе не слабее вас втрое: правило, о котором негде прочитать,
            для игрока не существует.
          */}
          {selfHuman && game?.status === 'IN_PROGRESS' && claim ? (
            <button
              type="button"
              role="menuitem"
              disabled={!claim.allowed}
              title={claim.allowed ? t('gameMenu.declare.title', { own: claim.own, others: claim.others })
                : claim.reason}
              className="block w-full px-3 py-1 text-left text-ink hover:bg-space-800 hover:text-accent disabled:cursor-not-allowed disabled:text-ink-off disabled:hover:bg-transparent"
              onClick={() => {
                if (!declaring) {
                  setDeclaring(true);
                  return;
                }
                setDeclaring(false);
                setOpen(false);
                if (game && credentials) {
                  gameApi
                    .declareVictory(game.id, credentials.accessToken)
                    .then(onConceded)
                    .catch((failure: Error) => setError(failure.message));
                }
              }}
            >
              {declaring ? t('gameMenu.declare.confirm') : t('gameMenu.declare')}
              {!claim.allowed && claim.reason ? (
                <span className="block max-w-[22rem] text-14 text-ink-faint">{claim.reason}</span>
              ) : null}
            </button>
          ) : null}
          {/*
            Пригласить друга (пункт 15) — ссылка за этот стол; отпуск (пункт 177) — только когда за
            столом есть другие люди: одиночная партия и так ждёт игрока.
          */}
          {selfHuman && game?.status !== 'FINISHED' ? (
            <div className="max-w-[22rem] border-t border-space-700 px-3 py-2">
              <InviteLink />
            </div>
          ) : null}
          {selfHuman && game?.status === 'IN_PROGRESS' && humans >= 2 ? (
            <div className="max-w-[22rem] border-t border-space-700 px-3 py-2">
              <VacationChoice />
            </div>
          ) : null}
          {/* Наставник — пункт 184: только где за столом есть другой человек. */}
          {selfHuman && game?.status === 'IN_PROGRESS' && humans >= 2 ? (
            <div className="max-w-[22rem] border-t border-space-700 px-3 py-2">
              <MentorChoice onStudents={() => { setOpen(false); onStudents(); }} />
            </div>
          ) : null}
          {/*
            Памятка гостевой партии (03.10.2026, решение хозяина проекта): условия здесь мягче
            настоящих — богатый старт, первые уровни по оплате, наука вдвое, — и игрок,
            перешедший потом в обычную партию, не должен удивляться, что там всё медленнее.
          */}
          {game?.guestRules ? (
            <div className="mt-1 max-w-[22rem] border-t border-space-700 px-3 py-2">
              <RuleNote hint="rule.guestGame" text={t('rule.guestGame')} />
            </div>
          ) : null}
          {/*
            Списка горячих клавиш здесь больше нет (08.10.2026, решение хозяина проекта): он
            удлинял меню вдвое. Клавиши перечислены в «Настройках» → «Горячие клавиши» вместе с
            закреплёнными клавишами карты, а у кнопок подсказка с клавишей стоит на самой кнопке.
          */}
        </div>
      ) : null}

      {/*
        Подсказка и ошибка — над полосой, и только пока меню закрыто: раскрытое меню
        стоит на том же месте, и спорить им там незачем.
      */}
      {!open && (notice || lastError) ? (
        <span
          /*
            Подсказка встаёт НАД пунктом и по центру его, а не от левого края: пункт
            стоит посреди полосы, и строка, пущенная вправо одной линией, уходила под
            правую панель — «Партия „Fleet“ сохран…». Ширина ограничена, перенос
            разрешён: длинное имя партии складывается в две строки, а не пропадает.
          */
          className={
            'absolute bottom-full left-1/2 mb-1 w-max max-w-[min(26rem,60vw)] '
            + '-translate-x-1/2 border border-space-700 bg-space-950/90 px-2 py-0.5 '
            + 'text-center ' + (lastError ? 'text-danger' : 'text-ink-dim')
          }
        >
          {lastError ? <><span aria-hidden className="mr-1">⚠</span>{lastError}</> : notice}
        </span>
      ) : null}
    </div>
  );
}

/** Ход 0 — состояние сразу после старта партии: ни одного хода ещё не завершено. */
const turnLabel = (turn: number): string => (turn === 0 ? t('turn.start') : t('turn.n', { n: turn }));

function MenuItem({ children, onClick }: { children: string; onClick: () => void }) {
  return (
    <button
      type="button"
      role="menuitem"
      className="block w-full px-3 py-1 text-left text-ink hover:bg-space-800 hover:text-accent"
      onClick={onClick}
    >
      {children}
    </button>
  );
}
