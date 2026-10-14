import { useEffect, useState, type ReactNode } from 'react';
import { SpectateScreen } from './spectate/SpectateScreen';
import { WeeklyGalaxyScreen } from './weekly/WeeklyGalaxyScreen';
import { useGameStore } from '../state/gameStore';
import type { MenuScreen } from '../state/session';
import { readAccount, readMenuScreen, takeGuestPlay, writeAccount, writeMenuScreen } from '../state/session';
import { GuestRegisterDialog } from './auth/GuestRegisterDialog';
import type { GameSummary } from '../api/types';
import { LoadGameDialog } from './LoadGameDialog';
import { gameApi } from '../api/client';
import type { DemoBattle } from '../api/types';
import { BattleScreen } from './battle/BattleScreen';
import { BalanceRunScreen } from './balance/BalanceRunScreen';
import { BalanceOpenScreen } from './balance/BalanceOpenScreen';
import { OrionometerScreen } from './orionometer/OrionometerScreen';
import { MilkyWayScreen } from './milkyway/MilkyWayScreen';
import { SoundLabScreen } from './soundlab/SoundLabScreen';
import { RegistrationRequestsScreen } from './auth/RegistrationRequestsScreen';
import { ProblemReportDialog } from './feedback/ProblemReportDialog';
import { ProblemReportsScreen } from './feedback/ProblemReportsScreen';
import { FoundersScreen } from './founders/FoundersScreen';
import { FounderOffer } from './founders/FounderOffer';
import { requestLoginForm } from './auth/AuthScreen';
import { offerDue, purchasable } from '../state/purchaseOffer';
import { RaceCostEditor } from './race/RaceCostEditor';
import { RaceSetupFlow, type PlayerSetup } from './race/RaceSetupFlow';
import type { BannerCode } from './race/banners';
import { LocaleSwitch } from './LocaleSwitch';
import { FullscreenSwitch } from './FullscreenSwitch';
import { LiveStats } from './LiveStats';
import { NewGameScreen, turnTimerLabel } from './newgame/NewGameScreen';
import { QuickStartScreen, QUICK_START_RACE } from './race/QuickStartScreen';
import { VeteranQuestion } from './preferences/VeteranQuestion';
import { noviceHintsAnswer, useHints } from '../state/hints';
import { ProfileScreen } from './profile/ProfileScreen';
import { tf, useT } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass } from './accent';
import { galaxySizeName } from './planetLabels';
import { Logo } from './brand/Logo';
import { CoverGalaxy } from './auth/CoverGalaxy';
import { WhatsNewDialog } from './NoticesDialog';
import { useRelease } from '../state/release';
import { InstallButton } from './InstallButton';
import { AboutScreen } from './about/AboutScreen';
import { markQuietCorner } from '../state/hudPresence';

interface MainMenuProps {
  /** «Сыграть с людьми» — field-backlog, пункт 65: стол подбора под именем учётной записи. */
  onQuickMatch: (playerName: string) => void;
  /** Тем же составом — пункт 149: из сцены конца, открытой в «Ваших партиях». */
  onRematch: (gameId: string) => void;
  onCreate: (payload: {
    gameName: string;
    galaxySize: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    /** Код готовой расы MOO II — п. 5; пусто — раса достанется по разнарядке. */
    raceCode?: string;
    /** Знамя — цвет империи, п. 3.2; пусто — знамя расы. */
    banner?: BannerCode;
    /** Идут ли в партии случайные галактические события — п. 11.1. */
    galacticEvents: boolean;
    /** Сколько всего империй в партии, считая свою, — п. 3. */
    totalPlayers: number;
    /** Пути к победе — п. 3: окно новой игры включает их по умолчанию. */
    council?: boolean;
    wardenholdVictory?: boolean;
    mightVictory?: boolean;
    /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
    turnSeconds?: number;
    /** Разрешить зрителей — field-backlog, пункт 59. */
    spectators?: boolean;
    /** Закрытая партия — пункт 67. */
    inviteOnly?: boolean;
    /** Ход итога по могуществу — пункт 24. */
    mightTurn?: number;
    /** Тихая галактика — пункт 186. */
    peacefulAi?: boolean;
    /** Особенность галактики — пункт 94. */
    galaxyFeature?: string;
    /** Уровень сложности — пункт 9. */
    aiSkill?: string;
    /** Галактика недели — пункт 146. */
    weekly?: boolean;
    /** Партию заводит обучение: она стартует сама и идёт с подсказками — п. 3. */
    tutorial?: boolean;
    /** Партию заводит гость: стартует сама, минуя лобби — backlog-promo, пункт 1. */
    guest?: boolean;
  }) => void;
  onJoin: (payload: {
    gameId: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    /** Код готовой расы MOO II — п. 5; пусто — раса достанется по разнарядке. */
    raceCode?: string;
    /** Знамя — цвет империи, п. 3.2. */
    banner?: BannerCode;
  }) => void;
  onRefresh: () => void;
  onLoadSave: (saveId: string, file?: string) => void;
  onRejoin: (gameId: string) => void;
  /** Выход из учётной записи — п. 3.1: возвращает к форме входа. */
  onSignOut: () => void;
  /**
   * Ввод прошлой попытки: имя игрока, родная звезда, собранная раса, название партии
   * и размер галактики.
   *
   * Меню разбирается и собирается заново на каждый заход в него, поэтому своё состояние
   * оно пережить не может: неудачная попытка создать партию возвращает игрока сюда, и без
   * этих значений собранная раса пропадала бы вместе с ней. Значения живут в машине
   * экранов — она переживает смену экранов, а меню нет.
   */
  initial: {
    gameName: string;
    galaxySize: string;
    playerName: string;
    homeStarName: string;
    raceName: string;
    raceTraits: string[];
    totalPlayers: number;
    council: boolean;
    wardenholdVictory: boolean;
    mightVictory: boolean;
    turnSeconds?: number;
    spectators: boolean;
    inviteOnly: boolean;
    mightTurn: number;
    peacefulAi: boolean;
    galaxyFeature: string;
    aiSkill: string;
  };
}

