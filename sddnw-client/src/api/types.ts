/** Контракты REST API игрового сервера. Соответствуют DTO в com.sddnw.server.dto. */

export type GameStatus = 'LOBBY' | 'IN_PROGRESS' | 'FINISHED';
export type PlayerType = 'HUMAN' | 'AI';
export type StarColor = 'RED' | 'WHITE' | 'YELLOW';

/** Размер галактики — п. 4.2 / 4.2.1. */
export interface GalaxySizeOption {
  code: string;
  label: string;
  widthParsecs: number;
  heightParsecs: number;
  /** Обычные звёзды, без особой. */
  starCount: number;
  /** Обычные звёзды плюс особая. */
  totalStarCount: number;
  /** Значение, выбранное по умолчанию (Huge). */
  defaultChoice: boolean;
}

/**
 * Сохранённая партия — строка диалога «Игра» → «Загрузить».
 *
 * Само состояние лежит на сервере, клиенту нужен только заголовок строки.
 */
export interface GameSave {
  id: string;
  gameId: string;
  name: string;
  galaxySize: string;
  widthParsecs: number;
  heightParsecs: number;
  starCount: number;
  /** Номер завершённого хода, на конец которого снято состояние. */
  turn: number;
  humanPlayers: number;
  totalPlayers: number;
  savedAt: string;
  /** Снято игрой в конце хода, а не человеком — п. 3. */
  auto: boolean;
  /**
   * Дозволено ли поднять этот слепок.
   *
   * Автосохранение партии поднимают только те, кто в ней играл (п. 3), и знать об этом
   * надо ДО нажатия: отказ после нажатия — плохой обмен. Сделанное руками поднимает кто
   * угодно, и там всегда `true`.
   */
  loadable: boolean;
}

/** Размер планеты — п. 4.1.1 / 4.1.1.1. */
export interface PlanetSizeRef {
  code: string;
  label: string;
  basePopulation: number;
}

/** Плодородность и цепочка терраформирования — п. 4.1.2 / 4.1.2.1. */
export interface PlanetClimateRef {
  code: string;
  label: string;
  populationMultiplierPercent: number;
  colonizable: boolean;
  terraformOrder: number;
  nextClimateCode?: string;
  requiredTechCode?: string;
}

/** Минералы — п. 4.1.3. */
export interface MineralRichnessRef {
  code: string;
  label: string;
  productionPerWorker: number;
}

/** Раса — п. 5 / п. 7. */
export interface Race {
  code: string;
  name: string;
  description?: string;
  homeClimateCode: string;
  color: string;
  /**
   * Коды сторон готовой расы — п. 5: те же, что покупает игрок в конструкторе.
   * Названия берутся из `RaceDesign`, который у клиента уже есть.
   */
  traits: string[];
  /** Предыстория расы — откуда она и чего хочет (пункт 43). */
  lore?: string;
}

export interface Player {
  id: string;
  slot: number;
  name: string;
  playerType: PlayerType;
  raceCode: string;
  raceName?: string;
  color: string;
  homeSystemId?: string;
  /** Казна игрока в кредитах — п. 10. */
  credits: number;
  /** Правительство империи — п. 14; пусто, если раса собрана без него. */
  government?: string;
  /** Накоплено очков шпионажа — п. 13. */
  espionagePoints: number;
  /** Грузовых кораблей у империи — п. 4.1.1: они возят еду голодающим колониям. */
  freighters: number;
  /**
   * Сколько грузовиков занято перевозкой жителей — п. 4.1.1: по одному на единицу
   * населения, пока рейс не дойдёт. Занятые еду не возят.
   */
  freightersReserved: number;
  /**
   * Игрок объявил конец текущего хода — п. 11.1. У ИИ всегда true: он никого не ждёт
   * и никого не задерживает.
   */
  turnEnded: boolean;
  /** Человек ушёл из-за стола, и его империю ведёт ИИ — backlog-promo, пункт 11. */
  away?: boolean;
  /** Когда игрок последний раз закончил ход или вошёл в партию (ISO) — пункт 108. */
  lastActionAt?: string;
  /**
   * Через сколько секунд часы передадут империю ИИ — field-backlog, пункт 127; считает сервер.
   * Пусто — не грозит: ход закончен, никто не ждёт или игрок уже ушёл.
   */
  aiTakeoverSeconds?: number;
  /** До какого мига игрок в отпуске — field-backlog, пункт 177; пусто — не в отпуске. */
  vacationUntil?: string;
  /** Глава партии — пункт 54: 1 расселение … 5 финал. */
  chapter?: number;
  /** Кому этот игрок показал империю как ученик — пункт 184. */
  mentorPlayerId?: string;
  /** Приходило ли хоть одно предложение службы лидера — пункт 27. */
  leadersSeen?: boolean;
  /** Знак «с нами с раннего доступа» — пункт 147. */
  earlyBird?: boolean;
  /** Знак спутника — подарок за доигранную по приглашению партию (пункты 50, 174), 0…3. */
  companions?: number;
  /** Неистраченные очки эволюционной мутации: пока они есть, своему игроку открывается окно выбора. */
  mutationPicks?: number;
}

/** Сторона расы, которую может прибавить эволюционная мутация. */
export interface MutationOption {
  code: string;
  name: string;
  description: string;
  picks: number;
  group: string;
}

/** Событие хода — что изменилось у игрока, пока считалась галактика (п. 11.1). */
export interface TurnEvent {
  /** POPULATION, BUILDING, COLONY_BASE, SPY, INCOME, RESEARCH, ESPIONAGE. */
  code: string;
  text: string;
  /** Успех игрока — пункт 88: своим разделом итогов после угроз (правило летописи на сервере). */
  success?: boolean;
  /**
   * Ключ словаря, из которого собран `text`, — п. 3.5.
   *
   * Клиент читает `text`, но по ключу событие УЗНАЁТСЯ: сцена украденной технологии ищет
   * `turn.espionage.stole`, а разбирать показанный текст нельзя — он меняется с языком.
   */
  key?: string;
  /**
   * Подстановки того же события: имена приходят как есть, а ссылки на справочник —
   * ключами вида `catalog.tech.<код>`. Из них сцена и достаёт код технологии.
   */
  args?: string[];
  systemId?: string;
  planetId?: string;
}

/** Отчёт хода одного игрока — п. 11.1. */
export interface TurnReport {
  turn: number;
  events: TurnEvent[];
  changedSystemIds: string[];
  changedPlanetIds: string[];
}

/**
 * Ответ на конец хода — п. 11.1: игроки ходят одновременно, и ход считается, когда
 * закончили все. Пока не закончили, `advanced` ложь, а в `waitingFor` те, кого ждут.
 */
export interface EndTurnResult {
  state: GameDetails;
  advanced: boolean;
  waitingFor: string[];
  report?: TurnReport;
}

/** Оружие корабля строкой карточки экрана флота — п. 8: сколько, чем и на какой урон. */
export interface FleetWeapon {
  name: string;
  count: number;
  /** Урон всех этих стволов за заход — без приборов и расы: они уже в залпе корабля. */
  damage: number;
}

/** Корабли одного проекта во флоте — п. 8. */
export interface FleetShip {
  designId: string;
  designName: string;
  hullCode?: string;
  hullName?: string;
  /** Размер корпуса 1..6 — им меряется значок корабля в сетке экрана флота. */
  hullSize?: number;
  ships: number;
  attack: number;
  defense: number;
  /** Щит корабля; пусто — щита нет. */
  shield?: string;
  /** Чем вооружён; пусто — корабль без оружия, вспомогательный. */
  weapons: FleetWeapon[];
  /** Особые модули: боевые отсеки, усиленный корпус, десант. */
  specials: string[];
  /** Проект вытеснен из ячейки новым, но корабли по нему ещё летают. */
  obsolete: boolean;
  /** Для чего корабль построен — п. 8. */
  role: ShipRole;
  /** Сколько жителей везут эти корабли: поселенцы или десант; у боевых ноль. */
  cargo: number;
  /** Прибавка дальности от дополнительных баков, процентов; 0 — без баков (пункт 206). */
  rangePercent?: number;
  /** Звание экипажа: 0 — новобранцы … 4 — сверхэлита (опыт за бои и академию). */
  crewLevel?: number;
  /** Прибавка звания к меткости и защите. */
  crewBonus?: number;
}

/**
 * Флот игрока в звёздной системе или в пути к ней — п. 8.
 *
 * У летящего флота `starSystemId` — система вылета: с неё начинается линия полёта на
 * карте. Где флот сейчас, клиент считает сам по номерам ходов: сервер шлёт маршрут и
 * сроки, а не координаты, — на карте флот идёт по прямой.
 */
export interface FleetGroup {
  id: string;
  starSystemId: string;
  systemName: string;
  ships: number;
  /** Инициатива: сила флота на скорость его кораблей — кто первым выбирает бой. */
  initiative: number;
  /** Боевая сила: сумма сил кораблей по их проектам. */
  power: number;
  /** Скорость флота в парсеках за ход — по самому медленному кораблю. */
  speed: number;
  /**
   * Дальность ЭТОГО флота в парсеках от ближайшей своей колонии — п. 8: топливо империи
   * плюс дополнительные баки, и идёт она по самому короткому кораблю строя.
   */
  rangeParsecs: number;
  /** Из каких проектов флот собран. */
  composition: FleetShip[];
  /** Куда летит; пусто — флот стоит в системе. */
  targetSystemId?: string;
  /** Название системы назначения; у неразведанной его нет. */
  targetSystemName?: string;
  /** Ход вылета. */
  departureTurn?: number;
  /** Ход, на котором флот окажется на месте. */
  arrivalTurn?: number;
  /** Приказ «разведывать самим» — backlog-promo, пункт 24. */
  autoExplore?: boolean;
  /** Имя флота — пункт 141; пусто — безымянный. */
  name?: string;
  /** Бои флота — пункт 141. */
  battlesWon?: number;
  battlesLost?: number;
  /**
   * Флот в пути слышит приказ сейчас (п. 8, `FlightRules.canOrder`): гиперпространственная связь
   * или своя звёздная база рядом — ближе парсека, 3 пк с тахионной связью, 6 с подпространственной.
   */
  orderable?: boolean;
}

/** Состояние встречи флотов — п. 8. */
export type EncounterStateCode = 'WAITING_FIRST' | 'WAITING_SECOND' | 'BATTLE' | 'IGNORED';

/** Встреча флотов в системе — п. 8, со стороны одного игрока. */
export interface Encounter {
  id: string;
  starSystemId: string;
  systemName: string;
  turn: number;
  state: EncounterStateCode;
  stateLabel: string;
  /** Решение сейчас за этим игроком. */
  yourTurn: boolean;
  /** Этот игрок ходит первым: его флот быстрее. */
  firstMover: boolean;
  opponentPlayerId: string;
  opponentName: string;
  yourShips: number;
  opponentShips: number;
  yourInitiative: number;
  opponentInitiative: number;
  /** Тактический бой, если игрок выбрал ручной, — п. 8. */
  battleId?: string;
  /** Исход боя; пусто, пока встреча не закрыта. */
  outcome?: string;
  /**
   * Встреча у беззащитной колонии флота с бомбами — пункт 25: решение «бомбить или уйти»,
   * диалог оригинала «Select Combat at …: BOMBARD / CANCEL».
   */
  bombard?: boolean;
  /** Своя сила — флот и платформы своих колоний системы (пункты 84 и 153). */
  yourPower?: number;
  /** Сила соперника; пусто — не видна: технологии на его кораблях не увидены. */
  opponentPower?: number;
  /** Исход словами: LIKELY_WIN, UNCLEAR, LIKELY_LOSS или UNKNOWN. */
  forecast?: 'LIKELY_WIN' | 'UNCLEAR' | 'LIKELY_LOSS' | 'UNKNOWN';
  /** Сколько технологий на кораблях соперника ещё не увидено. */
  unseenTechnologies?: number;
}

/** Прогресс к путям к победе — field-backlog, пункты 51 и 11; выключенный путь пуст. */
export interface VictoryProgress {
  council?: { yourVotes: number; knownVotes: number; neededPercent: number; nextTurn?: number; unknownEmpires: number };
  conquest: { yourColonies: number; knownRivals: number; unknownEmpires: number };
  thessar?: { holderPlayerId?: string; turnsLeft?: number; holdTurns: number };
  might?: {
    place?: number; ranked: number; turnsLeft: number; turnLimit: number;
    /** Итог ждёт согласия людей (09.10.2026): кого назвали сильнейшим; пусто — не ждёт. */
    pendingPlayerId?: string;
    /** Спрашивают ли вас. */
    asked?: boolean;
    /** Ваш ответ: AGREE, REFUSE или пусто. */
    yourAnswer?: 'AGREE' | 'REFUSE';
  };
  /** Ближайшая значимая веха одной строкой; пусто — вехи нет. */
  milestone?: string;
  /** Финиш близко — пункт 56: своя или чужая победа в считанных ходах, строкой на путь. */
  finish?: { kind: string; yours: boolean; text: string }[];
}

/** Наследие ушедших на выбор — backlog-promo, пункт 34. */
export interface ArtifactChoice {
  id: string;
  systemName: string;
  /** Сколько можно взять: одну, при удачном броске — две. */
  picks: number;
  /** До конца какого хода ждут выбора; дальше берутся первые. */
  deadlineTurn: number;
  options: { code: string; name: string; description: string; categoryCode: string; levelOrder: number }[];
}

/** Короткий знак между людьми партии — field-backlog, пункт 183; текст — словарём по коду. */
export interface GameSignal {
  id: string;
  fromPlayerId: string;
  fromName?: string;
  /** Кому; пусто — всем. */
  toPlayerId?: string;
  code: string;
  turn: number;
  createdAt: string;
  /** Свободный текст при коде TEXT — пункт 16. */
  text?: string;
}

/** Решение, которое ИИ принял за ушедшего игрока, — field-backlog, пункт 112. */
export interface StewardDecision {
  turn: number;
  text: string;
}

/** Событие партии из подписки — п. 11.1. */
export interface GameEvent {
  type: 'SUBSCRIBED' | 'PLAYER_READY' | 'TURN_ADVANCED' | 'GAME_STARTED' | 'PLAYER_JOINED' | 'TURN_PHASE'
    | 'SIGNAL';
  gameId: string;
  turn?: number;
  playerId?: string;
  text?: string;
  report?: TurnReport;
}

export interface GameSummary {
  id: string;
  name: string;
  status: GameStatus;
  galaxySize: string;
  widthParsecs: number;
  heightParsecs: number;
  starCount: number;
  humanPlayers: number;
  maxHumanPlayers: number;
  totalPlayers: number;
  turn: number;
  hostPlayerId?: string;
  /** Идут ли в партии случайные галактические события — п. 11.1. */
  galacticEvents?: boolean;
  /** Победитель партии — п. 3; пусто, пока партия идёт. */
  winnerPlayerId?: string;
  /** Чем взята победа: покорением, Высшим советом или удержанием Wardenhold. */
  victoryKind?: VictoryKind;
  createdAt: string;
  /** Побеждает ли в партии удержание Wardenhold двадцать ходов — п. 3. */
  wardenholdVictory?: boolean;
  /** Кто сейчас один держит особую звезду; пусто — никто. */
  wardenholdHolderPlayerId?: string;
  /** Сколько ходов держателю осталось до победы; считает сервер. */
  wardenholdTurnsLeft?: number;
  /** Собирается ли Высший совет — п. 3. */
  council?: boolean;
  /** Подводится ли итог по могуществу — п. 3. */
  mightVictory?: boolean;
  /** На каком ходу подводится итог по могуществу. */
  mightVictoryTurn?: number;
  /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
  turnSeconds?: number;
  /** Гостевые правила: богатый старт, первые уровни по оплате, наука вдвое (`GuestGameRules`). */
  guestRules?: boolean;
  /** Партию можно смотреть со стороны — field-backlog, пункт 59. */
  spectators?: boolean;
  /** Особенность галактики — пункт 94; пусто — нет. */
  galaxyFeature?: string;
  /** Неделя галактики недели — пункт 146. */
  weeklyWeek?: number;
  /** Союзники, разделившие победу покорением, — пункт 145. */
  coWinnerPlayerIds?: string[];
  /** Уровень сложности — пункт 9 (`AiSkill`); пусто — обычный. */
  aiSkill?: string;
  /**
   * Партия под наблюдением администратора — field-backlog, пункт 197: он смотрел в ней всю
   * галактику при других людях. Видна каждому красным оком (`AdminWatchEye`).
   */
  adminWatched?: boolean;
  /**
   * Стол подбора «сыграть с людьми» (field-backlog, пункт 65): когда он стартует сам, если за ним
   * хотя бы двое людей; пусто — обычная партия, её стартует создатель.
   */
  autoStartAt?: string;
  /** Когда истекает текущий ход (ISO); пусто — срока нет. */
  turnDeadline?: string;
  /**
   * Ход, на котором ВЫ признали поражение (пункт 81) — только в «Ваших партиях»: партия для вас
   * окончена, даже если идёт дальше без вас.
   */
  yourConcededTurn?: number;
  /** Чем эта партия запомнилась вам — только в «Ваших партиях» (пункт 109). */
  mine?: {
    /** Своё место по мощи на последнем замере; пусто — замеров ещё нет. */
    place?: number;
    empires: number;
    /** Ждёт ли партия вашего хода (иначе ход объявлен — ждём соперников). */
    yourMove: boolean;
    /** Последний главный момент летописи, на языке запроса. */
    lastEvent?: string;
    lastEventTurn?: number;
    lastActionAt?: string;
  };
}

/** Чем кончилась партия — п. 3. */
export type VictoryKind = 'CONQUEST' | 'COUNCIL' | 'WARDENHOLD' | 'MIGHT' | 'CONCEDED' | 'DECLARED';

/**
 * Можно ли объявить победу — field-backlog, пункт 154: мощь своя и всех прочих живых вместе,
 * во сколько раз нужно быть сильнее и, если нельзя, — почему.
 */
export interface VictoryClaim {
  allowed: boolean;
  own: number;
  others: number;
  ratio: number;
  reason?: string;
}

/**
 * Итоги оконченной партии — field-backlog, пункт 33: всё, из чего сцена конца рассказывает
 * партию. Причину сервер присылает готовой фразой с числами на языке запроса.
 */
export interface GameEnding {
  gameName: string;
  turn: number;
  victoryKind: VictoryKind;
  kindLabel: string;
  /** Пусто у признавшего поражение в партии, которая идёт дальше без него (пункт 81). */
  winnerId?: string;
  winnerName?: string;
  winnerColor?: string;
  won: boolean;
  reason: string;
  /** Место спрашивающего по мощи на последнем ходу. */
  place: number;
  empires: number;
  mine: EndingAchievements;
  history: EndingEmpireLine[];
  /** Летопись главных моментов игрока (пункт 34), по ходам, на языке запроса. */
  chronicle: EndingMoment[];
  /** «Как это было» — пункт 143: сильнейшие строки хроники по ходам. */
  story?: EndingMoment[];
  /** Какой трудной была победа — пункт 185; пусто — не была. */
  hardship?: string;
  /** Путь победителя по контрольным ходам — пункт 106. */
  winnerPath?: {
    name: string;
    color: string;
    self: boolean;
    firstColonyTurn?: number;
    checkpoints: { turn: number; colonies: number; fleetPower: number; technologies: number }[];
    wars: number;
    battles: number;
    captured: number;
  };
  /** Сравнение с лучшей своей партией той же настройки — пункт 115. */
  pastSelf?: PastSelf;
  /** Первые места партии по осям — пункт 116: только настоящие, из записанных чисел. */
  firsts?: {
    axis: string;
    holders: { name: string; color: string; self: boolean }[];
    value: number;
    mine: boolean;
  }[];
  guest?: boolean;
  /**
   * Сыграть снова тем же составом — пункт 149: `started` — кто-то из участников уже завёл новую
   * (`byName`), `joined` — вы за её столом. Пусто — других людей за столом не было.
   */
  rematch?: { started: boolean; byName?: string; joined: boolean };
  /** Ваша раса названием — пункт 98. */
  raceName?: string;
  /** Свои проекты кораблей партии, лучший первым — пункт 98. */
  designs?: OwnDesign[];
  /** Звания за трудное, которые принесла эта партия, — пункт 41 (коды). */
  titles?: string[];
  /** Доля среди похожих партий других игроков — пункт 117; пусто — рано или не с чем. */
  peers?: PeerShare;
  /** Рейтинг и сдвиг от этой партии — пункт 19; пусто — без других людей или своих партий мало. */
  rating?: Rating;
}

export interface EndingMoment {
  turn: number;
  /** Род момента: CONTACT, WAR, BATTLE_WON, COLONY_FOUNDED, COLONY_TAKEN, COLONY_LOST… */
  kind: string;
  text: string;
}

export interface EndingAchievements {
  /** Ход наибольшей доли мощи в галактике; пусто — летописи нет. */
  bestTurn?: number;
  bestSharePercent: number;
  peakColonies: number;
  peakPopulationK: number;
  technologies: number;
  founded: number;
  captured: number;
  lost: number;
  battles: number;
  wars: number;
}

export interface EndingEmpireLine {
  playerId: string;
  name: string;
  color: string;
  self: boolean;
  winner: boolean;
  points: { turn: number; might: number }[];
}

/**
 * Для чего корабль построен — п. 8.
 *
 * Гражданские корабли MOO II оружия не несут и в бой не выходят, но высаживают разное:
 * колониальный корабль — колонию, застава — заставу, транспорт — десант. По одному лишь
 * отсутствию оружия их не различить, поэтому роль приходит с сервера отдельным полем.
 */
export type ShipRole = 'WARSHIP' | 'COLONY' | 'OUTPOST' | 'TRANSPORT';

export interface GameDetails {
  game: GameSummary;
  players: Player[];
}

export interface PlayerCredentials {
  gameId: string;
  playerId: string;
  accessToken: string;
  host: boolean;
}

/** Ответ на создание игры и на присоединение к ней. */
export interface JoinedGame {
  game: GameSummary;
  players: Player[];
  credentials: PlayerCredentials;
}

/**
 * Колония на планете — п. 4.1: чем заняты жители и что колония даёт за ход.
 *
 * Каждый житель занят чем-то одним, поэтому фермеры, рабочие и учёные в сумме дают
 * население планеты. У незаселённой планеты колонии нет, и поле не приходит вовсе.
 */
/**
 * Проект колонии — п. 10: здание из справочника либо особый проект MOO II.
 *
 * Особый проект (`special`) зданием не становится и на планете ничего не занимает. Дома
 * и товары вдобавок не заканчиваются, поэтому стоимости у них нет; колониальная база
 * достраивается, и стоимость у неё есть.
 */
export interface ColonyProject {
  code: string;
  name: string;
  description: string;
  cost?: number;
  upkeep: number;
  special: boolean;
  /** Сколько кредитов даст продажа постройки — п. 10; у особых проектов пусто. */
  sellValue?: number;
  /**
   * Можно ли поставить проект в очередь ещё раз, когда он уже строится, — п. 10: здание
   * на планете одно, а кораблей, шпионов и грузовиков строят сколько угодно. Правило
   * приходит с сервера: повторять его здесь значило бы дать ему разъехаться.
   */
  repeatable: boolean;
  /**
   * За сколько ходов здание окупится на этой колонии — field-backlog, пункт 122: то же число,
   * по которому выбирают ИИ и автострой. Пусто — окупаемости нет, тогда говорит `purpose`.
   */
  paybackTurns?: number;
  /** Ради чего строят здание без окупаемости: DEFENCE, FOOD, INCOME, GROWTH, POLLUTION, MORALE… */
  purpose?: string;
}

/** Слагаемое выработки колонии — field-backlog, пункт 40 (`ColonyDto.OutputPartDto`). */
export interface OutputPart {
  resource: 'FOOD' | 'PRODUCTION' | 'RESEARCH';
  /** BASE, BUILDING, RACE, ARTIFACTS, FIND, GRAVITY, MORALE, TREATIES, TECHNOLOGIES, LEADER, POLLUTION. */
  source: string;
  /** Имя постройки — у слагаемого BUILDING. */
  name?: string;
  perUnit: number;
  flat: number;
  percent: number;
  perColonist: number;
}

/** Занятие жителя — так его называет сервер (`ColonistJob`), п. 4.1. */
export type ColonistJob = 'FARMERS' | 'WORKERS' | 'SCIENTISTS';