/** Что открыл игрок: свою партию или чужую из списка. */
/** `weekly` — партия галактики недели (пункт 146): условия назначает сервер, игрок выбирает расу. */
type SetupTarget = { kind: 'create'; weekly?: boolean } | { kind: 'join'; game: GameSummary };

/**
 * Главное меню — п. 11.1: только текстовые ссылки.
 *
 * Здесь же выбирается размер галактики перед стартом (п. 3): по умолчанию Huge,
 * значение приходит из справочника сервера.
 *
 * Имя игрока, название его родной звезды и собранная им раса (п. 7) спрашиваются экраном
 * выбора расы — и на входе в свою партию, и на входе в чужую: это настройки участника,
 * а не галактики. Экран занимает окно целиком, как окно race picks в MOO II. Последние
 * введённые значения меню помнит и подставляет при следующем открытии.
 */
export function MainMenu({
  onCreate,
  onJoin,
  onQuickMatch,
  onRefresh,
  onLoadSave,
  onSignOut,
  initial,
}: MainMenuProps) {
  const { t } = useT();
  // Кто вошёл — п. 3.1: имя из учётной записи, а не из настроек партии.
  const account = readAccount()?.account ?? null;
  /*
    ГОСТЬ — backlog-promo, пункт 1. Меню у него короче: одна своя партия с настройками,
    которые назначает сервер, и ни чужих партий, ни сохранений — сервер их гостю и так не
    отдаст, а ссылка, отвечающая отказом, читается как поломка.
  */
  const guest = account?.role === 'GUEST';
  /**
   * Выход гостя спрашивает подтверждения вторым нажатием: ключ от гостевой записи — только
   * пропуск в этом браузере, и выход теряет её вместе с партиями навсегда.
   */
  const [guestLeaving, setGuestLeaving] = useState(false);
  /*
    Войти в свою запись из гостевой (08.10.2026, решение хозяина проекта): у гостя был только
    «выйти», и пришедший уже зарегистрированным не находил входа. Вход — это тот же выход из
    гостевой записи (её партии пропадут), поэтому подтверждение то же, а экран входа сразу
    открывается формой входа (`requestLoginForm`).
  */
  const [guestSigningIn, setGuestSigningIn] = useState(false);
  const signIn = () => {
    if (!guestSigningIn) {
      setGuestSigningIn(true);
      setGuestLeaving(false);
      return;
    }
    requestLoginForm();
    onSignOut();
  };
  /** Окно регистрации гостя — field-backlog, пункт 1: запись превращается, а не меняется. */
  const [registering, setRegistering] = useState(false);
  const signOut = () => {
    if (guest && !guestLeaving) {
      setGuestLeaving(true);
      setGuestSigningIn(false);
      return;
    }
    onSignOut();
  };
  const galaxySizes = useGameStore((state) => state.galaxySizes);
  const openGames = useGameStore((state) => state.openGames);
  // Сыгранные вместе (пункт 148): спрашиваются, когда меняется список открытых партий.
  const [together, setTogether] = useState<Record<string, number>>({});
  useEffect(() => {
    if (readAccount()?.account.role === 'GUEST' || openGames.length === 0) {
      return;
    }
    gameApi.playedTogether().then(setTogether).catch(() => undefined);
  }, [openGames]);
  const lastError = useGameStore((state) => state.lastError);

  // Запасное значение нужно только до загрузки справочника: умолчание называет сервер.
  const defaultSize = galaxySizes.find((size) => size.defaultChoice)?.code ?? 'SMALL';
  // Игровой псевдоним по умолчанию — личный кабинет: имя записи, у гостя — общее «Игрок».
  const [playerName, setPlayerName] = useState(initial.playerName
    || (readAccount()?.account.role === 'GUEST' ? '' : readAccount()?.account.name ?? '')
    || t('menu.player.default'));
  const [homeStarName, setHomeStarName] = useState(initial.homeStarName);
  const [raceName, setRaceName] = useState(initial.raceName);
  const [raceTraits, setRaceTraits] = useState<string[]>(initial.raceTraits);
  const [gameName, setGameName] = useState(initial.gameName);
  const [galaxySize, setGalaxySize] = useState(initial.galaxySize || defaultSize);
  // Случайные события — п. 11.1: в MOO II их выключают в окне новой игры, и здесь тоже.
  const [galacticEvents, setGalacticEvents] = useState(true);
  // Сколько империй в партии — п. 3: там же, в окне новой игры.
  const [totalPlayers, setTotalPlayers] = useState(initial.totalPlayers);
  // Пути к победе — п. 3, там же, в окне новой игры, и включены по умолчанию.
  const [council, setCouncil] = useState(initial.council);
  const [wardenholdVictory, setWardenholdVictory] = useState(initial.wardenholdVictory);
  const [mightVictory, setMightVictory] = useState(initial.mightVictory);
  const [turnSeconds, setTurnSeconds] = useState<number | undefined>(initial.turnSeconds);
  const [spectators, setSpectators] = useState(initial.spectators);
  const [inviteOnly, setInviteOnly] = useState(initial.inviteOnly);
  const [mightTurn, setMightTurn] = useState(initial.mightTurn);
  const [peacefulAi, setPeacefulAi] = useState(initial.peacefulAi);
  const [galaxyFeature, setGalaxyFeature] = useState(initial.galaxyFeature);
  const [aiSkill, setAiSkill] = useState(initial.aiSkill);
  /*
    ОТКРЫТЫЙ ЭКРАН ПОМНИТСЯ МЕЖДУ ПЕРЕСБОРКАМИ ДЕРЕВА — state/session.ts.

    Было пять булевых флагов в состоянии самого меню, и жили они ровно до следующей
    перерисовки всего приложения: перезагрузка страницы, горячая замена модуля у
    dev-сервера, возврат машины экранов в `menu` — и пульт закрывался сам, выбрасывая
    хозяина на первый экран. За прогоном сидят часами, и это потеря работы, а не мелочь.
    Экраны эти и так открываются по одному, поэтому вместо пяти флагов одно поле.
  */
  const [screen, setScreenState] = useState<MenuScreen>(() => readMenuScreen());
  /** «Об игре» с титрами (пункт 48) — своим состоянием: экран не переживает перезагрузку, и незачем. */
  const [aboutOpen, setAboutOpen] = useState(false);
  const setScreen = (next: MenuScreen) => {
    setScreenState(next);
    writeMenuScreen(next);
  };
  const loadOpen = screen === 'saves';
  const costsOpen = screen === 'costs';
  const accountsOpen = screen === 'accounts';
  const requestsOpen = screen === 'requests';
  /**
   * Раскрытая группа меню администратора — «Модерация» или «Инструменты администратора»
   * (07.10.2026, решение хозяина проекта): всё, что видит только администратор, собрано в две
   * группы, и закрытые они занимают две строки, а не полтора десятка.
   */
  const [adminGroup, setAdminGroup] = useState<'moderation' | 'tools' | null>(null);
  // Значок звука в углу меню не нужен (07.10.2026): звук правят в личном кабинете.
  useEffect(() => markQuietCorner(), []);
  /**
   * Сколько регистраций ждут подтверждения — п. 3.1: число рядом со ссылкой.
   *
   * Спрашивается только у администратора и только в меню: остальным список не отдадут
   * вовсе, а без числа ссылка ничем не отличается от прочих, и застрявшая регистрация
   * ждала бы, пока о ней вспомнят. Перечитывается при закрытии страницы — подтверждённая
   * запись должна уходить из счётчика сразу.
   */
  const [waitingAccounts, setWaitingAccounts] = useState(0);
  /** Сообщения о проблемах, ждущие разбора, — число у ссылки хозяина (field-backlog, пункт 157). */
  const [waitingProblems, setWaitingProblems] = useState(0);
  const problemsOpen = screen === 'problems';
  const foundersOpen = screen === 'founders';
  /** Окно «Сообщить о проблеме» — field-backlog, пункт 157. */
  const [reporting, setReporting] = useState(false);
  // Номер версии и весь список «что нового» по нему — field-backlog, пункт 159.
  const release = useRelease();
  const [whatsNew, setWhatsNew] = useState(false);
  // Обложка нашлась: файла может не быть — тогда на её месте Галактика (как на экране входа).
  const [coverPhoto, setCoverPhoto] = useState(true);
  const milkyWayOpen = screen === 'milkyway';
  const newGameOpen = screen === 'newgame';
  const balanceOpen = screen === 'balance';
  const orionometerOpen = screen === 'orionometer';
  const preferencesOpen = screen === 'preferences';
  const [demo, setDemo] = useState<DemoBattle | null>(null);
  const [demoBusy, setDemoBusy] = useState(false);
  const [setup, setSetup] = useState<SetupTarget | null>(null);
  // Один экран вместо трёх у гостя — field-backlog, пункт 3: «играть сразу» или «собрать свою».
  const [quickStart, setQuickStart] = useState(false);
  // Вопрос ветерану — пункт 30: один раз, пока запись на него не ответила.
  useHints();
  const askVeteran = noviceHintsAnswer() === undefined;
  /*
    «Играть» с экрана входа ведёт сразу к выбору расы (backlog-promo, пункт 19): гость
    нажимал «играть» и попадал в меню, где нужно было нажать «новая партия» ещё раз — лишний
    шаг ровно там, где новичок решает, остаться ли. Метку ставит экран входа
    (`GUEST_PLAY_KEY`) и только на этот переход: вернувшийся в меню из партии видит меню.
  */
  useEffect(() => {
    if (guest && takeGuestPlay()) {
      setQuickStart(true);
    }
  }, [guest]);
  /*
    Гость мог подтвердить почту В ДРУГОМ браузере (field-backlog, пункт 1): запись на сервере
    уже игрок, а здесь в хранилище всё ещё «гость», и меню оставалось бы гостевым. Поэтому
    меню гостя спрашивает запись у сервера и, если роль сменилась, переписывает хранимую.
  */
  const [, setAccountVersion] = useState(0);
  useEffect(() => {
    const stored = readAccount();
    if (!guest || !stored) {
      return;
    }
    gameApi
      .currentAccount()
      .then((fresh) => {
        if (fresh.role !== stored.account.role) {
          writeAccount({ ...stored, account: fresh });
          setAccountVersion((version) => version + 1);
        }
      })
      .catch(() => undefined);
  }, [guest]);
  // Знамя — п. 3.2: последнее выбранное подставляется при следующем входе в партию.
  const [banner, setBanner] = useState<BannerCode | null>(null);
  /*
    Цвета тех, кто уже сидит в чужой партии: их знамёна на экране выбора гаснут. Список
    открытых игр состава не несёт, поэтому он берётся запросом в миг входа — а на создание
    своей партии занятых знамён нет вовсе.
  */
  const [takenColors, setTakenColors] = useState<string[]>([]);
  useEffect(() => {
    if (setup?.kind !== 'join') {
      setTakenColors([]);
      return;
    }
    let alive = true;
    gameApi
      .getGame(setup.game.id)
      .then((details) => {
        if (alive) {
          setTakenColors(details.players.map((player) => player.color).filter(Boolean));
        }
      })
      // Не узнали — экран покажет все знамёна, а занятое отклонит сервер словами.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [setup]);

  // Счётчик запросов: пока страница открыта, его не трогаем — она сама показывает список.
  useEffect(() => {
    if (account?.role !== 'ADMIN' || accountsOpen || requestsOpen) {
      return;
    }
    let cancelled = false;
    gameApi
      .listAccounts()
      .then((accounts) => {
        if (!cancelled) {
          setWaitingAccounts(accounts.filter((entry) => !entry.confirmed).length);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [account?.role, accountsOpen, requestsOpen]);

  /*
    Ссылка на пакет основателя видна, только когда страница есть для этого читателя: игроку —
    опубликованная, администратору — и черновик (с пометкой). Узнаётся это тем же запросом, что
    и сама страница: пустой ответ («не опубликована») и значит «ссылки нет». После правки спрашивается
    заново — администратор мог снять или включить публикацию.
  */
  const [foundersLink, setFoundersLink] = useState<'none' | 'published' | 'draft'>('none');
  // Предложение пакета после успеха (пункты 25, 175, 48): только записи с историей, не гостю и не
  // тому, у кого ранний доступ уже есть, и только когда купить и правда можно (пункт 38).
  const [offer, setOffer] = useState(false);
  const refreshFounders = () => {
    gameApi
      .founderPage()
      .then((page) => {
        setFoundersLink(page === undefined ? 'none' : page.published ? 'published' : 'draft');
        const holder = (account?.entitlements ?? []).some((kind) => kind === 'FOUNDER' || kind === 'EARLY_ACCESS');
        setOffer(purchasable(page) && !!account && account.role !== 'GUEST' && !holder && offerDue());
      })
      .catch(() => setFoundersLink('none'));
  };
  useEffect(refreshFounders, [account?.role]);

  // Счётчик сообщений о проблемах — тем же правилом, что и счётчик запросов.
  useEffect(() => {
    if (account?.role !== 'ADMIN' || problemsOpen) {
      return;
    }
    let cancelled = false;
    gameApi
      .pendingProblems()
      .then((count) => !cancelled && setWaitingProblems(count))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [account?.role, problemsOpen]);

  const confirmSetup = (target: SetupTarget, value: PlayerSetup) => {
    setPlayerName(value.playerName);
    setHomeStarName(value.homeStarName);
    setRaceName(value.raceName);
    setRaceTraits(value.raceTraits);
    setBanner(value.banner);
    setSetup(null);

    if (target.kind === 'create' && target.weekly) {
      // Условия недели назначает сервер (`WeeklyGalaxyRules`); здесь — те же, чтобы экран не спорил.
      onCreate({ gameName: t('weekly.gameName'), galaxySize: 'SMALL', galacticEvents: true, totalPlayers: 4,
        council: true, wardenholdVictory: true, mightVictory: true, weekly: true, ...value });
      return;
    }
    if (target.kind === 'create') {
      // Гостю настройки назначает сервер (`GuestGameRules`); клиент шлёт те же, чтобы
      // экран и партия говорили об одном и том же, а не полагались на подмену.
      onCreate(guest
        ? { gameName: gameName.trim(), galaxySize: 'SMALL', galacticEvents: true, totalPlayers: 4,
            council: true, wardenholdVictory: true, mightVictory: true, guest: true, ...value }
        : { gameName: gameName.trim(), galaxySize, galacticEvents, totalPlayers,
            council, wardenholdVictory, mightVictory, turnSeconds, spectators, inviteOnly, mightTurn, peacefulAi,
            galaxyFeature, aiSkill, ...value });
      return;
    }
    onJoin({ gameId: target.game.id, ...value });
  };

  return (
    /*
      Прокрутка, а не обрезка: у страницы `overflow: hidden` (карта галактики не должна
      уезжать под курсором), поэтому меню обязано прокручиваться само. Раньше оно было
      выровнено по центру без прокрутки — на невысоком окне список открытых игр уходил
      за край экрана, и добраться до него было нечем.

      Центрирование при этом осталось: `my-auto` у содержимого центрирует его, пока оно
      помещается, и схлопывается в ноль, когда не помещается, — тогда экран прокручивается
      от самого верха. Одним `items-center` так не выходит: верх содержимого срезается и
      прокруткой не достаётся.
    */
    <div className="relative flex h-full justify-center overflow-y-auto px-8 pb-8 pt-12">
      {loadOpen ? (
        <LoadGameDialog
          onLoad={(saveId) => {
            setScreen(null);
            onLoadSave(saveId);
          }}
          onImport={(content) => {
            setScreen(null);
            onLoadSave('', content);
          }}
          onClose={() => setScreen(null)}
        />
      ) : null}

      {/*
        Экраны администратора заперты и НА ОТРИСОВКЕ, а не только ссылкой: меню помнит
        открытый экран между перезагрузками (state/session.ts), и спрятанная ссылка дверь
        не закрывает. Редактор цен до сих пор был виден всем — у кого он запомнился
        открытым, тот увидел бы его снова и без ссылки.
      */}
      {costsOpen && account?.role === 'ADMIN' ? (
        <RaceCostEditor onClose={() => setScreen(null)} />
      ) : null}
      {/*
        Запросы на регистрацию — п. 3.1: страница администратора. Открывается из двух мест
        (ссылка при имени вошедшего и ссылка в списке до партии), но экран один: действие
        там одно и то же, и второй такой же список разъезжался бы с первым.
      */}
      {accountsOpen && account?.role === 'ADMIN' ? (
        <RegistrationRequestsScreen mode="search" onClose={() => setScreen(null)} />
      ) : null}
      {requestsOpen && account?.role === 'ADMIN' ? (
        <RegistrationRequestsScreen mode="requests" onClose={() => setScreen(null)} />
      ) : null}
      {problemsOpen && account?.role === 'ADMIN' ? <ProblemReportsScreen onClose={() => setScreen(null)} /> : null}
      {foundersOpen ? (
        <FoundersScreen isAdmin={account?.role === 'ADMIN'} onClose={() => { setScreen(null); refreshFounders(); }} />
      ) : null}
      {/* Демонстрационная сцена: трёхмерная схема Млечного Пути на сто элементов. */}
      {milkyWayOpen && account?.role === 'ADMIN' ? <MilkyWayScreen onClose={() => setScreen(null)} /> : null}
      {/*
        Окно новой игры — п. 3: настройки партии спрашиваются до выбора расы, как в
        MOO II, где New Game Menu стоит перед Race Selection.
      */}
      {newGameOpen ? (
        <NewGameScreen
          gameName={gameName}
          galaxySize={galaxySize}
          totalPlayers={totalPlayers}
          galacticEvents={galacticEvents}
          council={council}
          wardenholdVictory={wardenholdVictory}
          mightVictory={mightVictory}
          turnSeconds={turnSeconds}
          onTurnSeconds={setTurnSeconds}
          spectators={spectators}
          onSpectators={setSpectators}
          inviteOnly={inviteOnly}
          onInviteOnly={setInviteOnly}
          mightTurn={mightTurn}
          onMightTurn={setMightTurn}
          peacefulAi={peacefulAi}
          onPeacefulAi={setPeacefulAi}
          onEvening={() => {
            // Партия под вечер (пункт 24): малая галактика, три империи, итог на сотом ходу.
            setGalaxySize('SMALL');
            setTotalPlayers(3);
            setMightVictory(true);
            setMightTurn(100);
          }}
          galaxyFeature={galaxyFeature}
          onGalaxyFeature={setGalaxyFeature}
          aiSkill={aiSkill}
          onAiSkill={setAiSkill}
          onGameName={setGameName}
          onGalaxySize={setGalaxySize}
          onTotalPlayers={setTotalPlayers}
          onGalacticEvents={setGalacticEvents}
          onCouncil={setCouncil}
          onWardenholdVictory={setWardenholdVictory}
          onMightVictory={setMightVictory}
          onAccept={() => {
            setScreen(null);
            setSetup({ kind: 'create' });
          }}
          onCancel={() => setScreen(null)}
        />
      ) : null}
      {balanceOpen && account?.role === 'ADMIN' ? (
        <BalanceRunScreen onClose={() => setScreen(null)} />
      ) : null}
      {orionometerOpen && account?.role === 'ADMIN' ? (
        <OrionometerScreen onClose={() => setScreen(null)} />
      ) : null}
      {screen === 'spectate' ? <SpectateScreen onClose={() => setScreen(null)} /> : null}
      {/* Баланс рас в открытую — пункты 47, 70, 101: администратору, модератору и основателю. */}
      {screen === 'openBalance' && account?.insider ? <BalanceOpenScreen onClose={() => setScreen(null)} /> : null}
      {screen === 'weekly' ? (
        <WeeklyGalaxyScreen
          onClose={() => setScreen(null)}
          onPlay={() => {
            setScreen(null);
            setSetup({ kind: 'create', weekly: true });
          }}
        />
      ) : null}
      {screen === 'soundlab' && account?.role === 'ADMIN' ? (
        <SoundLabScreen onClose={() => setScreen(null)} />
      ) : null}
      {/*
        Настройки игрока — п. 11.1: раскладка горячих клавиш. Экран ОДИН на оба входа — из
        меню и из партии: настройка там и там одна и та же, а второй такой же экран
        разъехался бы с первым.
      */}
      {preferencesOpen ? <ProfileScreen onClose={() => setScreen(null)} /> : null}
      {aboutOpen ? <AboutScreen onClose={() => setAboutOpen(false)} /> : null}

      {/* Демонстрация боя — п. 8: та же сцена, что и в партии, но ведёт её сервер. */}
      {demo ? (
        <BattleScreen battle={demo.battle} demo={demo} onClose={() => setDemo(null)} />
      ) : null}

      {/*
        Гость начинает сразу — пункт 3: «Играть сразу» заводит партию готовой расой, знамя
        которой и имена назначает игра (пустые поля — сервер выберет сам), а «Собрать свою» —
        прежний путь в три экрана.
      */}
      {askVeteran && !quickStart && !setup ? <VeteranQuestion /> : null}
      {quickStart ? (
        <QuickStartScreen
          onClose={() => setQuickStart(false)}
          onCustom={() => {
            setQuickStart(false);
            setSetup({ kind: 'create' });
          }}
          onQuick={() => {
            setQuickStart(false);
            onCreate({ gameName: gameName.trim(), galaxySize: 'SMALL', galacticEvents: true, totalPlayers: 4,
                       council: true, wardenholdVictory: true, mightVictory: true, guest: true,
                       playerName, homeStarName: '', raceName: '', raceTraits: [], raceCode: QUICK_START_RACE });
          }}
        />
      ) : null}
      {setup ? (
        <RaceSetupFlow
          title={setup.kind === 'create' ? t('menu.setup.new') : t('menu.setup.join', { name: setup.game.name })}
          confirmLabel={setup.kind === 'create' ? t('menu.setup.create') : t('menu.setup.confirmJoin')}
          playerName={playerName}
          homeStarName={homeStarName}
          raceName={raceName}
          raceTraits={raceTraits}
          banner={banner}
          takenColors={takenColors}
          onConfirm={(value) => confirmSetup(setup, value)}
          onClose={() => setSetup(null)}
        />
      ) : null}
      {/*
        Обложка — та же, что на экране входа (05.10.2026, решение хозяина проекта): слева знак
        «Венец», строка названия и космонавт с нашей галактикой в стекле шлема, справа меню.
        Картинка берётся ПО АДРЕСУ (`public/brand/cover-astronaut.webp`); не нашёлся файл —
        на её месте медленно поворачивающаяся Галактика, рисуемая самой игрой. Подписи о
        настроении у картинки нет намеренно: настроение не называют — его делают (правила проекта).
        На узком экране колонка обложки встаёт над меню и картинку прячет: меню важнее.
      */}
      <div className="my-auto flex w-full max-w-7xl flex-col gap-8 lg:flex-row lg:items-start">
      <aside className="w-full lg:sticky lg:top-6 lg:w-[30rem] lg:flex-none">
        {/* Название игры вдвое крупнее (07.10.2026, решение хозяина проекта). */}
        <h1>
          <Logo height={5.5} mark="crown" />
        </h1>
        <p className="mt-1 text-xs uppercase tracking-[0.35em] text-ink-dim">
          {t('app.tagline')}
        </p>
        {/* Подложка цвета страницы: «прилипшая» колонка — свой слой, и смешивать картинку иначе не с чем. */}
        <figure className="mt-6 hidden bg-space-950 lg:block">
          {coverPhoto ? (
            /*
              Без рамки, и чёрный фон картинки растворяется в фоне экрана (07.10.2026, решение хозяина
              проекта): «осветление» оставляет вместо чёрного фон сцены. Маски больше нет (08.10.2026):
              она гасила низ картинки, и космонавт выглядел обрезанным по колено, — а края и без неё
              растворяются, потому что фон картинки чёрный.
            */
            <img
              src="/brand/cover-astronaut.webp"
              alt={t('cover.astronaut.alt')}
              className="mx-auto block max-h-[calc(100vh-14rem)] min-h-[16rem] w-auto max-w-full"
              style={{ mixBlendMode: 'lighten' }}
              onError={() => setCoverPhoto(false)}
            />
          ) : (
            <CoverGalaxy label={t('cover.galaxy.alt')} />
          )}
        </figure>
      </aside>
      <div className="grid w-full min-w-0 flex-1 grid-cols-1 gap-6 lg:grid-cols-2">
        <header className="lg:col-span-2 flex items-baseline justify-end gap-4">
          {/* Кто вошёл и выход — п. 3.1: без этого сменить учётную запись было бы нечем. */}
          {account ? (
            <div className="text-right">
              {/*
                Кто вошёл (07.10.2026, решение хозяина проекта): имя белым и вдвое крупнее, справа
                почта, ещё правее роль. Звук и размер текста отсюда ушли в личный кабинет.
              */}
              <div className="flex flex-wrap items-baseline justify-end gap-x-4">
                <span className="text-22 text-white">
                  {guest ? t('account.guest.name', { n: account.name }) : account.name || account.login}
                </span>
                {!guest && (account.email ?? (account.login.includes('@') ? account.login : '')) ? (
                  <span className="text-14 text-ink-soft">{account.email ?? account.login}</span>
                ) : null}
                {/*
                  Роль переводится здесь, а не берётся из roleLabel: тот пришёл на языке
                  запроса входа и лежит в localStorage — переключение языка его бы не тронуло.
                  У гостя роль уже названа в имени («Гость 4821») — второй раз незачем.
                */}
                {guest ? null : (
                  <span className="text-11 text-ink-faint">
                    {t(account.role === 'ADMIN' ? 'account.role.ADMIN'
                      : account.role === 'MODERATOR' ? 'account.role.MODERATOR' : 'account.role.PLAYER')}
                  </span>
                )}
              </div>
              <div className="mt-1 flex justify-end gap-3 text-11 text-ink-dim">
                <button type="button" className={`link text-11${guestLeaving ? ' text-warn' : ''}`}
                        onClick={signOut}>
                  {guestLeaving ? t('menu.guest.signOut.confirm') : t('menu.signOut')}
                </button>
                {guest ? (
                  <button type="button" className={`link text-11${guestSigningIn ? ' text-warn' : ''}`}
                          onClick={signIn}>
                    {guestSigningIn ? t('menu.guest.signIn.confirm') : t('menu.signIn')}
                  </button>
                ) : null}
                {/* Полный экран — слева от языка (09.10.2026): подсказка называет клавишу. */}
                <FullscreenSwitch className="link hit-24 text-ink-faint hover:text-accent" />
                <LocaleSwitch />
              </div>
            </div>
          ) : null}
        </header>
        {offer ? (
          <div className="lg:col-span-2 flex justify-center">
            <FounderOffer onOpen={() => { setOffer(false); setScreen('founders'); }} onDone={() => setOffer(false)} />
          </div>
        ) : null}

        {lastError ? (
          <div className="border border-red-800 bg-red-950/40 px-3 py-2 text-xs text-danger lg:col-span-2">
            {lastError}
          </div>
        ) : null}

        {/*
          Новая игра (07.10.2026, решение хозяина проекта): все пункты ОДНОГО размера, подписи
          ушли во всплывающие подсказки, а то, что видит только администратор, собрано в две
          группы — «Модерация» и «Инструменты администратора». «Ваших партий» больше нет:
          вернуться в партию помогает автосохранение, а история партий живёт в личном кабинете.
        */}
        <section className="panel">
          <div className="panel-title">{t('menu.newGame')}</div>
          <div className="space-y-2">
            {/*
              Гостевая партия: окна новой игры гость не видит — настройки назначены, — и сразу
              выбирает расу. На чём партия кончается, сказано подсказкой.
            */}
            {guest ? (
              <MenuButton tone="text-accent" tip={t('menu.guest.rules')} onClick={() => setQuickStart(true)}>
                {t('menu.guest.create')}
              </MenuButton>
            ) : null}

            {/*
              ОБУЧЕНИЕ — п. 3, первым пунктом нарочно: единственный вход, который ведёт за руку.
              Оно НИЧЕГО НЕ СПРАШИВАЕТ: малая галактика, один соперник, события и совет выключены.
              Обучение, окно новой игры и сохранения гостю не показываются: сервер всё равно
              подменил бы обучение гостевой партией на четверых, а сохранений гостю не отдаст.
            */}
            {guest ? null : (
              <>
                <MenuButton
                  tone="text-accent"
                  tip={t('menu.tutorial.hint')}
                  onClick={() =>
                    onCreate({
                      gameName: t('menu.tutorial'),
                      galaxySize: 'SMALL',
                      playerName: playerName || t('menu.player.default'),
                      homeStarName: '',
                      raceName: '',
                      raceTraits: [],
                      // Земляне: раса без перекосов, и первую партию новичок играет ею.
                      raceCode: 'HUMANS',
                      galacticEvents: false,
                      totalPlayers: 2,
                      tutorial: true,
                    })
                  }
                >
                  {t('menu.tutorial')}
                </MenuButton>
                {/* Настройки партии спрашивает окно новой игры, а расу — экран за ним. */}
                <MenuButton tone="text-accent" onClick={() => setScreen('newgame')}>
                  {t('menu.create')}
                </MenuButton>
                {/* Сохранения лежат на сервере, поэтому список доступен и до входа в партию. */}
                <MenuButton onClick={() => setScreen('saves')}>{t('menu.load')}</MenuButton>
              </>
            )}

            {/*
              Личный кабинет: учётная запись, свои расы, проекты кораблей, порядки стройки, история
              партий и настройки — всё, что принадлежит записи, а не партии, одним входом.
            */}
            <MenuButton onClick={() => setScreen('preferences')}>{t('menu.profile')}</MenuButton>

            {/* Галактика недели — пункт 146: одна галактика для всех, итоги по расам. Гостю — после регистрации. */}
            {!guest ? (
              <MenuButton tone="text-lamp" onClick={() => setScreen('weekly')}>{t('menu.weekly')}</MenuButton>
            ) : null}

            {/* Смотреть чужие партии — пункт 59: так видят, как в игру играют другие. */}
            <MenuButton onClick={() => setScreen('spectate')}>{t('menu.spectate')}</MenuButton>

            {/* Баланс рас — пункты 47, 70, 101: что намерил прибор; администратору, модератору и основателю. */}
            {account?.insider ? (
              <MenuButton onClick={() => setScreen('openBalance')}>{t('menu.openBalance')}</MenuButton>
            ) : null}

            {/* Установить игру — field-backlog, пункт 203: своя кнопка, а не значок браузера. */}
            <InstallButton plain className={MENU_BUTTON_CLASS + ' text-lamp'} />

            {/* Пакет основателя — и гостю тоже: страницу читают те, кто ещё решает. */}
            {foundersLink !== 'none' ? (
              <MenuButton tone="text-lamp" onClick={() => setScreen('founders')}>
                <span>
                  {t('menu.founders')}
                  {foundersLink === 'draft' ? <span className="ml-2 text-ink-faint">({t('founders.draft')})</span> : null}
                </span>
              </MenuButton>
            ) : null}

            {account?.role === 'ADMIN' ? (
              <>
                {/*
                  Модерация — п. 3.1 и пункт 157: учётные записи с поиском, заявки на регистрацию,
                  ждущие ручного подтверждения, и сообщения о проблемах. Число у группы — сколько
                  всего ждёт решения: закрытая группа не должна прятать то, что ждёт.
                */}
                <MenuGroup
                  label={t('menu.moderation')}
                  badge={waitingAccounts + waitingProblems}
                  open={adminGroup === 'moderation'}
                  onToggle={() => setAdminGroup(adminGroup === 'moderation' ? null : 'moderation')}
                >
                  <MenuButton onClick={() => setScreen('accounts')}>{t('menu.accounts')}</MenuButton>
                  <MenuButton badge={waitingAccounts} onClick={() => setScreen('requests')}>
                    {t('menu.requests')}
                  </MenuButton>
                  <MenuButton badge={waitingProblems} onClick={() => setScreen('problems')}>
                    {t('menu.problems')}
                  </MenuButton>
                </MenuGroup>

                {/*
                  Инструменты администратора: демонстрации, правка справочника и хозяйство машины.
                  Демонстрационный бой и схема Млечного Пути переехали сюда же (07.10.2026).
                */}
                <MenuGroup
                  label={t('menu.adminTools')}
                  open={adminGroup === 'tools'}
                  onToggle={() => setAdminGroup(adminGroup === 'tools' ? null : 'tools')}
                >
                  <MenuHeading>{t('menu.admin.demo')}</MenuHeading>
                  <MenuButton
                    disabled={demoBusy}
                    onClick={() => {
                      setDemoBusy(true);
                      gameApi.reference
                        .startDemoBattle()
                        .then(setDemo)
                        .finally(() => setDemoBusy(false));
                    }}
                  >
                    {demoBusy ? t('menu.demo.busy') : t('menu.demo')}
                  </MenuButton>
                  <MenuButton onClick={() => setScreen('milkyway')}>{t('menu.milkyway')}</MenuButton>
                  <MenuHeading>{t('menu.admin.design')}</MenuHeading>
                  <MenuButton onClick={() => setScreen('costs')}>{t('menu.costs')}</MenuButton>
                  <MenuHeading>{t('menu.admin.balance')}</MenuHeading>
                  <MenuButton onClick={() => setScreen('balance')}>{t('menu.balance')}</MenuButton>
                  <MenuButton onClick={() => setScreen('orionometer')}>{t('menu.orionometer')}</MenuButton>
                  <MenuButton onClick={() => setScreen('soundlab')}>{t('menu.soundlab')}</MenuButton>
                </MenuGroup>
              </>
            ) : null}
          </div>
        </section>

        {/*
          Гостю вместо списка открытых игр — зов к регистрации: войти в чужую партию он не
          может, и пустая дверь без объяснения читалась бы как поломка. Регистрация отсюда —
          это выход с подтверждением: гостевую запись в зарегистрированную пока не
          превращаем (backlog-promo, пункт 1, — следующий шаг).
        */}
        {guest ? (
          <section className="panel">
            <div className="panel-title">{t('menu.guest.register.title')}</div>
            <p className="mb-3 text-xs text-ink-soft">{t('menu.guest.register.text')}</p>
            {/* Регистрация НЕ выходит из записи (field-backlog, пункты 1 и 126): та же запись
                получает почту и пароль, и партии остаются за ней. */}
            <button type="button" className="link text-xs" onClick={() => setRegistering(true)}>
              ▸ {t('menu.guest.register.action')}
            </button>
            {registering ? <GuestRegisterDialog onClose={() => setRegistering(false)} /> : null}
          </section>
        ) : (
        /* Список открытых игр */
        <section className="panel">
          <div className="flex items-baseline justify-between">
            <div className={`panel-title ${figureLabelClass('note')}`}>
              {t('menu.openGames')}
              {openGames.length > 0 ? (
                <Figure accent="note" className="ml-2">{openGames.length}</Figure>
              ) : null}
            </div>
            <button type="button" className="link text-xs" onClick={onRefresh}>
              {t('menu.refresh')}
            </button>
          </div>

          {/*
            «Сыграть с людьми» — field-backlog, пункт 65: пустой список открытых партий —
            холодный старт, видимый каждому. Кнопка сажает за ближайший стол подбора или заводит
            новый, и стол стартует сам, когда за ним двое людей, — пустоты игрок не видит.
          */}
          <div className="mb-2">
            <button
              type="button"
              className="link text-accent"
              onClick={() => onQuickMatch(account?.name ?? account?.login ?? 'Player')}
            >
              ▸ {t('menu.quickMatch')}
            </button>
            <p className="text-11 text-ink-dim">{t('menu.quickMatch.hint', { minutes: 5 })}</p>
          </div>
          {openGames.length === 0 ? (
            <p className="text-xs text-ink-dim">{t('menu.noGames')}</p>
          ) : (
            /*
              Список со своей прокруткой: открытых партий на сервере бывает много, и без
              предела высоты панель растягивала бы меню на несколько экранов — а рядом с
              ней стоит «Новая игра», до которой тогда надо было прокручивать.
            */
            <ul className="max-h-[22rem] space-y-2 overflow-y-auto pr-1">
              {openGames.map((game) => (
                <GameRow
                  key={game.id}
                  game={game}
                  together={together[game.id]}
                  onJoin={() => setSetup({ kind: 'join', game })}
                  canJoin={game.humanPlayers < game.maxHumanPlayers}
                />
              ))}
            </ul>
          )}
        </section>
        )}

        {/* Признаки живой игры — пункт 45: пока только администратору и модератору. */}
        {account?.role === 'ADMIN' || account?.role === 'MODERATOR' ? <LiveStats /> : null}
        {/*
          Номер версии — field-backlog, пункт 159: его называют в каждом сообщении о проблеме,
          а по нему открывается весь список «что нового». Внизу и тихо: смотрят на него редко,
          но найти его должно быть можно всегда.
        */}
        {release ? (
          <div className="text-center text-11 text-ink-faint lg:col-span-2">
            <button type="button" className="link-quiet" onClick={() => setWhatsNew(true)}>
              {t('menu.version', { version: release })} · {t('menu.whatsNew')}
            </button>
            {' · '}
            {/* Об игре и титры (backlog-promo, пункт 48): атрибуции чужого должны быть видны игроку. */}
            <button type="button" className="link-quiet" onClick={() => setAboutOpen(true)}>
              {t('menu.about')}
            </button>
          </div>
        ) : null}
        {whatsNew ? <WhatsNewDialog onClose={() => setWhatsNew(false)} /> : null}
        {/*
          Сообщить о проблеме — field-backlog, пункт 157: в правом нижнем углу сцены, а не пунктом
          меню (07.10.2026, решение хозяина проекта). Место свободное, и сцену ссылка не сдвигает.
        */}
        <button
          type="button"
          className="link-quiet fixed bottom-3 right-4 z-10 text-13 text-ink-dim hover:text-accent"
          onClick={() => setReporting(true)}
        >
          {t('problem.open')}
        </button>
        {reporting ? <ProblemReportDialog screen="menu" onClose={() => setReporting(false)} /> : null}
      </div>
      </div>
    </div>
  );
}

/** Оправа пункта главного меню: все пункты одного размера (07.10.2026, решение хозяина проекта). */
const MENU_BUTTON_CLASS = 'flex w-full items-center justify-between gap-3 border border-space-600 '
  + 'bg-space-950/60 px-4 py-2 text-left text-16 transition-colors hover:border-accent hover:text-accent '
  + 'disabled:cursor-not-allowed disabled:text-ink-off';

function MenuButton({ children, onClick, tip, tone = 'text-ink', badge, disabled }: {
  children: ReactNode;
  onClick: () => void;
  /** Подпись пункта — всплывающей подсказкой, а не строкой под ним. */
  tip?: string;
  tone?: string;
  /** Сколько ждёт решения — число справа. */
  badge?: number;
  disabled?: boolean;
}) {
  return (
    <button type="button" title={tip} disabled={disabled} className={`${MENU_BUTTON_CLASS} ${tone}`} onClick={onClick}>
      {children}
      {badge ? <Figure accent="note" tone="warn">{badge}</Figure> : null}
    </button>
  );
}

/** Группа пунктов: раскрывается на месте, вложенные пункты того же размера, с отступом. */
function MenuGroup({ label, badge, open, onToggle, children }: {
  label: string;
  badge?: number;
  open: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <div>
      <button type="button" aria-expanded={open} className={`${MENU_BUTTON_CLASS} ${open ? 'text-accent' : 'text-ink'}`} onClick={onToggle}>
        <span>{label}</span>
        <span className="flex items-center gap-3">
          {badge ? <Figure accent="note" tone="warn">{badge}</Figure> : null}
          <span aria-hidden className={`inline-block transition-transform ${open ? 'rotate-90' : ''}`}>›</span>
        </span>
      </button>
      {open ? <div className="mt-2 space-y-2 border-l border-space-700 pl-4">{children}</div> : null}
    </div>
  );
}

/** Подраздел внутри группы — подпись, а не пункт. */
function MenuHeading({ children }: { children: ReactNode }) {
  return <div className="pt-1 text-11 uppercase tracking-[0.25em] text-ink-faint">{children}</div>;
}

/** Строка-сводка партии в списке открытых игр: числа — сопутствующей ступенью, фраза — одна строка словаря. */
function gameSummary(game: GameSummary) {
  const n = (value: number) => <Figure accent="note">{value}</Figure>;
  return tf('menu.game.summary', {
    size: galaxySizeName(game.galaxySize), w: n(game.widthParsecs), h: n(game.heightParsecs),
    have: n(game.humanPlayers), max: n(game.maxHumanPlayers), total: n(game.totalPlayers),
  });
}

function GameRow({
  game,
  onJoin,
  canJoin,
  together,
}: {
  game: GameSummary;
  onJoin: () => void;
  canJoin: boolean;
  /** Сколько партий вы уже сыграли с кем-то из её участников — пункт 148. */
  together?: number;
}) {
  const { t } = useT();
  return (
    <li className="border-b border-space-700 pb-2 last:border-b-0">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-ink">{game.name}</span>
        <button type="button" className="link text-xs" disabled={!canJoin} onClick={onJoin}>
          {t('menu.join')}
        </button>
      </div>
      <div className={figureLabelClass('note')}>
        {gameSummary(game)}
      </div>
      {/*
        Расписание партии — до входа (field-backlog, пункт 177): срок хода и правило, по которому
        ИИ берёт империю у отошедшего. Узнавать его после входа поздно.
      */}
      <div className="text-11 text-ink-faint">
        {t(game.turnSeconds ? 'menu.game.clock' : 'menu.game.noClock', { time: turnTimerLabel(game.turnSeconds) })}
      </div>
      {/* Зрители (пункт 59): согласие создателя видно до входа — смотреть будут и эту империю. */}
      {game.spectators ? (
        <div className="text-11 text-ink-faint"><span aria-hidden className="mr-1">◉</span>{t('menu.game.spectators')}</div>
      ) : null}
      {/* Свои видны (пункт 148): в группу своих входят охотнее — только число, без чужих итогов. */}
      {together ? <div className="text-11 text-lamp">{t('menu.game.together', { n: together })}</div> : null}
    </li>
  );
}