export interface Colony {
  /** Население в тысячах жителей: работать умеют только целые. */
  populationK: number;
  /** Вместимость планеты со зданиями — биосферы её поднимают. */
  maxPopulation: number;
  /** Прирост населения за ход в тысячах; отрицательный — колония голодает. */
  growthK: number;
  /** Прибавка к рождаемости от медицинских технологий — п. 9. */
  medicineBonusPercent: number;
  /** Прибавка к рождаемости от домов, если колония их строит. */
  housingBonusPercent: number;
  /** Постоянная прибавка к приросту от зданий, тысяч жителей. */
  growthFlatK: number;

  farmers: number;
  workers: number;
  scientists: number;

  /**
   * Ставки выработки со зданиями и то, что здания дают сами по себе. По ним считается
   * предпросмотр ещё не применённого перераспределения — без повторения правил зданий.
   */
  foodPerFarmer: number;
  foodFlat: number;
  productionPerWorker: number;
  productionFlat: number;
  /**
   * Производство с каждого жителя, чем бы он ни был занят, — п. 10: так работает
   * рециклотрон, и эта часть планету не пачкает.
   */
  productionPerColonist: number;
  /**
   * Твёрдое производство, которое пачкает, — п. 10: роботизированный завод (по недрам).
   * В чистую часть добычи не входит. У ответов старого сервера поля нет.
   */
  productionDirtyFlat?: number;
  researchPerScientist: number;
  researchFlat: number;

  /** Выработка колонии за ход. */
  food: number;
  production: number;
  research: number;
  /** Съедает колония: по единице на жителя. */
  foodConsumption: number;
  /** Остаток еды; отрицательный — колония себя не кормит. */
  foodBalance: number;
  /** Сколько еды довёз грузовой флот империи — п. 4.1.1; без грузовиков ноль. */
  foodDelivered: number;
  /** Доход колонии в кредитах за ход, уже за вычетом содержания зданий. */
  income: number;
  /** Содержание зданий колонии в кредитах за ход. */
  upkeep: number;
  /**
   * Сколько производства уходит на уборку за собой — п. 10. В `production` этих единиц
   * уже нет: колония строит на то, что осталось.
   */
  pollution: number;
  /** Сколько производства планета терпит без грязи: её размер, поднятый зданиями. */
  pollutionTolerance: number;
  /** Во сколько раз чище считается производство: 1 без очистных сооружений, 2, 4 или 8 с ними. */
  pollutionDivisor: number;
  /** Грязи нет вовсе: свалка в ядре планеты или неприхотливая раса (п. 7). */
  pollutionFree: boolean;
  /** Что колония строит; пусто — ничего, и производство пропадает. */
  projectCode?: string;
  projectName?: string;
  /** Вложено в стройку единиц производства. */
  projectPoints: number;
  /** Во что обойдётся проект; у домов и товаров стоимости нет. */
  projectCost?: number;
  /** Построенные здания. */
  buildings: ColonyProject[];
  /** Что колония может строить прямо сейчас. */
  available: ColonyProject[];
  /** Очередь стройки — п. 10: что колония заложит следом, по порядку. */
  queue: ColonyProject[];
  /**
   * Очередь идёт по кругу — п. 10, кнопка REPEAT BUILD оригинала: взятое с очереди
   * возвращается в её конец. Возвращается только повторяемое: здание строится однажды.
   */
  repeatBuild: boolean;
  /**
   * Колония сама берёт следующее дело, когда очередь опустела, — п. 10, кнопка AUTO BUILD:
   * самое быстроокупаемое из доступного, по тому же правилу, по которому выбирает ИИ.
   */
  autoBuild: boolean;
  /** «Жители сами» — field-backlog, пункт 91: фермеров на прокорм, остальные в доле игрока. */
  autoJobs: boolean;
  /**
   * Во что обойдётся выкуп недостающего по текущей стройке — п. 10. Пусто, когда
   * выкупать нечего: дома и товары не кончаются, а оплаченному докупать уже нечего.
   */
  buyCost?: number;
  /**
   * Ход, когда колония продала постройку в последний раз, — п. 10. За ход продаётся
   * одна, поэтому равенство нынешнему ходу и значит «на этот ход уже всё».
   */
  soldTurn?: number;
  /** Сколько жителей колонии — подданные, взятые с боем (п. 12); 0 — все свои. */
  alienPopulation: number;
  /** Через сколько ходов один из подданных станет своим; пусто — подданных нет. */
  assimilationTurns?: number;
  /**
   * Мораль колонии в процентах — п. 10.1: процент ко всему, что дают жители. Уже сидит в
   * процентах ниже и в доходе.
   */
  moralePercent: number;
  /** Из чего мораль сложилась — строками подсказки. */
  moraleParts: MoralePart[];
  /** Строй морали не знает (объединение). */
  moraleIgnored: boolean;
  /** Проценты к выработке фермера, рабочего и учёного: строй, тяжесть, мораль. */
  foodPercent: number;
  productionPercent: number;
  researchPercent: number;
  /** Из чего сложились еда, производство и наука — пункт 40. */
  outputParts?: OutputPart[];
  /** Стоят пищевые репликаторы: недостающую еду колония делает из производства, единица за единицу. */
  foodReplicator?: boolean;
}

/** Слагаемое морали колонии — п. 10.1. */
export interface MoralePart {
  source: 'GOVERNMENT' | 'BUILDINGS' | 'VIRTUAL_REALITY' | 'PSIONICS' | 'LEADER' | 'ALIENS';
  percent: number;
}

export interface Planet {
  id: string;
  orbit: number;
  name: string;
  size: string;
  sizeLabel: string;
  climate: string;
  climateLabel: string;
  populationMultiplierPercent: number;
  colonizable: boolean;
  /** Еда с одного фермера на этом климате — п. 4.1.2. */
  foodPerFarmer: number;
  /** Тяжесть мира — п. 4.1: непривычная расе роняет производство колонии. */
  gravity: 'LOW' | 'NORMAL' | 'HIGH';
  gravityLabel: string;
  /** Чего стоит эта тяжесть производству, в процентах; ноль у привычной — п. 4.1. */
  gravityPenaltyPercent: number;
  minerals: string;
  mineralsLabel: string;
  productionPerWorker: number;
  maxPopulation: number;
  population: number;
  ownerPlayerId?: string;
  homeworld: boolean;
  colony?: Colony;
  /**
   * Колония достроила колониальную базу и ждёт, какую планету системы заселить — п. 4.1.
   * По этому признаку клиент открывает экран системы после конца хода.
   */
  colonyBaseReady: boolean;
  /**
   * Находка на планете — п. 4.1: золотые жилы, самоцветы, туземцы или наследие ушедшей
   * цивилизации. Пусто — планета обыкновенная, и таких подавляющее большинство.
   */
  find?: 'GOLD_DEPOSITS' | 'GEM_DEPOSITS' | 'ARTIFACTS' | 'NATIVES';
  /** Имя находки на языке игрока — сервер уже перевёл. */
  findLabel?: string;
  /** Что находка даёт и при каком условии — теми же словами, что и в оригинале. */
  findDescription?: string;
  /**
   * Оборона ЧУЖОЙ колонии глазами игрока (п. 15, туман войны): названия платформ, батарей,
   * щитов и казарм. У своей колонии пусто — она видна целиком через `colony`.
   */
  defences?: string[];
  /** Ход, на котором чужую колонию видели в последний раз; пусто — видна сейчас. */
  seenTurn?: number;
  /** Радиус варп-перехватчика ЧУЖОЙ колонии в парсеках (п. 8); пусто — его нет. */
  interdictorParsecs?: number;
}

/**
 * Звёздная система на карте.
 *
 * Звёзды видны все, но у неразведанной системы (`explored: false`) сервер не отдаёт ни
 * названия, ни планет, ни владельца — на карте это безымянная точка. Разведывает систему
 * только флот — п. 15.
 *
 * Сканеры империи добавляют к неразведанной звезде ровно один факт: попала ли она в их
 * дальность (`scanned`) и есть ли там кто-то чужой (`occupied`). Состав системы сканеры
 * не раскрывают.
 */
export interface StarSystem {
  id: string;
  name?: string;
  /** Координаты в парсеках — теми же, что и дальность топлива (п. 8). */
  x: number;
  y: number;
  color: StarColor;
  colorHex: string;
  /** Особая звезда Wardenhold: у неразведанной системы всегда false. */
  special: boolean;
  ownerPlayerId?: string;
  planets: Planet[];
  /** Система разведана: известны название, планеты и владелец. */
  explored: boolean;
  /** Система в дальности сканеров империи — п. 15. */
  scanned: boolean;
  /** Сканер видит чужое присутствие: колонию или флот — п. 15. */
  occupied: boolean;
  /**
   * Чудище, сторожащее систему, — п. 11.1; пусто — система чиста.
   *
   * Приходит именем на языке игрока (сервер переводит его сам). У неразведанной системы
   * его нет, как нет и названия: чудище видно только тому, кто там побывал, — и
   * всевидящей расе.
   */
  monster?: string;
  /** Тело этого чудища (`amoeba-body`, `dragon-body`…): им карта рисует значок сторожа. */
  monsterHull?: string;
  /**
   * Хозяева НЕРАЗВЕДАННОЙ системы, известные без разведки — п. 15, туман войны: ближайшая система
   * знакомой империи и системы знакомых в дальности сканеров. Тогда же приходит и название; состав
   * системы — нет.
   */
  knownOwners?: string[];
}

/** Карта галактики — прямоугольное поле в парсеках со звёздными системами. */
export interface GalaxyMap {
  gameId: string;
  galaxySize: string;
  widthParsecs: number;
  heightParsecs: number;
  minStarDistanceParsecs: number;
  systems: StarSystem[];
  /** Чужие флоты, которые игрок видит: под глазами и в радиусе сканеров (п. 15). */
  foreignFleets?: ForeignFleet[];
  /**
   * Клад особой звезды, названный наполовину, — пункт 135: сколько технологий Стражей получит
   * первый, кто поселится у звезды. Пусто — клад взят.
   */
  specialStarReward?: number;
  /** Метки наставника на карте этого игрока — пункт 184. */
  mentorMarks?: MapMark[];
}

/**
 * Чужой флот на карте — п. 8, п. 15. Состав со стороны не виден: число кораблей и есть ли
 * вооружённые; у летящего — цель и срок прибытия (правило оригинала для сканеров).
 */
export interface ForeignFleet {
  id: string;
  ownerPlayerId: string;
  ships: number;
  armed: boolean;
  /** Где стоит; у летящего — откуда вылетел. */
  starSystemId: string;
  targetSystemId?: string;
  departureTurn?: number;
  arrivalTurn?: number;
}

/** Технология на выбор внутри уровня раздела — п. 9. */
export interface ResearchOption {
  code: string;
  name: string;
  description: string;
  /** Отмечена в описании дерева как предпочтительная. */
  recommended: boolean;
  /**
   * Раздел списка изученного в окне «Инфо» — п. 11.1: GENERAL, COLONY, WEAPON или SHIP.
   * Четыре части окна Tech Review MOO II; выводится не из дерева науки, а из того, что
   * технология открывает.
   */
  section?: string;
  /** Тот же раздел по-русски — подпись кнопки. */
  sectionLabel?: string;
}

/** Уровень раздела: на нём выбирается одна технология из предложенных. */
export interface ResearchLevel {
  /** Порядковый номер уровня в разделе, начиная с 1. */
  order: number;
  name: string;
  /** Стоимость уровня в очках исследований. */
  cost: number;
  /** Стоимость раздела с начала и до этого уровня включительно. */
  cumulativeCost: number;
  /** Общий уровень: его технологии выдаются все сразу, без выбора одной. */
  general: boolean;
  options: ResearchOption[];
}

/** Раздел дерева технологий: прямая последовательность уровней без ответвлений. */
export interface ResearchCategory {
  code: string;
  name: string;
  description: string;
  levels: ResearchLevel[];
}

/** Дерево технологий целиком — приходит с сервера из описания дерева. */
export interface ResearchTree {
  version: string;
  categories: ResearchCategory[];
  /**
   * Уровни, которые есть у всех рас с первого хода, — ссылками «раздел:номер»
   * (`power:1`). Ссылка, а не название: название переводится, а опознаватель уровня — его
   * место в дереве.
   */
  startingLevels: string[];
}

/** Изученная игроком технология — п. 9. */
export interface AcquiredTechnology {
  categoryCode: string;
  levelOrder: number;
  optionCode: string;
  name: string;
  /** Ход, на конец которого случился прорыв. */
  acquiredTurn: number;
}

/**
 * Исследования игрока — п. 9. Империя исследует одну технологию за раз, поэтому
 * цель здесь одна.
 *
 * Поля цели необязательны, а не пусты: сервер не сериализует null, и пока игрок
 * не выбрал, что исследовать, их в ответе просто нет.
 */
export interface PlayerResearch {
  categoryCode?: string;
  levelOrder?: number;
  optionCode?: string;
  optionName?: string;
  /** Базовая стоимость уровня в очках исследований. */
  levelCost?: number;
  /** Вложено в текущую цель. */
  researchPoints: number;
  /** Доход очков исследований за ход. */
  researchPerTurn: number;
  /** Сколько осталось вложить до базовой стоимости. */
  remainingPoints?: number;
  /**
   * На скольких очках прорыв наверняка — двойная цена, а в гостевой партии у первых уровней
   * сама цена (backlog-promo, пункт 25). Отдаёт сервер: правило живёт там.
   */
  certainPoints?: number;
  /** Шанс прорыва на ближайшем ходу; 100 — прорыв гарантирован. */
  breakthroughPercent?: number;
  /**
   * Изобретательная раса — п. 7: прорыв выдаёт весь уровень, а не одну технологию.
   * Экран выбора отмечает у такой расы весь уровень целиком.
   */
  creative?: boolean;
  acquired: AcquiredTechnology[];
}

/**
 * Особенность расы из конструктора — п. 7.
 *
 * `picks` — цена в очках расы: отрицательная особенность очки возвращает, и на них
 * берутся сильные стороны.
 */
export interface RaceTrait {
  code: string;
  name: string;
  description: string;
  picks: number;
  /** Коды особенностей, с которыми эта не встаёт — п. 7: литовор и фермеры. */
  excludes: string[];
  /** Что особенность меняет: одна может менять сразу несколько сторон — п. 7. */
  effects: { type: string; amount: number }[];
}

/** Шпион империи — п. 13: у каждого своё задание и свои накопленные очки. */
export interface Spy {
  id: string;
  /** Задание кодом: HOME, STEAL_TECH, SABOTAGE. */
  mission: SpyMissionCode;
  missionLabel: string;
  /** Во сколько очков обходится операция; у работы дома — ноль. */
  missionCost: number;
  points: number;
  targetPlayerId?: string;
  targetName?: string;
  createdTurn: number;
}

/** Что делает шпион — п. 13. */
export type SpyMissionCode = 'HOME' | 'STEAL_TECH' | 'SABOTAGE' | 'COUNTER';

/**
 * Способность лидера с силой при его нынешнем звании — п. 6.
 *
 * Сила приходит уже пересчитанной: формула роста живёт на сервере, и повторять её на
 * клиенте значило бы завести второе место, где она может разъехаться.
 */
export interface LeaderSkill {
  ability: string;
  name: string;
  /** GENERAL, COLONY или SHIP: где способность работает. */
  kind: string;
  /** PERCENT, POINTS или CREDITS. */
  unit: string;
  value: number;
  perLevel: number;
  /** ALWAYS — работает и в резерве; ASSIGNED — только по месту службы. */
  works: string;
  description?: string;
  /** Пусто, если способность действует; иначе — почему в этой игре она ничего не делает. */
  note?: string;
}

/** Лидер: предлагает службу или уже служит — п. 6. */
export interface Leader {
  /** Строка службы, а не номер лидера: по ней нанимают, увольняют и назначают. */
  id: string;
  code: string;
  name: string;
  title: string;
  kind: string;
  kindLabel: string;
  raceName?: string;
  state: string;
  stateLabel: string;
  rank: string;
  experience: number;
  hireCost: number;
  salary: number;
  skills: LeaderSkill[];
  techs: string[];
  offeredTurn: number;
  expiresTurn: number;
  systemId?: string;
  systemName?: string;
  fleetId?: string;
  arrivesTurn?: number;
  /** Послужной список — пункт 140: бои флота лидера. */
  battlesWon?: number;
  battlesLost?: number;
  /** Ход начала срока губернатора и рост его системы за срок, жителей. */
  termStartTurn?: number;
  systemGrowth?: number;
}

/** Офицерский резерв империи — п. 6. */
export interface Leaders {
  offers: Leader[];
  colony: Leader[];
  ship: Leader[];
  colonySlots: number;
  shipSlots: number;
  salaryPerTurn: number;
  credits: number;
}

/** Разведка империи — п. 13. */
export interface Espionage {
  points: number;
  pointsPerTurn: number;
  /** Расовая поправка к очкам за ход. */
  racePoints: number;
  /** Шпионы империи: их строят колонии, каждый приносит очко за ход. */
  spies: number;
  /** Сила контрразведки: столько очков империя отнимает у каждого чужого агента — п. 13. */
  counterStrength: number;
  /** Сами агенты: у каждого своё задание и свои накопленные очки. */
  agents: Spy[];
}

/** Отношения с одной знакомой империей — п. 15. */
export interface DiplomacyRelation {
  playerId: string;
  playerName?: string;
  raceName?: string;
  /** Код справочной расы соседа: по нему выбирается портрет посла. */
  raceCode?: string;
  color?: string;
  /** Код состояния: NEUTRAL, PEACE, WAR. */
  stance: string;
  stanceLabel: string;
  /** Доверие соседа к игроку, 0..100: по нему сосед и решает, соглашаться ли. */
  trust: number;
  /** Доверие игрока к соседу, 0..100: его подтачивают чужие кражи и поимки — п. 13. */
  yourTrust: number;
  /** Действующие договоры кодами — п. 15. */
  treaties: TreatyCode[];
  /** Они же по-русски. */
  treatyLabels: string[];
  /**
   * Правитель соседа двумя словами — «Агрессивный промышленник», как в окне Report
   * меню рас MOO II. Первое говорит, чего ждать в дипломатии, второе — куда он
   * вкладывается. Пусто у человека: за него решает человек.
   */
  character?: string;
  metTurn: number;
  /** Что ответила другая сторона на последнее действие. */
  answer?: string;
  /**
   * Ведутся ли с этой империей переговоры вообще — п. 15, п. 7: ложь, если отталкивающая
   * раса хоть у одной из сторон. Тогда в разговоре остаются только война и мир, и экран
   * переговоров укорачивает список — как в оригинале.
   */
  negotiates?: boolean;
  /** Характер правителя ИИ кодом (`AGGRESSIVE`, `HONORABLE`…) — пункт 43: посол говорит им. */
  personality?: string;
}

/** Договор между империями — п. 15. */
export type TreatyCode = 'TRADE' | 'RESEARCH' | 'NON_AGGRESSION' | 'ALLIANCE';

/** Что игрок предлагает другой империи — п. 15. */
export type DiplomacyActionCode =
  | 'PROPOSE_PEACE'
  | 'PROPOSE_TREATY'
  | 'BREAK_TREATY'
  | 'DEMAND_TRIBUTE'
  | 'DECLARE_WAR'
  | 'EXCHANGE_TECH'
  | 'GIFT_CREDITS'
  | 'GIFT_TECH';

/**
 * Предложение, ждущее ответа человека, — field-backlog, пункт 182: мир, договор, дань и обмен,
 * адресованные живому игроку, решает он сам; ответ исполняется в конце хода, без ответа к концу
 * `deadlineTurn` решает доверие.
 */
export interface DiplomacyProposal {
  id: string;
  fromPlayerId: string;
  fromName: string;
  toPlayerId: string;
  toName: string;
  action: DiplomacyActionCode;
  treaty?: TreatyCode;
  offeredTech?: string;
  offeredTechName?: string;
  requestedTech?: string;
  requestedTechName?: string;
  turn: number;
  deadlineTurn: number;
  /** Ответ адресата; пусто — ещё не ответил. */
  accepted?: boolean;
}

/** Предложения игроку и от игрока — пункт 182. */
export interface DiplomacyProposals {
  incoming: DiplomacyProposal[];
  outgoing: DiplomacyProposal[];
}

/** Технология в списке обмена — п. 15. */
export interface TechnologyOffer {
  code: string;
  name: string;
  categoryCode: string;
  levelOrder: number;
  /** Ценность: стоимость уровня в ОИ. Сосед соглашается, получая не дешевле, чем отдаёт. */
  value: number;
}

/**
 * Что можно обменять с одной знакомой империей — п. 15.
 *
 * Обмен называет обе технологии сразу, поэтому и списка два: чего нет у соседа (это можно
 * предложить) и чего нет у меня (это можно попросить).
 */
export interface TechTrade {
  /** Мои технологии, о которых не известно, что они у соседа есть. */
  offer: TechnologyOffer[];
  /** Увиденные у соседа технологии, которых нет у меня (п. 15, туман войны). */
  wanted: TechnologyOffer[];
  /** Всё, что увидено у соседа в бою или сканерами флота, — доклад о расе. */
  observed: TechnologyOffer[];
}

/** Гнездо компонента корабля — п. 8. */
export type ShipComponentSlot =
  | 'ENGINE'
  | 'ARMOR'
  | 'SHIELD'
  | 'COMPUTER'
  | 'ECM'
  | 'WEAPON'
  | 'SPECIAL';

/** Корпус корабля в окне дизайна — п. 8. */
export interface ShipHull {
  code: string;
  name: string;
  description?: string;
  /** Размер корпуса 1..6: два наименьших колония строит и без звёздной базы. */
  size: number;
  space: number;
  cost: number;
  structure: number;
  command: number;
  /** Шанс попасть по кораблю такого размера: 20 % по фрегату, 100 % по Leviathan. */
  hitChancePercent: number;
  /** Во сколько раз дороже и объёмнее на этом корпусе всё, кроме оружия. */
  systemFactor: number;
  requiredTechCode?: string;
  available: boolean;
}

/** Что компонент даёт кораблю — п. 8: тип, количество и готовая подпись. */
export interface ShipEffect {
  type: string;
  amount: number;
  label: string;
}

/** Компонент корабля в окне дизайна — п. 8. Место и цена даны для фрегата. */
export interface ShipComponentOption {
  /** Название нужной технологии на языке игрока; пусто — технологии не нужно. */
  requiredTechName?: string;
  code: string;
  name: string;
  description?: string;
  slot: ShipComponentSlot;
  space: number;
  cost: number;
  /** Растут ли место и цена вместе с корпусом: у оружия — нет. */
  scalesWithHull: boolean;
  /**
   * Вид выстрела — только у оружия: по нему окно выбора раскладывает пушки лучами,
   * снарядами и ракетами, как полоса BEAM/MISSILE/BOMB в оригинале.
   */
  weaponKind?: WeaponKindCode;
  /**
   * Обволакивает ли удар цель сам собой — п. 8: так бьют плазменная пушка и Копьё новы,
   * и в залпе такой ствол считается вчетверо.
   */
  envelops?: boolean;
  requiredTechCode?: string;
  available: boolean;
  effects: ShipEffect[];
  /**
   * Коды модификаций, которые носит именно это оружие — п. 8: набор у каждого ствола
   * свой, и окно модификаций предлагает только их.
   */
  modifications: string[];
}

/** Что империя может поставить в проект — п. 8. */
export interface ShipCatalog {
  hulls: ShipHull[];
  components: ShipComponentOption[];
  /** Модификации ствола — п. 8: их ставят на оружие в окне дизайна. */
  modifications: WeaponModification[];
  /** Ячеек проектов у империи: шесть, как в MOO II. */
  maxDesigns: number;
  /** Умеет ли империя строить корабли: нужны базовые уровни Power и Chemistry — п. 8. */
  available: boolean;
  /** Чего для этого не хватает; пусто, когда хватает всего. */
  requirement?: string;
  /** Какого размера корпуса поднимает колония без звёздной базы — п. 8. */
  hullSizeWithoutStarBase: number;
}

/** Компонент в готовом проекте — п. 8: место и цена уже с поправкой на корпус. */
export interface ShipDesignComponent {
  code: string;
  name: string;
  slot: ShipComponentSlot;
  count: number;
  space: number;
  cost: number;
  /** Коды модификаций ствола — п. 8; у прочих гнёзд пусто. */
  modifications: string[];
  /** Урон одного выстрела с поправкой модификаций; у не-оружия пусто. */
  damage?: number;
}

/**
 * Модификация ствола — п. 8, «Modifications» окна дизайна MOO II: тяжёлое орудие,
 * ближняя оборона, скорострельное, бронебойное, обволакивающее. Числа приходят с
 * сервера — считает их он.
 */
export interface WeaponModification {
  code: string;
  name: string;
  description: string;
  damagePercent: number;
  attackPercent: number;
  /** Число выстрелов: автоматический огонь — втрое, разделяющаяся боеголовка — вчетверо. */
  shotsPercent: number;
  /** Дальность: тяжёлая установка бьёт вдвое дальше, ближняя оборона — вдвое ближе. */
  rangePercent: number;
  costPercent: number;
  spacePercent: number;
  piercesArmour: boolean;
  piercesShield: boolean;
  /** Обволакивающий удар: приходится на все стороны цели разом. */
  envelops: boolean;
  /** Расстояние не ослабляет удар. */
  noRangePenalty: boolean;
  /** Помехозащита: вдвое срезает уклонение цели от ракет. */
  halvesEvasion: boolean;
  /** Насколько крепче сама ракета: настолько же труднее её сбить. */
  missileArmourPercent: number;
  /** Насколько ракета быстрее — её тоже труднее перехватить. */
  missileSpeed: number;
  /** Пробившись сквозь щит, ракета выбивает двигатель цели. */
  hitsEngine: boolean;
  requiredTechCode?: string;
  /** Изучена ли технология модификации. */
  available: boolean;
}

/** Проект корабля — п. 8: корпус с компонентами и всё, что из них следует. */
export interface ShipDesign {
  id: string;
  slot: number;
  name: string;
  hullCode: string;
  hullName: string;
  components: ShipDesignComponent[];
  space: number;
  spaceUsed: number;
  cost: number;
  structure: number;
  armour: number;
  shield: number;
  speed: number;
  /** Боевая скорость двигателя — п. 8, число оригинала; поле боя делит её на четыре. */
  combatSpeed: number;
  attack: number;
  defense: number;
  /** Уклонение от ракет сверх обычной защиты — п. 8: постановщик помех. */
  missileEvasion: number;
  troops: number;
  command: number;
  /**
   * Прибавка дополнительных баков к дальности в процентах — п. 8. Саму дальность даёт
   * топливо империи (`Fleet.rangeParsecs`), а это — то, что прибавил модуль.
   */
  rangePercent: number;
  /** Боевая сила корабля: по ней сходятся флоты. */
  power: number;
  obsolete: boolean;
}

/** Сохранение проекта в ячейку — п. 8. */
export interface SaveShipDesignPayload {
  slot: number;
  name: string;
  hullCode: string;
  components: { code: string; count: number; modifications?: string[] }[];
}

/** Проект корабля в библиотеке учётной записи — пункт 96: составом, как сохранение проекта. */
export interface ShipTemplate {
  id?: string;
  name: string;
  hullCode: string;
  components: { code: string; count: number; modifications?: string[] }[];
  /** Части, которые справочник поменял с сохранения (пункт 176), — названиями; пусто — ничего. */
  changed?: string[];
  /** Корпус названием — сервер отдаёт, чтобы библиотеку можно было смотреть вне партии. */
  hullName?: string;
  /** Состав строками «название ×N». */
  parts?: string[];
}

/** Своя раса в учётной записи — пункты 26 и 176: составом и с ценами на миг сохранения. */
export interface RaceTemplate {
  id?: string;
  name: string;
  traits: string[];
  /** Стороны с ценой на миг сохранения и нынешней (пусто — стороны больше нет). */
  details?: { code: string; name: string; savedPicks?: number; picks?: number }[];
  /** Свободных очков выбора на миг сохранения. */
  savedFree?: number;
  /** Свободных сейчас: больше прежнего — балансировка расе прибавила. */
  free?: number;
  /** Почему расу сейчас нельзя взять как есть; пусто — законна. */
  refusal?: string;
  /** Чья это раса — пункт 99: при сохранении — раса автора из ссылки; в ответе — есть ли автор. */
  originId?: string;
  /** Сколько людей сыграли расой по вашей ссылке — пункт 99; пусто — никто. */
  plays?: { people: number; games: number; finished: number; wins: number };
}

/** Что выбрал бы советник — пункт 23. */
export interface Advice {
  research?: {
    categoryCode: string;
    levelOrder: number;
    optionCode: string;
    optionName: string;
    /** Нужда, к которой совет лишь шаг; пусто — он сама нужда. */
    wantedName?: string;
    reason: string;
    /** Игрок уже исследует именно это. */
    chosen: boolean;
  };
  colonies: {
    planetId: string;
    planetName: string;
    projectCode: string;
    projectName: string;
    paybackTurns?: number;
    /** Колония свободна — на товарах или без стройки. */
    idle: boolean;
    reason: string;
  }[];
}

/** Флот империи — п. 8. */
export interface Fleet {
  attackPercent: number;
  defensePercent: number;
  designs: ShipDesign[];
  /** Построенные флоты по системам и в пути — п. 8. */
  fleets: FleetGroup[];
  /**
   * Дальность топлива империи в парсеках — п. 8: столько пролетает корабль БЕЗ
   * дополнительных баков.
   */
  rangeParsecs: number;
  /** Куда долетают корабли без баков: по этому списку карта красит линию к звезде. */
  reachableSystemIds: string[];
  /**
   * То же для кораблей С дополнительными баками — п. 8. Списка два, а не по одному на
   * флот, потому что дальностей всего две: с баками и без них.
   */
  reachableFarSystemIds: string[];
  /** Курс флота в полёте можно сменить — изучена Hyperspace Communications. */
  canRedirect: boolean;
  /** Раса телепатов — п. 7: крупный корабль подчиняет чужую колонию без десанта. */
  telepathic: boolean;
  /** Сколько командных очков занимают корабли империи — п. 8. */
  commandUsed: number;
  /** Сколько их дают колонии; перебор оплачивается казной каждый ход. */
  commandCapacity: number;
  /** Изучены прыжковые врата: от своей колонии флот летит на 3 парсека за ход быстрее. */
  jumpGate?: boolean;
  /** Изучены звёздные врата: от колонии к колонии — один ход при любом расстоянии. */
  starGate?: boolean;
}

/** Сторона тактического боя — п. 8. */
export type BattleSideCode = 'ATTACKER' | 'DEFENDER';

/** Корабль на поле боя — п. 8. */
export interface BattleShip {
  id: string;
  ownerPlayerId: string;
  ownerName: string;
  side: BattleSideCode;
  designName: string;
  hullName: string;
  /** Код корпуса справочника: по нему сцена выбирает рисунок платформы или чудища. */
  hullCode: string;
  /** Размер корпуса 1..6: им и меряется рисунок корабля на поле. */
  hullSize: number;
  ordinal: number;
  x: number;
  y: number;
  structure: number;
  maxStructure: number;
  armour: number;
  maxArmour: number;
  /** Запас щита сейчас — п. 8: падает под огнём, восстанавливается на 30 % за круг. */
  shield: number;
  /** Полный запас щита; у ответов старого сервера поля нет. */
  maxShield?: number;
  /** Класс щита: столько он срезает с каждого выстрела. */
  shieldClass?: number;
  attack: number;
  /** Инициатива: по ней строится очередь хода — ходят корабли, а не игроки. */
  initiative: number;
  speed: number;
  moveLeft: number;
  /** Насколько далеко бьёт самый дальнобойный ствол корабля — п. 8: у каждого свой. */
  weaponRange: number;
  fired: boolean;
  destroyed: boolean;
  yours: boolean;
  /**
   * Корпус — орбитальная платформа (п. 8, п. 11): звёздная база, боевая станция,
   * крепость, ракетная база, наземные батареи, планетарный щит. На поле такая вещь
   * рисуется сооружением, а не кораблём: в оригинале база и не похожа на корабль.
   */
  platform: boolean;
  /** На поле не корабль, а космическое чудище — п. 11.1: рисуется существом. */
  monster: boolean;
  /** Защита от лучей — та же величина, что в окне дизайна. */
  defense: number;
  /** Уклонение от ракет в процентах: его даёт постановщик помех. */
  missileEvasion: number;
  /** Чем корабль вооружён — п. 8: нижняя полоса сцены и окно осмотра. */
  weapons: BattleWeapon[];
  /** Особые модули корабля названиями. */
  specials: string[];
  /** Двигатель разбит — п. 8: корабль неподвижен и с поля боя уже не уйдёт. */
  engineWrecked: boolean;
  /** Щит разбит: между кругами ему восстанавливаться нечем — п. 8. */
  shieldWrecked: boolean;
  /** Прицельный компьютер сожжён: корабль целится голым прицелом — п. 8. */
  computerWrecked: boolean;
  engineName?: string;
  armourName?: string;
  shieldName?: string;
  computerName?: string;
  /** Шанс противоракет в упор, в процентах (пункт 30); ноль — их на корабле нет. */
  antiMissile: number;
  /** Морская пехота на борту — п. 8, абордаж. */
  marines: number;
  /** Корабль взят абордажем и воюет за захватчика — п. 8. */
  captured: boolean;
  /** С какого расстояния корабль идёт на абордаж: 1 — вплотную, больше — телепортом. */
  boardingRange: number;
  /** Обездвижен: двигатель разбит или держат тяговые лучи — его можно брать вплотную. */
  immobile: boolean;
  /** Получил приказ отходить: развёрнут к своему краю и уйдёт в свой следующий ход — п. 8. */
  retreating?: boolean;
  /** Ушёл с поля отходом — на поле его нет, но он и не погиб. */
  retreated?: boolean;
  /** Проект собран игроком, а не игрой — пункт 98: карточка боя называет его «ваш проект». */
  crafted?: boolean;
  /** Заморожен стазисным полем — п. 8: не ходит, не стреляет, атаковать его нельзя. */
  frozen?: boolean;
  /** Замаскирован — п. 8: защищён от лучей и ракет, пока не выстрелит. */
  cloaked?: boolean;
}

/** Ствол корабля в бою — п. 8: строка таблицы оружия в нижней полосе и окне осмотра. */
export interface BattleWeapon {
  name: string;
  count: number;
  damage: number;
  kind?: WeaponKindCode;
  /** Модификации этого ствола; пусто — их нет. */
  modifications: string[];
  /**
   * Сколько гнёзд этого ствола выбито попаданиями по корпусу — п. 8.
   *
   * Ноль значит «все на месте»: таблица оружия показывает не «пушка есть», а «пушка
   * есть, да не стреляет».
   */
  wrecked: number;
  /** Дальность ствола в клетках — п. 8: по ней прицел показывает, достаёт ли оружие; у ракет — путь на топливе. */
  range?: number;
}

/** Чем стреляли — п. 8: сцена рисует луч, снаряд и ракету по-разному. */
export type WeaponKindCode = 'BEAM' | 'PROJECTILE' | 'MISSILE' | 'BOMB';

/** Что случилось в бою — п. 8: строка журнала и повод для картинки на поле. */
export interface BattleEvent {
  /**
   * У ракет ещё три (backlog-promo, пункт 30): LAUNCH — ушла, но не долетела; MISSILE_MOVE —
   * летит дальше; MISSILE_LOST — кончилось топливо, цели больше нет или сбиты все;
   * INTERCEPT — ближняя оборона сбила ракеты (`x`, `y` — где).
   */
  type: 'MOVE' | 'FIRE' | 'DESTROYED' | 'PASS' | 'RETREAT' | 'WARP_OUT' | 'FINISHED'
    | 'LAUNCH' | 'MISSILE_MOVE' | 'MISSILE_LOST' | 'INTERCEPT'
    // Абордаж (п. 8): тяговый луч, захват, рейд и пехота, выжженная нейтронным огнём.
    | 'TRACTOR' | 'BOARD' | 'RAID' | 'MARINES' | 'STASIS';
  shipId?: string;
  targetShipId?: string;
  x?: number;
  y?: number;
  damage?: number;
  /** Вид оружия — только у залпа. */
  weaponKind?: WeaponKindCode;
  text: string;
  /** Сколько выстрелов ушло этим видом оружия — только у залпа: столько ракет и летит. */
  shots?: number;
  /**
   * Причина гибели — только у DESTROYED (field-backlog, пункт 204): SHOT, BLAST, BLAST_HIT,
   * SELF_DESTRUCT, STRANDED; `targetShipId` тогда — тот, чей удар или взрыв был последним.
   */
  cause?: 'SHOT' | 'BLAST' | 'BLAST_HIT' | 'SELF_DESTRUCT' | 'STRANDED';
  /** Откуда летит выстрел, если не от корабля: ракета в полёте — со своей клетки (пункт 30). */
  fromX?: number;
  fromY?: number;
}

/** Ракетный залп в полёте — backlog-promo, пункт 30. */
export interface BattleMissile {
  id: number;
  side: BattleSideCode;
  shooterShipId: string;
  targetShipId: string;
  x: number;
  y: number;
  count: number;
  /** Клеток за круг. */
  speed: number;
  /** Сколько кругов полёта ещё осталось. */
  fuel: number;
  /** Пехота на борту, если это штурмовые шаттлы (п. 8, абордаж); у ракет — пусто. */
  marines?: number;
}

/**
 * Голос империи на выборах Высшего совета — п. 3.
 *
 * Вес голоса — население империи, как в оригинале; пустой выбор значит «воздержалась».
 */
export interface CouncilVoter {
  playerId: string;
  name: string;
  color?: string;
  weight: number;
  choicePlayerId?: string;
  candidate: boolean;
  /** Это империя того, кто смотрит сцену. */
  yours: boolean;
  /** Голос ещё не объявлен: совет открыт (чужие скрыты до итога) или игрок не ответил. */
  pending: boolean;
  /** Свой голос подан по доверию: игрок не ответил до итога. */
  silent?: boolean;
}

/** Выборы Высшего совета — п. 3: всё, что нужно сцене совета. */
export interface Council {
  turn: number;
  totalVotes: number;
  requiredVotes: number;
  electedPlayerId?: string;
  electedName?: string;
  /** Двое кандидатов — их имена стоят в заголовке сцены. */
  candidates: CouncilVoter[];
  /** Все голосующие в порядке объявления: сперва тяжёлые голоса. */
  voters: CouncilVoter[];
  /** Избрания уже не признали: партия идёт дальше, второго отказа не бывает — п. 3. */
  refused: boolean;
  /** Этот игрок вправе не подчиниться избранному правителю — п. 3. */
  canRefuse: boolean;
  /** Совет созван и ждёт голосов людей: итог подведут в конце хода — п. 3. */
  open: boolean;
  /** Этого игрока спрашивают: он голосует сам из сцены совета. */
  ballot: boolean;
}

/** Тактический бой — п. 8: всё, что нужно сцене. */
export interface Battle {
  id: string;
  starSystemId: string;
  systemName: string;
  turn: number;
  round: number;
  state: 'IN_PROGRESS' | 'FINISHED';
  stateLabel: string;
  width: number;
  height: number;
  /** Дальность залпа в клетках — одна на все стволы. */
  weaponRange: number;
  yourSide: BattleSideCode;
  attackerName: string;
  defenderName: string;
  currentShipId?: string;
  yourTurn: boolean;
  /** Сила уцелевших кораблей стороны: по ней видно, кто сильнее и как перевес тает. */
  attackerPower: number;
  defenderPower: number;
  ships: BattleShip[];
  /** Очередь хода в круге: корабли по убыванию инициативы. */
  queue: string[];
  events: BattleEvent[];
  outcome?: string;
  /** Ракеты в полёте: залпы, не долетевшие в круг пуска (пункт 30). */
  missiles?: BattleMissile[];
}

/**
 * Демонстрационный бой — п. 8: сцена, которую смотрят из главного меню, не начиная партии.
 * Силы сторон — те, с которыми флоты сошлись: по ним и виден обещанный перевес.
 */
export interface DemoBattle {
  battle: Battle;
  leftName: string;
  rightName: string;
  leftPower: number;
  rightPower: number;
  advantagePercent: number;
}

/** Ход корабля в бою — п. 8. */
export interface BattleActionPayload {
  shipId: string;
  action: 'MOVE' | 'FIRE' | 'PASS' | 'RETREAT' | 'SELF_DESTRUCT' | 'BOARD' | 'RAID';
  x?: number;
  y?: number;
  targetShipId?: string;
  /** Какие строки оружия стреляют — номера в `ship.weapons`; пусто — всё. */
  weaponRows?: number[];
  /** По какому залпу ракет в полёте стрелять вместо корабля (пункт 30) — `BattleMissile.id`. */
  targetMissileId?: number;
}

/** Итог наземного боя — п. 12. */
export interface InvasionResult {
  captured: boolean;
  survivors: number;
  attackPower: number;
  defencePower: number;
  system: StarSystem;
}

/** Группа особенностей: из группы берут не больше одной — п. 7. */
export interface RaceTraitGroup {
  code: string;
  name: string;
  description: string;
  /**
   * Можно ли взять несколько вариантов сразу — п. 7. Так устроены особые способности
   * MOO II: всевидящий бывает и скрытным, и подземным разом. Обычная группа —
   * переключатель, из неё берут одну сторону.
   */
  multiple: boolean;
  options: RaceTrait[];
}

/**
 * Конструктор расы — п. 7: бюджет очков и стороны расы.
 *
 * Чисел два, и они разные. `picks` — сколько очков можно потратить, `antiPicks` — сколько
 * не больше вернуть слабыми сторонами. В MOO II они совпадали (десять и десять, и ровно
 * десять возвращали себе Силикоиды); здесь бюджет вырос до пятнадцати, а потолок остался
 * прежним, отчего продавать слабости стало выгодно меньше.
 */
export interface RaceDesign {
  picks: number;
  antiPicks: number;
  /**
   * Связки с надбавкой: пара, которая вместе даёт больше суммы своих половин, берёт очки
   * СВЕРХ своих частей. Приходит с сервера, потому что очки конструктор считает на лету, и
   * надбавка, всплывшая только в отказе сервера, читалась бы как поломка.
   */
  combinations?: RaceCombination[];
  groups: RaceTraitGroup[];
}

/** Связка сторон, которые дорожают вместе, — п. 7. */
export interface RaceCombination {
  /** Коды сторон: надбавка берётся, когда взяты все. */
  traits: string[];
  names: string[];
  /** Сколько очков связка стоит сверх своих частей. */
  picks: number;
  /** На чём измерена. */
  note?: string;
}

/**
 * Правка цен особенностей — п. 7: то, что сохраняет экран «Стоимость особенностей рас».
 * Меняются только цены и бюджет: набор особенностей и их действия живут в справочнике
 * сервера и редактором не трогаются.
 */
export interface SaveRaceTraitCostsPayload {
  /** Бюджет очков расы; не передан — остаётся прежним. */
  picks?: number;
  traits: { code: string; picks: number }[];
}

/**
 * Замер империи за один ход — п. 11.1: точка на графике окна «Инфо».
 *
 * Величины сняты в конце хода: то, что игрок и сравнивает по соседям — сколько у
 * империи людей, сколько она делает, сколько знает и чем воюет.
 */
export interface EmpireHistoryPoint {
  turn: number;
  /** Население в тысячах — в тех же единицах, что и у колонии. */
  populationK: number;
  colonies: number;
  /**
   * Постройки ценой — величина графика оригинала: «каждое здание добавляет свою
   * стоимость производства». Пусто у партий, поднятых из слепка без этой величины.
   */
  buildings?: number;
  production: number;
  research: number;
  fleetPower: number;
  technologies: number;
  credits: number;
  /**
   * Мощь империи одним числом: флот, население, выработка и изученное вместе.
   * Считает сервер (`EmpireMightRules`) — это же число сравнивают между собой
   * империи ИИ, решая, нападать ли (п. 15).
   */
  might: number;
}

/** Сторона расы в окне «Инфо» — п. 7: название и цена в очках. */
export interface EmpireTrait {
  code: string;
  name: string;
  /** Цена особенности; отрицательная — особенность возвращает очки. */
  picks: number;
}

/** Империя в окне «Инфо» — п. 11.1: кто это, чем её раса особенна и как шли её дела. */
export interface EmpireProfile {
  playerId: string;
  name: string;
  raceName?: string;
  color: string;
  /** Своя ли это империя: на графике своя линия выделена. */
  own: boolean;
  government?: string;
  /** Правитель ИИ двумя словами — «Агрессивный промышленник»; пусто у человека. */
  character?: string;
  traits: EmpireTrait[];
  history: EmpireHistoryPoint[];
}

/**
 * Окно «Инфо» — п. 11.1: летопись империй и их описание.
 *
 * Приходит одним запросом на весь экран: он открывается целиком, и отдельные запросы
 * рисовали бы его частями. Империи здесь только знакомые и своя — п. 15.
 */
export interface EmpireInfo {
  turn: number;
  empires: EmpireProfile[];
}

/** Учётная запись игрока — п. 3.1. Пароля здесь нет: наружу он не уходит никогда. */
export interface Account {
  id: string;
  /** Логин — он же почта: отдельного логина у записи нет (кроме администратора). */
  login: string;
  /** Своё знамя — пункт 144; пусто — не выбирал. */
  banner?: string;
  email?: string;
  /** Имя для показа в интерфейсе игры; входить по нему нельзя. */
  name: string;
  /**
   * Код роли: PLAYER, ADMIN или GUEST. Гость играет без регистрации одну партию —
   * малую галактику против трёх ИИ (backlog-promo, пункт 1).
   */
  role: string;
  roleLabel: string;
  /**
   * Язык игрока — п. 3.5: `en`, `ru` или пусто, если он его не называл.
   *
   * Клиент ставит его сразу после входа: запись переживает и браузер, и машину, а
   * `localStorage` — нет, и сев за другую машину игрок получал бы английский заново.
   */
  locale?: string;
  /**
   * Размер текста интерфейса в процентах — п. 11.1: 100, 115, 130, 150 или пусто.
   *
   * Едет за игроком по той же причине, что и язык: выбранный однажды крупный шрифт не
   * должен теряться на второй машине.
   */
  textScale?: number;
  /**
   * Громкость музыки и звуковых эффектов ступенью 0…3 или пусто. Пусто значит «игрок звука не
   * трогал», то есть ВЫКЛЮЧЕНО: звук, заигравший сам при первом входе, раздражает сильнее
   * тишины. Едет за игроком, как язык.
   */
  musicVolume?: number;
  sfxVolume?: number;
  /**
   * Раскладка горячих клавиш карты — п. 11.1: «действие: клавиша» (`{"turn": "KeyT"}`) или
   * пусто, если игрок клавиш не переназначал.
   *
   * Третья настройка вида, которая едет за игроком: клавиши помнят пальцами, и стандартные
   * на второй машине — это та же потеря, что английский язык или мелкий шрифт. Сервер эти
   * пары не толкует: что они значат, знает только клиент (`ui/hotkeys.ts`).
   */
  hotkeys?: Record<string, string>;
  /** Подсказки новичку — пункт 30: пусто — ещё не спрашивали. */
  noviceHints?: boolean;
  /** Подсказки, отклонённые «больше не показывать», — пункт 130. */
  dismissedHints?: string[];
  /** Окна, которые не открываются сами, — пункт 164; пусто — открываются все. */
  quietWindows?: string[];
  /** Меньше движения — пункт 170: пусто — как в системе. */
  reduceMotion?: boolean;
  /** Знаки знамён у хозяев на карте — пункт 202: пусто — выключены. */
  bannerGlyphs?: boolean;
  /** Действующие права записи (backlog-promo, пункт 41): FOUNDER, EARLY_ACCESS, SUPPORTER. */
  entitlements?: string[];
  /** Открыты ли страницы прибора — баланс рас и «как сделано» (пункты 47, 70, 101). */
  insider?: boolean;
  /** Новая почта, ждущая подтверждения по ссылке (пункт 45). */
  pendingEmail?: string;
  /** Уникальный игровой код — номер записи по порядку заведения (личный кабинет). */
  playerCode?: number;
  /** Советник — пункт 23: пусто — по умолчанию (выключен, в гостевой партии включён). */
  advisor?: boolean;
  /** Когда запись заведена. */
  createdAt?: string;
  /** Сцены, увиденные целиком, — пункт 163: дальше они идут строкой итогов. */
  seenScenes?: string[];
  /** Письма о ходе в партии с людьми — пункт 22; пусто — выключены. */
  notifyMail?: boolean;
}

/**
 * Учётная запись в списке администратора — п. 3.1.
 *
 * Того, чем запись открывают, здесь нет: ни пароля, ни ссылки подтверждения. Список нужен
 * ради одного действия — подтвердить застрявшую регистрацию.
 */
export interface AccountSummary {
  id: string;
  login: string;
  email?: string;
  name: string;
  role: string;
  roleLabel: string;
  /** Почта подтверждена; неподтверждённая запись в игру не пускает. */
  confirmed: boolean;
  createdAt: string;
}

/** Сеанс после входа — п. 3.1: пропуск, срок и кто вошёл. */
export interface AccountSession {
  token: string;
  expiresAt: string;
  account: Account;
}

/**
 * Что вышло из регистрации — п. 3.1.
 *
 * `mailSent` — ушло ли письмо по-настоящему; false значит, что почтовый сервер не
 * настроен и письмо лежит в исходящих сервера. Это не ошибка, а положение дел, и
 * сказать о нём игроку надо прямо — иначе он будет ждать письма, которого нет.
 */
export interface RegisterResult {
  login: string;
  email: string;
  mailSent: boolean;
  notice: string;
}

/**
 * Задачка перед регистрацией — п. 3.1: «докажите, что вы человек».
 *
 * Ответа здесь нет: он остаётся у сервера. Вопрос одноразовый, поэтому после неудачной
 * заявки форма берёт новый. Задачка выключается настройкой сервера — тогда вместо неё
 * приходит пустота, и поля ответа в форме нет.
 */
export interface RegistrationChallenge {
  id: string;
  question: string;
  hint: string;
}

export interface CreateGamePayload {
  name?: string;
  /**
   * Идут ли в партии случайные галактические события — п. 11.1; пусто — идут.
   * Переключатель окна новой игры, как в MOO II.
   */
  galacticEvents?: boolean;
  /**
   * Сколько всего империй в партии, считая создателя, — п. 3; пусто — как задано
   * настройкой сервера. В MOO II число соперников выбирают в окне новой игры.
   */
  totalPlayers?: number;
  /** Название расы, собранной игроком — п. 7. */
  raceName?: string;
  /** Коды выбранных особенностей расы — п. 7. */
  raceTraits?: string[];
  galaxySize?: string;
  /**
   * Собирается ли в этой партии Высший совет — п. 3; пусто — собирается.
   *
   * Выключают его замеры и обучающая партия: избранный правитель обрывает партию раньше
   * срока, а обучение должно кончиться тогда, когда игрок научился, а не когда двое
   * соседей договорились.
   */
  council?: boolean;
  /** Побеждает ли удержание Wardenhold двадцать ходов — п. 3; пусто — нет. */
  wardenholdVictory?: boolean;
  /** Подводится ли итог по могуществу на трёхсотом ходу — п. 3; пусто — нет. */
  mightVictory?: boolean;
  /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
  turnSeconds?: number;
  /** Разрешить зрителей — field-backlog, пункт 59; пусто — нельзя. */
  spectators?: boolean;
  /** Закрытая партия — пункт 67: только по ссылке-приглашению; пусто — открытая. */
  inviteOnly?: boolean;
  /** Ход итога по могуществу (пункт 24): 100, 200 или 300; пусто — 300. */
  mightTurn?: number;
  /** Тихая галактика (пункт 186): ИИ войну первым не начинает. */
  peacefulAi?: boolean;
  /** Особенность галактики — пункт 94: RANDOM, NONE или код. */
  galaxyFeature?: string;
  /** Галактика недели — пункт 146. */
  weekly?: boolean;
  /** Уровень сложности — пункт 9: BEGINNER, EASY, NORMAL или HARD; пусто — обычный. */
  aiSkill?: string;
  playerName: string;
  /** Название родной звезды игрока; пусто — имя выберет генератор. */
  homeStarName?: string;
  raceCode?: string;
  /** Знамя — цвет империи, п. 3.2 (`BannerColor` сервера); пусто — знамя расы. */
  banner?: string;
  seed?: number;
  /** Учебная партия — пункт 41: звания по ней не считаются. */
  tutorial?: boolean;
  /** Своя раса из личного кабинета — пункт 99: раса по чужой ссылке засчитывается автору. */
  raceTemplateId?: string;
  /** Свой набор справочников основателя — пункт 42: партия одиночная, в историю не идёт. */
  modId?: string;
}

/**
 * Балансовый прогон пульта администратора — этап 2 плана.
 *
 * `traitCosts` — снимок цен на миг запуска: прогон мерил именно их, а не те, что в файле
 * сейчас. Без снимка нельзя сказать, какие цены породили эти приговоры.
 */
export interface BalanceRun {
  id: string;
  createdAt: string;
  finishedAt?: string;
  status: 'RUNNING' | 'PAUSED' | 'FINISHED' | 'FAILED';
  galaxySize: string;
  empires: number;
  games: number;
  turns: number;
  played: number;
  seed: number;
  races: BalanceRunRace[];
  traitCosts: Record<string, number>;
  /** Измеренный курс очка: сколько долей выработки приносит одно очко цены. */
  pointValue?: number;
  /**
   * Невязка цены в очках — мера успеха круга балансировки: на сколько цена расходится с
   * тем, что сторона даёт, медианой по сторонам. Чем меньше, тем лучше сведена таблица.
   * Счёт адекватных для этого не годится — он растёт от размытости прогона.
   */
  residual?: number;
  /**
   * Ход решённости медианой по партиям, в процентах их длины (пункт 155): с какого места будущий
   * победитель первый до конца. Пусто — прогон старый или ещё идёт.
   */
  decidedPercent?: number;
  /** Хвост — ходов после решённости, медианой. */
  tailTurns?: number;
  /** Сколько сторон удалось оценить: прочие взяли слишком редко. */
  measured?: number;
  judgements: BalanceJudgement[];
  /** Найденные связки сторон: что пара даёт сверх суммы половин. */
  synergies: BalanceSynergy[];
  /** Проверяемая связка: стороны, подсаженные в сборки нарочно. Пусто — обычный прогон. */
  combination: string[];
  /** Род прогона: приговоры ценам или поиск сильнейших сборок. */
  kind: 'MEASURE' | 'ORACLE';
  /** Сколько сборок в первом поколении поиска; у замера пусто. */
  population?: number;
  /** Ладдер оракула; у замера пусто. */
  oracle?: BalanceOracle;
  failure?: string;
}

/** Чем играла одна империя прогона. */
export interface BalanceRunRace {
  slot: number;
  name: string;
  traits: string[];
  budget: number;
}

/** Приговор цене одной стороны расы. */
export interface BalanceJudgement {
  code: string;
  name: string;
  price: number;
  takers: number;
  /** Измеренная сила по всем трём мерилам разом, в п.п. — по ней и выносится приговор. */
  strength?: number;
  error?: number;
  /** Из чего сила сложилась: доля выработки. */
  production?: number;
  /** Она же по науке. */
  research?: number;
  /** Она же по разведке: доля доведённых до конца заданий. */
  espionage?: number;
  /** Она же по деньгам: доля дохода за ход. */
  money?: number;
  /** Она же по военной силе: доля силы флотов. */
  military?: number;
  /** Она же по наземному бою: доля взятых десантом колоний за вычетом потерянных. */
  ground?: number;
  /** Она же по широте науки: доля изученных технологий. */
  technology?: number;
  /** Цена, которую эта сила заслуживает по измеренному курсу очка. */
  fairPrice?: number;
  /** Куда двигать цену ОДНИМ шагом — долей пути к справедливой; пусто — двигать не надо. */
  recommendedPrice?: number;
  verdict: 'FAIR' | 'TOO_EXPENSIVE' | 'TOO_CHEAP' | 'NOT_MEASURED';
  verdictLabel: string;
}

/**
 * Связка двух сторон расы: что они дают вместе сверх того, что дают порознь.
 *
 * Отдельно взвешенная сторона отвечает на вопрос «сколько она стоит в среднем», и у
 * половины таблицы честный ответ «нисколько»: киборги сами по себе только едят, а с
 * промышленниками и голодным миром они и есть Меклар.
 */
export interface BalanceSynergy {
  /** Коды сторон связки: две или три. */
  members: string[];
  names: string[];
  /** У скольких империй взяты все её стороны сразу. */
  pairs: number;
  /** Что связка стоит по таблице: сумма цен её сторон. */
  price: number;
  /** Прибавка сверх всего, что дают её части поодиночке и более мелкими связками, в п.п. */
  extra?: number;
  error?: number;
  /** Вся сила связки: её стороны, её внутренние пары и сама прибавка. */
  together?: number;
  verdict: 'SYNERGY' | 'ANTI' | 'PLAIN';
  verdictLabel: string;
}

/**
 * Связка, которую стоит проверить, и что о ней известно.
 *
 * Связок из полусотни сторон больше двадцати тысяч, и перебирать их незачем: смысл имеют
 * считанные — те, о которых есть догадка, зачем игрок берёт эти стороны вместе. Догадка
 * живёт до прогона, поэтому у неё есть объяснение словами.
 */
export interface BalanceCombination {
  id: string;
  traits: string[];
  names: string[];
  /** Зачем проверяем — единственное, чего замер не восстановит сам. */
  note?: string;
  createdAt: string;
  checkedAt?: string;
  runId?: string;
  verdict?: 'SYNERGY' | 'ANTI' | 'PLAIN' | 'NOT_MEASURED';
  verdictLabel?: string;
  extra?: number;
  error?: number;
  carriers?: number;
}

/**
 * Ладдер оракула сборок — этап 3.
 *
 * Цены назначаются по случайным сборкам, а игрок случайной не играет: он ищет сильнейшую.
 * Оракул ищет её нарочно и отвечает на два условия плана — нет ли доминирующей сборки и
 * какие стороны в верхушку не вошли вовсе.
 */
export interface BalanceOracle {
  ladder: BalanceOracleBuild[];
  /** Сила сильнейшей сборки. */
  best?: number;
  /** Сила середины верхушки. */
  median?: number;
  /** Насколько сильнейшая обгоняет середину верхушки. */
  gap?: number;
  /** Есть ли доминирующая сборка: разрыв больше допуска. */
  dominated: boolean;
  /** Стороны, не вошедшие ни в одну сборку верхушки. */
  deadTraits: string[];
  deadNames: string[];
  /** Стороны, которых поиск не пробовал вообще: про них сказать нечего. */
  unseenTraits: string[];
  unseenNames: string[];
  generations: number;
}

/** Одна сборка ладдера. */
export interface BalanceOracleBuild {
  traits: string[];
  names: string[];
  budget: number;
  /** Сколько партий сборка сыграла: у выживших их больше. */
  games: number;
  strength?: number;
  error?: number;
}

/** Заказ прогона: чем играют империи и сколько партий сыграть. */
export interface BalanceRunPayload {
  galaxySize?: string;
  empires: number;
  games: number;
  turns: number;
  /** По записи на место: готовая раса или случайная сборка на бюджет. */
  races?: { raceCode?: string; budget?: number }[];
  /**
   * Проверяемая связка — две или три стороны, которые прогон подсадит в сборки нарочно: по
   * жребию на каждую сторону решается, войдёт она в сборку или нет, и все сочетания выходят
   * поровну. Случайная сборка редкую связку не ловит ни при какой длине прогона.
   */
  combination?: string[];
  /** Род прогона: приговоры ценам (по умолчанию) или поиск сильнейших сборок. */
  kind?: 'MEASURE' | 'ORACLE';
  /** Сколько сборок в первом поколении поиска; только для оракула. */
  population?: number;
  seed?: number;
}

export interface ApiError {
  status: number;
  error: string;
  message: string;
  details?: string[];
  timestamp: string;
}

/**
 * Состояние Орионометра — записи игры человека в ОРИГИНАЛ (MOO II).
 *
 * Пустые поля с сервера не приходят вовсе (`non_null`), поэтому они необязательные.
 */
export interface Orionometer {
  running: boolean;
  gameOpen: boolean;
  seconds?: number;
  clicks?: number;
  shots?: number;
  scene?: string;
  log?: string;
  data?: string;
  brainRunning: boolean;
  failure?: string;
}

/**
 * Шаблон стройки — п. 10: порядок развития, который закладывают колонии одним движением.
 *
 * Принадлежит учётной записи, а не партии: порядок развития — привычка игрока, он
 * переживает партию и нужен в главном меню, где партии ещё нет.
 */
export interface BuildTemplate {
  id: string;
  name: string;
  /** Стадия развития, назначенная игроком (1, 2, 3…). Игра её не толкует. */
  tier: number;
  /** Коды проектов ПО ПОРЯДКУ: те же, что в списке стройки колонии. */
  projects: string[];
}

/** Здание справочника — п. 10: экрану шаблонов стройки нужен список того, что бывает. */
export interface BuildingRef {
  code: string;
  name: string;
  description: string;
  cost: number;
  upkeep: number;
  requiredTechCode?: string;
}

/** Сообщение о проблеме в списке хозяина — field-backlog, пункт 157. */
/** Частый отказ игрокам — field-backlog, пункт 77: ключ, текст, сколько раз и когда последний. */
export interface RefusalCount {
  key: string;
  text: string;
  refusals: number;
  lastAt: string;
}

/**
 * Что ждёт пришедшего по приглашению — field-backlog, пункт 15: партия, ход, срок хода и место —
 * в лобби (`LOBBY`) или империя, которую ведёт ИИ (`EMPIRE`); пусто — мест нет.
 */
export interface InvitePreview {
  gameName: string;
  status: GameStatus;
  turn: number;
  galaxySize?: string;
  humanPlayers: number;
  totalPlayers: number;
  turnSeconds?: number;
  seat?: 'LOBBY' | 'EMPIRE';
  empire?: string;
  color?: string;
  host?: string;
  /**
   * Империи ИИ, которые можно взять в идущей партии, — пункт 66: выбирает пришедший; в войне с
   * пригласившим — помечены.
   */
  empires?: { playerId: string; name: string; color: string; raceName?: string; colonies: number;
    atWarWithHost: boolean }[];
}

/** Что понравилось больше всего — вопрос пункта 187. */
export type FeedbackLiked = 'HARD_WIN' | 'DISCOVERY' | 'BUILDING' | 'PEOPLE';

/** Отзыв после партии для администратора — field-backlog, пункты 46 и 187. */
export interface GameFeedback {
  rating: number;
  liked?: FeedbackLiked;
  comment?: string;
  outcome?: string;
  victoryKind?: string;
  galaxySize?: string;
  empires?: number;
  turn?: number;
  createdAt: string;
}

export interface ProblemReport {
  id: string;
  accountId: string;
  accountName?: string;
  accountRole?: string;
  gameId?: string;
  turn?: number;
  clientVersion?: string;
  screen?: string;
  text: string;
  /** Что клиент собрал сам: последние сбои, JSON строкой. */
  context?: string;
  resolved: boolean;
  createdAt: string;
  /** Ответ хозяина — field-backlog, пункт 158; пусто, пока не ответили. */
  answerKind?: ProblemAnswerKind;
  answerVersion?: string;
  answerText?: string;
  answeredAt?: string;
  /** Написавший прочёл ответ. */
  answerSeen?: boolean;
}

/** Ответ хозяина на сообщение о проблеме — пункт 158: исправлено или «не будем исправлять». */
export type ProblemAnswerKind = 'FIXED' | 'WONT_FIX';

/** Ответ на своё сообщение глазами написавшего — пункт 158. */
export interface ProblemAnswer {
  id: string;
  reportedAt: string;
  /** Начало его же сообщения — узнать, о чём речь. */
  excerpt: string;
  kind: ProblemAnswerKind;
  version?: string;
  text?: string;
}

/**
 * Раздел «что нового» — пункты 159 и 161: потерянное и что делать, правила, цены сторон рас,
 * новое, исправленное. Порядок показа — этот же.
 */
export type NoticeKind = 'loss' | 'changed' | 'prices' | 'new' | 'fixed';
export const NOTICE_KINDS: NoticeKind[] = ['loss', 'changed', 'prices', 'new', 'fixed'];

/** Запись «что нового» — field-backlog, пункты 159 и 161. */
export interface Notice {
  id: string;
  date: string;
  /** Версия, с которой изменение вышло. */
  version: string;
  kind: NoticeKind;
  text: string;
}

/**
 * Страница пакета основателя — целиком и на обоих языках (`FounderPageDto`): переключатель
 * языка меняет её сразу, без нового запроса, а администратор правит её так же целиком.
 */
export interface FounderPage {
  price?: string;
  seatsTotal: number;
  seatsTaken: number;
  published: boolean;
  /** Куда ведёт кнопка покупки; пусто — продажа ещё не открыта. */
  buyUrl?: string;
  en: FounderText;
  ru: FounderText;
  updatedAt?: string;
}

export interface FounderText {
  title: string;
  tagline?: string;
  intro?: string;
  sections?: FounderSection[];
  faq?: FounderQuestion[];
  note?: string;
}

export interface FounderSection {
  title?: string;
  items?: string[];
}

export interface FounderQuestion {
  question?: string;
  answer?: string;
}

/**
 * Справочник «Как устроено» — field-backlog, пункты 75 и 200: фазы конца хода в порядке сервера
 * и статьи о правилах с числами из классов правил.
 */
export interface RulesBook {
  /** `code` — имя класса фазы: им подписано событие TURN_PHASE (пункт 100). */
  phases: { code: string; order: number; title: string; text: string }[];
  articles: {
    code: string;
    title: string;
    text: string;
    /** Чем правило этой игры отличается от оригинала; пусто — ничем. */
    deviation?: string;
  }[];
}

/** Свой проект корабля в итогах — пункт 98: собранный игроком и сколько по нему построено. */
export interface OwnDesign {
  name: string;
  built: number;
}

/** История своих партий — field-backlog, пункт 18, — с личными рекордами. */
export interface GameHistory {
  games: {
    gameName: string;
    raceName?: string;
    galaxySize?: string;
    empires: number;
    outcome: 'WON' | 'LOST' | 'CONCEDED';
    victoryKind?: string;
    turn: number;
    place: number;
    peakColonies: number;
    deficitTurn?: number;
    deficitPercent?: number;
    finishedAt: string;
    /** Свои проекты кораблей партии, лучший первым — пункт 98. */
    designs?: OwnDesign[];
  }[];
  played: number;
  wins: number;
  fastestWin?: number;
  mostColonies?: number;
  hardestWin?: number;
  /** Звания за трудное с ходом к ним — пункт 41. */
  titles?: Title[];
  /** Рейтинг среди людей со списком — пункт 19. */
  rating?: Rating;
  /** Подарков за приглашение — пункты 50 и 174, 0…3. */
  companions?: number;
}

/** Доля среди похожих — field-backlog, пункт 117: только доли, без имён и чужих чисел. */
export interface PeerShare {
  games: number;
  /** У какой доли похожих колоний было меньше, %. */
  coloniesAbove: number;
  /** У какой доли похожих побед победа пришла позже, %. */
  fasterThan?: number;
  /** Сравнение среди гостевых партий — пункт 118. */
  guest: boolean;
}

/** Рейтинг игроков — field-backlog, пункт 19: только партии с людьми. */
export interface Rating {
  value: number;
  games: number;
  provisional: boolean;
  /** Сдвиг от этой партии (сцена конца). */
  delta?: number;
  /** Все с рейтингом, сильнейшие первыми (экран истории). */
  board?: { name: string; value: number; games: number; you: boolean; earlyBird?: boolean }[];
}

/** Звание за трудное — пункт 41: код, заработано ли и сколько пройдено из скольких. */
export interface Title {
  code: string;
  earned: boolean;
  progress: number;
  goal: number;
}

/** Что сервер знает об уведомлениях записи — пункт 22. */
export interface NotifySettingsState {
  pushKey: string;
  subscribed: boolean;
  notifyMail: boolean;
  hasEmail: boolean;
}

/** Сравнение с собой прошлым — field-backlog, пункт 115. */
export interface PastSelf {
  checkpoint: number;
  mine: number;
  best: number;
  /** Насколько выше (плюс) или ниже (минус) лучшей своей партии, %. */
  percent: number;
}

/** Партия, которую можно смотреть, — field-backlog, пункт 59. */
export interface SpectatableGame {
  id: string;
  name: string;
  galaxySize: string;
  turn: number;
  empires: number;
  humans: number;
  /** Ход, который зритель увидит сейчас; пусто — партия ещё молода. */
  viewTurn?: number;
  /** Спрашивающий сам за этим столом — смотреть нельзя. */
  yours: boolean;
}

/** Партия глазами зрителя: вся галактика без тумана, но на ходу `turn`, а не `gameTurn`. */
export interface SpectatorFrame {
  gameId: string;
  name: string;
  turn: number;
  gameTurn: number;
  widthParsecs: number;
  heightParsecs: number;
  empires: {
    slot: number; name: string; raceName?: string; color?: string; ai: boolean;
    colonies: number; populationK: number; might?: number; alive: boolean;
  }[];
  stars: {
    id: string; name: string; x: number; y: number; starColor?: string; special?: boolean;
    owners: number[]; monster?: string;
  }[];
  fleets: {
    slot: number; systemId?: string; originId?: string; targetId?: string;
    departureTurn?: number; arrivalTurn?: number; ships: number; armed: boolean;
  }[];
}

/** Метка наставника на карте ученика — field-backlog, пункт 184. */
export interface MapMark {
  systemId: string;
  /** COLONIZE, OUTPOST, EXPLORE, DEFEND, ATTACK, AVOID. */
  code: string;
  author: string;
  turn: number;
}

/** Империя ученика глазами наставника — пункт 184. */
export interface MentorView {
  studentPlayerId: string;
  name: string;
  color?: string;
  credits: number;
  map: GalaxyMap;
  fleets: FleetGroup[];
  marks: MapMark[];
}

/** Галактика недели — пункт 146: условия и итоги по расам. */
export interface WeeklyGalaxy {
  week: number;
  from: string;
  until: string;
  galaxySize: string;
  empires: number;
  feature?: string;
  started: number;
  /** Своя раса — строкой без кода. */
  races: { raceCode?: string; games: number; wins: number }[];
}

/** Что можно отменить до конца хода — пункт 74. */
export interface TurnUndo {
  id: string;
  /** SELL, BUY или SCRAP. */
  kind: string;
  what: string;
  where: string;
  credits?: number;
}

/**
 * Уровень сложности — field-backlog, пункты 9 и 87: что делает ИИ этого уровня, признаками его правила
 * (`AiSkill` на сервере). Прибавок у ИИ нет ни на одном уровне.
 */
export interface AiSkillInfo {
  code: string;
  /** PAYBACK — по окупаемости, LIST — по списку, SHUFFLED — вразнобой. */
  build: string;
  recommendedResearch: boolean;
  purchasesPerTurn: number;
  coalitions: boolean;
  openingBook: boolean;
}

/** Подсказка уровня следующей партии — пункт 10; пустой объект — предлагать нечего. */
export interface DifficultySuggestion {
  last?: string;
  suggested?: string;
  /** EASY_WIN или EARLY_LOSS. */
  reason?: string;
  turn?: number;
}

/**
 * Баланс рас в открытую — field-backlog, пункты 70 и 101: итоги законченных прогонов, все числа — из
 * записей прогонов. Видят администратор, модератор и основатель.
 */
export interface OpenBalance {
  runs: number;
  games: number;
  turns: number;
  lastFinishedAt?: string;
  /** С какого числа партий прогон показывается приговорами. */
  minGames: number;
  measure?: BalanceRun;
  oracle?: BalanceRun;
  previousAt?: string;
  priceChanges: { code: string; before?: number; after: number }[];
}

/** Признаки живой игры — field-backlog, пункт 45; только администратору и модератору. */
export interface LiveStats {
  /** Партий доиграно за неделю (людьми, без учебных и служебных). */
  weekGames: number;
  /** Людей сейчас в сетевых партиях. */
  onlineNow: number;
  /** Ниже скольких людей сейчас блок игрокам не показывался бы. */
  publicThreshold: number;
  /** За сколько минут действие считается «сейчас». */
  onlineMinutes: number;
  lastGames: {
    raceName?: string;
    outcome: string;
    victoryKind?: string;
    turns: number;
    empires: number;
    humans: number;
    finishedAt: string;
  }[];
}

/** Свой набор справочников основателя — field-backlog, пункт 42: какие справочники он меняет. */
export interface ModSet {
  id: string;
  name: string;
  buildings: boolean;
  ships: boolean;
  technologies: boolean;
  leaders: boolean;
  updatedAt: string;
}
