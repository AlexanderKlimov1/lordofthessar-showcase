import { beginAction, endAction } from '../state/pending';
import type {
  LiveStats,
  MutationOption,
  ModSet,
  Account,
  AccountSession,
  AccountSummary,
  ApiError,
  CreateGamePayload,
  GalaxyMap,
  GalaxySizeOption,
  GameDetails,
  GameEvent,
  GameSave,
  RefusalCount,
  InvitePreview,
  FeedbackLiked,
  GameFeedback,
  VictoryClaim,
  ProblemReport,
  FounderPage,
  Notice,
  ProblemAnswer,
  ProblemAnswerKind,
  BuildingRef,
  BuildTemplate,
  GameEnding,
  GameSummary,
  EndTurnResult,
  TurnReport,
  JoinedGame,
  MineralRichnessRef,
  Planet,
  PlanetClimateRef,
  PlanetSizeRef,
  PlayerResearch,
  DiplomacyActionCode,
  ArtifactChoice,
  ShipTemplate,
  RaceTemplate,
  Advice,
  DiplomacyProposals,
  GameSignal,
  DiplomacyRelation,
  EmpireInfo,
  Encounter,
  Espionage,
  Leaders,
  Battle,
  BattleActionPayload,
  DemoBattle,
  Fleet,
  FleetGroup,
  SaveShipDesignPayload,
  StewardDecision,
  VictoryProgress,
  ShipCatalog,
  ShipDesign,
  InvasionResult,
  Spy,
  SpyMissionCode,
  TreatyCode,
  TechTrade,
  Race,
  RaceDesign,
  RegisterResult,
  RegistrationChallenge,
  SaveRaceTraitCostsPayload,
  ResearchTree,
  RulesBook,
  GameHistory,
  AiSkillInfo,
  DifficultySuggestion,
  OpenBalance,
  PastSelf,
  EndingMoment,
  StarSystem,
  BalanceCombination,
  BalanceRun,
  Orionometer,
  BalanceRunPayload,
  Council,
  ColonistJob,
  NotifySettingsState,
  SpectatableGame,
  SpectatorFrame,
  WeeklyGalaxy,
  TurnUndo,
  MentorView,
  MapMark,
} from './types';
import { locale, t } from '../i18n';
import { recordFault } from '../state/faults';

/**
 * Базовый адрес игрового сервера.
 * В dev-режиме пусто — запросы идут через прокси Vite на localhost:8080.
 * Для подключения к серверу в сети задайте VITE_API_BASE, например http://192.168.1.10:8080.
 */
const API_BASE = import.meta.env.VITE_API_BASE ?? '';

export class GameApiError extends Error {
  readonly status: number;
  readonly details: string[];
  /**
   * Через сколько секунд повторять — заголовок `Retry-After` ответа 429.
   *
   * Приходит с защитой от подбора пароля (п. 3.1): экран входа по нему запирает форму на
   * это время, вместо того чтобы разбирать текст сообщения. Форма, которая продолжает
   * стучаться в запертый вход, только продлевает замок.
   */
  readonly retryAfterSeconds?: number;

  constructor(status: number, message: string, details: string[] = [], retryAfterSeconds?: number) {
    super(message);
    this.name = 'GameApiError';
    this.status = status;
    this.details = details;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/**
 * Пропуск игрока уходит заголовком, а не в адресе: адреса оседают в журналах сервера,
 * прокси и истории браузера. Исключение одно — подписка на события: её открывает
 * EventSource, а он заголовков не умеет.
 */
function auth(accessToken: string): HeadersInit {
  return { 'X-Access-Token': accessToken };
}

/**
 * Пропуск учётной записи — п. 3.1: им игра узнаёт, что пришедший авторизован.
 *
 * Хранится здесь, а не передаётся в каждый вызов: спрашивают его только три запроса —
 * создать партию, войти в чужую и загрузить сохранение, — но знать о нём приходилось бы
 * всем, кто их зовёт. Ставит его `session.ts` при входе и при запуске клиента.
 */
let accountToken: string | null = null;

export function setAccountToken(token: string | null): void {
  accountToken = token;
}

/** Заголовок с пропуском учётной записи; пусто — игрок ещё не вошёл. */
function account(): HeadersInit {
  return accountToken ? { 'X-Account-Token': accountToken } : {};
}

/**
 * Партия, в которой сейчас игрок, — для честного сообщения о сбое (field-backlog, пункт 156):
 * по ней ищется последнее автосохранение. Ставит её `App`, когда партия меняется.
 */
let faultGameId: string | null = null;

export function setFaultGame(gameId: string | null): void {
  faultGameId = gameId;
}

/**
 * Ход последнего автосохранения текущей партии — из списка сохранений, а не догадкой
 * (field-backlog, пункт 156). Спрашивается простым `fetch`, мимо `request`: сбой этого
 * вопроса не должен порождать второго сообщения о сбое. Нет партии, нет связи, нет
 * автосохранения — пусто, и сообщение говорит без номера хода.
 */
async function lastAutosaveTurn(): Promise<number | null> {
  if (!faultGameId) {
    return null;
  }
  try {
    const answer = await fetch(`${API_BASE}/api/saves`, { headers: { ...account() } });
    if (!answer.ok) {
      return null;
    }
    const saves = (await answer.json()) as GameSave[];
    const turns = saves.filter((save) => save.gameId === faultGameId && save.auto).map((save) => save.turn);
    return turns.length > 0 ? Math.max(...turns) : null;
  } catch {
    return null;
  }
}

/**
 * Сбой — не отказ (field-backlog, пункт 156). «Ошибка запроса: 500» читается как «игра
 * сломана, и сыгранное, может быть, пропало», поэтому сбой сервера и обрыв связи названы
 * словами и с выходом: что случилось, что с партией и что делать. Отказы по правилам
 * (4xx с ключом словаря) остаются как есть — это не сбой; 502 тоже — им сервер говорит,
 * что не ушло письмо, и объясняет это сам.
 */
const SERVER_FAULTS = new Set([500, 503, 504]);

async function faultMessage(offline: boolean): Promise<string> {
  const turn = await lastAutosaveTurn();
  if (offline) {
    return turn === null ? t('app.unreachable') : t('fault.offline', { turn });
  }
  return turn === null ? t('fault.server.noSave') : t('fault.server', { turn });
}

/**
 * Слушатель удавшихся изменяющих запросов — замер «от открытия до первого действия»
 * (field-backlog, пункт 195, `state/firstAction.ts`). Здесь только крючок: клиент API не
 * знает о воронке, а воронка сама зовёт клиент API — прямой вызов замкнул бы модули в кольцо.
 */
let actionListener: ((method: string, path: string) => void) | null = null;

export const setActionListener = (listener: (method: string, path: string) => void): void => {
  actionListener = listener;
};

/**
 * Когда последний раз удался изменяющий запрос (мс) — field-backlog, пункт 127: любое такое
 * действие отмечает игрока присутствующим, и предупреждение «через минуту вашей империей
 * займётся ИИ» после него гаснет, не дожидаясь нового срока с сервера.
 */
let lastMutation = 0;

export const lastMutationAt = (): number => lastMutation;

/**
 * Каждое изменяющее действие — со знаком «идёт» (field-backlog, пункт 73): счётчик в одном
 * месте, а не в каждом экране. Конец хода и отметка присутствия не в счёт: у первого своё «идёт»
 * на кнопке хода, вторая уходит сама, без нажатия.
 */
/**
 * Одинаковые чтения — одним запросом (12.10.2026, замер конца хода: после хода клиент слал
 * 27 запросов, и прогресс к победе уходил четыре раза, советник — три, «отмена» и бои — по два).
 * У браузера к одному серверу шесть соединений, и повторы стояли в очереди перед нужным:
 * ответ в 30 мс растягивался до 300. Поэтому GET с тем же адресом и пропуском, пока первый ещё
 * в пути или ответил меньше {@link READ_REUSE_MS} назад, получает тот же ответ.
 *
 * Свежесть не страдает: любое изменяющее действие и любое событие партии (`forgetReads`)
 * сбрасывает память целиком — устаревшим ответ может быть только на те доли секунды, когда
 * сервер и так ничего не менял. Сбой не запоминается: повтор спросит сервер заново.
 */
const READ_REUSE_MS = 500;
const reads = new Map<string, { at: number; promise: Promise<unknown> }>();

export const forgetReads = (): void => {
  reads.clear();
};

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = init?.method ?? 'GET';
  if (method === 'GET' && init?.body === undefined) {
    const key = `${locale()} ${path} ${JSON.stringify(init?.headers ?? {})}`;
    const now = Date.now();
    const known = reads.get(key);
    if (known && now - known.at < READ_REUSE_MS) {
      return known.promise as Promise<T>;
    }
    const promise = send<T>(path, init);
    reads.set(key, { at: now, promise });
    promise.then(
      () => {
        const entry = reads.get(key);
        if (entry && entry.promise === promise) {
          // Свежесть считается от ответа, а не от вопроса: долгий запрос не должен отдать
          // ответ, который устарел, ещё пока шёл.
          entry.at = Date.now();
        }
      },
      () => {
        if (reads.get(key)?.promise === promise) {
          reads.delete(key);
        }
      },
    );
    return promise;
  }
  forgetReads();
  const counted = method !== 'GET' && !path.endsWith('/turn/end') && !path.endsWith('/presence');
  if (!counted) {
    return send<T>(path, init);
  }
  beginAction();
  try {
    return await send<T>(path, init);
  } finally {
    endAction();
  }
}

async function send<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        // Язык экрана — серверу: ошибки, письма и отчёты хода приходят на нём же.
        'Accept-Language': locale(),
        ...(init?.headers ?? {}),
      },
    });
  } catch {
    recordFault('request', `${init?.method ?? 'GET'} ${path} — нет связи`);
    throw new GameApiError(0, await faultMessage(true));
  }

  const method = init?.method ?? 'GET';
  if (method !== 'GET') {
    // Чтения, начатые, пока шло изменение, могли застать прежнее состояние.
    forgetReads();
  }
  if (response.ok && method !== 'GET') {
    lastMutation = Date.now();
  }
  if (response.ok && method !== 'GET' && actionListener) {
    try {
      actionListener(method, path);
    } catch {
      // замер не должен мешать игре ни на миг
    }
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // Ответ не JSON — так отвечает прокси перед упавшим сервером; это сбой, а не ответ игры.
    body = null;
  }

  if (!response.ok) {
    const error = body as ApiError | null;
    const retryAfter = Number(response.headers.get('Retry-After'));
    recordFault('request', `${init?.method ?? 'GET'} ${path} → ${response.status} ${error?.message ?? ''}`);
    throw new GameApiError(
      response.status,
      SERVER_FAULTS.has(response.status)
        ? await faultMessage(false)
        : error?.message ?? t('error.request', { status: response.status }),
      error?.details ?? [],
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
    );
  }

  return body as T;
}

export const gameApi = {
  /** Проверка доступности сервера при подключении клиента. */
  health: () => request<{ status: string }>('/actuator/health'),

  /*
    Вход в игру — п. 3.1. Пропуск учётной записи эти четыре запроса не шлют: три первых
    его и выдают, а четвёртый им же и проверяется — заголовок ставит `account()`.
  */

  /**
   * Регистрация: ссылка подтверждения уходит на почту — п. 3.1.
   *
   * Логина в заявке нет: логином служит сама почта, а имя идёт только на показ в
   * интерфейсе игры.
   */
  register: (
    email: string,
    name: string,
    password: string,
    challengeId?: string,
    challengeAnswer?: string,
  ) =>
    request<RegisterResult>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, name, password, challengeId, challengeAnswer }),
    }),

  /**
   * Задачка перед регистрацией — п. 3.1. Пусто значит, что она выключена на сервере:
   * форма тогда о ней и не спрашивает.
   */
  registrationChallenge: () =>
    request<RegistrationChallenge | null>('/api/auth/challenge'),

  /**
   * Балансовые прогоны — этап 2 плана: список свежими сверху.
   *
   * Хозяйство сервера, а не своя партия, поэтому пропуск идёт учётной записи
   * (`X-Account-Token`), и сервер спрашивает роль.
   */
  /**
   * Орионометр: запись того, как человек играет в оригинал.
   *
   * Хозяйство машины, а не своя партия, поэтому пропуск идёт учётной записи, а сервер
   * спрашивает роль.
   */
  orionometer: () =>
    request<Orionometer>('/api/orionometer', { headers: account() }),

  /** Начать запись: игра должна быть уже открыта. */
  startOrionometer: (minutes?: number) =>
    request<Orionometer>(
      `/api/orionometer/start${minutes ? `?minutes=${minutes}` : ''}`,
      { method: 'POST', headers: account() },
    ),

  /** Остановить запись: протокол дописывается и остаётся на диске. */
  stopOrionometer: () =>
    request<Orionometer>('/api/orionometer/stop', { method: 'POST', headers: account() }),

  /** Пустить нейросеть играть в оригинал через Орионометр. */
  orionometerBrain: () =>
    request<Orionometer>('/api/orionometer/brain', { method: 'POST', headers: account() }),

  listBalanceRuns: () =>
    request<BalanceRun[]>('/api/balance/runs', { headers: account() }),

  /** Баланс рас в открытую — пункты 70 и 101: администратору, модератору и основателю. */
  openBalance: () => request<OpenBalance>('/api/balance/open', { headers: account() }),

  /** Один прогон целиком — со снимком цен и приговорами. */
  balanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}`, { headers: account() }),

  /**
   * Заказать прогон. Отвечает сразу: играет его сервер в фоне, и пульт показывает,
   * сколько партий уже сыграно.
   */
  startBalanceRun: (payload: BalanceRunPayload) =>
    request<BalanceRun>('/api/balance/runs', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(payload),
    }),

  /**
   * Пересудить прогон нынешними правилами, не переигрывая его.
   *
   * Замеры у прогона свои, цены — из его же снимка; меняются только правила чтения. Без
   * этого всякая правка правил оценки требовала бы играть двести партий заново.
   */
  reassessBalanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}/reassess`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Поставить прогон на паузу.
   *
   * Пауза мягкая: новых партий прогон не начинает, а начатые доигрывает — бросать партию
   * посередине нельзя, её замер пропал бы. Поэтому между нажатием и остановкой проходит
   * до нескольких минут, и число сыгранных всё это время растёт.
   */
  pauseBalanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}/pause`, {
      method: 'POST',
      headers: account(),
    }),

  /** Продолжить приостановленный прогон. */
  resumeBalanceRun: (runId: string) =>
    request<BalanceRun>(`/api/balance/runs/${runId}/resume`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Связки, которые стоит проверить, и что о них известно.
   *
   * Связок из полусотни сторон больше двадцати тысяч, и перебирать их незачем: смысл имеют
   * считанные — те, о которых есть догадка. Здесь они копятся вместе с ответами прогонов.
   */
  listBalanceCombinations: () =>
    request<BalanceCombination[]>('/api/balance/combinations', { headers: account() }),

  /** Запомнить связку: что проверяем и зачем. */
  rememberBalanceCombination: (traits: string[], note?: string) =>
    request<BalanceCombination>('/api/balance/combinations', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ traits, note }),
    }),

  /** Забыть связку — догадка не оправдалась. */
  forgetBalanceCombination: (id: string) =>
    request<void>(`/api/balance/combinations/${id}`, {
      method: 'DELETE',
      headers: account(),
    }),

  /** Учётные записи — п. 3.1: список видит только администратор. */
  listAccounts: () =>
    request<AccountSummary[]>('/api/auth/accounts', { headers: account() }),

  /**
   * Подтверждение чужой записи администратором — п. 3.1.
   *
   * Последний ключ от застрявшей регистрации: письмо могло не дойти вовсе, и тогда игрок
   * остаётся с занятой почтой и закрытым входом.
   */
  confirmAccountByAdmin: (accountId: string) =>
    request<AccountSummary>(`/api/auth/accounts/${accountId}/confirm`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Удаление учётной записи администратором — п. 3.1.
   *
   * Брошенные заявки и опечатки в адресе иначе висят в очереди вечно, а занятая почта не
   * освобождается сама. Сыгранное при этом остаётся: запись ни на что в партии не
   * ссылается.
   */
  /** Назначить или снять модератора — администратором (08.10.2026). */
  changeAccountRole: (accountId: string, role: 'PLAYER' | 'MODERATOR') =>
    request<AccountSummary>(`/api/auth/accounts/${accountId}/role`, {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ role }),
    }),

  deleteAccountByAdmin: (accountId: string) =>
    request<void>(`/api/auth/accounts/${accountId}`, {
      method: 'DELETE',
      headers: account(),
    }),

  /**
   * Выслать письмо подтверждения заново — п. 3.1.
   *
   * Спрашивает те же логин и пароль, что и вход: сервер иначе слал бы письма на чужие
   * адреса по чужой просьбе. Старая ссылка после этого не годится.
   */
  resendConfirmation: (login: string, password: string) =>
    request<RegisterResult>('/api/auth/resend', {
      method: 'POST',
      body: JSON.stringify({ login, password }),
    }),

  /** Подтверждение почты по ссылке из письма: в ответ приходит готовый пропуск — п. 3.1. */
  /** Попросить ссылку сброса пароля — backlog-promo, пункт 45; ответ один, есть запись или нет. */
  requestPasswordReset: (email: string) =>
    request<{ notice: string }>('/api/auth/reset/request', { method: 'POST', body: JSON.stringify({ email }) }),

  /** Новый пароль по ссылке из письма — пункт 45; отвечает новым пропуском. */
  resetPassword: (token: string, newPassword: string) =>
    request<AccountSession>('/api/auth/reset', { method: 'POST', body: JSON.stringify({ token, newPassword }) }),

  /** Сменить пароль из настроек — пункт 45. */
  changePassword: (password: string, newPassword: string) =>
    request<void>('/api/auth/password', { method: 'POST', headers: account(), body: JSON.stringify({ password, newPassword }) }),

  /** Сменить почту — пункт 45: новая станет логином после ссылки из письма на неё. */
  changeEmail: (password: string, email: string) =>
    request<RegisterResult>('/api/auth/email', { method: 'POST', headers: account(), body: JSON.stringify({ password, email }) }),

  /** Удалить свою запись — пункт 45. */
  deleteMyAccount: (password: string) =>
    request<void>('/api/auth/delete', { method: 'POST', headers: account(), body: JSON.stringify({ password }) }),

  confirmAccount: (token: string) =>
    request<AccountSession>(`/api/auth/confirm?token=${encodeURIComponent(token)}`, {
      method: 'POST',
    }),

  /**
   * Гостевой вход — backlog-promo, пункт 1: запись без почты и пароля, одним нажатием.
   * В ответ — обычный пропуск; партию гостю назначает сервер (`GuestGameRules`).
   */
  guestAccount: () => request<AccountSession>('/api/auth/guest', { method: 'POST' }),

  /**
   * Гость регистрируется, не теряя партий, — field-backlog, пункт 1: та же запись получает
   * почту и пароль, а игроком становится по ссылке из письма. До ссылки гость играет как
   * играл: его пропуск не гаснет.
   */
  upgradeGuest: (
    email: string,
    name: string,
    password: string,
    challengeId?: string,
    challengeAnswer?: string,
  ) =>
    request<RegisterResult>('/api/auth/guest/register', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ email, name, password, challengeId, challengeAnswer }),
    }),

  /**
   * Шаг воронки новичка — field-backlog, пункт 2. Пропуск записи уходит, если он есть:
   * первый шаг случается до всякого входа.
   */
  /**
   * Сообщение о проблеме — field-backlog, пункт 157: строка игрока и то, что клиент собрал
   * сам (партия, ход, экран, версия, последние сбои).
   */
  reportProblem: (body: {
    text: string;
    gameId?: string;
    turn?: number;
    clientVersion?: string;
    screen?: string;
    context?: string;
  }) => request<void>('/api/problems', { method: 'POST', headers: account(), body: JSON.stringify(body) }),

  /** Объявления об изменениях, которых запись ещё не видела, — field-backlog, пункт 161. */
  unseenNotices: () => request<Notice[]>('/api/notices', { headers: account() }),

  /** Запись прочитала объявления: больше их не показывать ни здесь, ни на другой машине. */
  markNoticesSeen: () => request<void>('/api/notices/seen', { method: 'POST', headers: account() }),

  /** Номер версии игры — field-backlog, пункт 159: в главном меню и в сообщении о проблеме. */
  release: () => request<{ version: string }>('/api/notices/release'),

  /** Весь список «что нового», свежее сверху, — по ссылке с номером версии. */
  allNotices: () => request<Notice[]>('/api/notices/all'),

  /** Ответы хозяина на свои сообщения о проблемах, которых вошедший ещё не видел, — пункт 158. */
  problemAnswers: () => request<ProblemAnswer[]>('/api/problems/answers', { headers: account() }),

  /** Ответы прочитаны: одна строка, один раз. */
  markProblemAnswersSeen: () =>
    request<void>('/api/problems/answers/seen', { method: 'POST', headers: account() }),

  /** Ответить написавшему — только администратору: исправлено в версии или «не будем» с причиной. */
  answerProblem: (reportId: string, body: { kind: ProblemAnswerKind; version?: string; text?: string }) =>
    request<ProblemReport>(`/api/problems/${reportId}/answer`, {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(body),
    }),

  /** Сообщения о проблемах — только администратору; свежие сверху. */
  listProblems: () => request<ProblemReport[]>('/api/problems', { headers: account() }),

  /** Спросить ли отзыв об этой партии — field-backlog, пункт 46: не чаще раза в три партии. */
  feedbackAsk: (gameId: string) =>
    request<{ ask: boolean }>(`/api/feedback/ask?gameId=${gameId}`, { headers: account() }),

  /** Отзыв после партии (пункты 46 и 187); без оценки — отказ, он тоже запоминается. */
  sendFeedback: (body: { gameId: string; rating?: number; liked?: FeedbackLiked; comment?: string }) =>
    request<{ saved: boolean }>('/api/feedback', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(body),
    }),

  /** Отзывы после партий — только администратору. */
  listFeedback: () => request<GameFeedback[]>('/api/feedback', { headers: account() }),

  /** Частые отказы живым игрокам (field-backlog, пункт 77) — только администратору. */
  listRefusals: () => request<RefusalCount[]>('/api/refusals', { headers: account() }),

  /** Сколько сообщений ждут разбора — число у ссылки в меню хозяина. */
  pendingProblems: () => request<number>('/api/problems/pending', { headers: account() }),

  resolveProblem: (reportId: string, resolved: boolean) =>
    request<ProblemReport>(`/api/problems/${reportId}/resolve?resolved=${resolved}`, {
      method: 'POST',
      headers: account(),
    }),

  /**
   * Страница пакета основателя. Пропуск необязателен: без входа её тоже читают, а с пропуском
   * администратора приходит и неопубликованный черновик. Пусто — страницы для читателя нет
   * (не опубликована): это не сбой, и ссылки на неё в меню просто не будет.
   */
  founderPage: () => request<FounderPage | undefined>('/api/founders', { headers: account() }),

  /** Сохранить страницу основателя целиком — только администратору. */
  saveFounderPage: (page: FounderPage) =>
    request<FounderPage>('/api/founders', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify(page),
    }),

  funnelStep: (body: { visitor: string; step: string; gameId?: string; turn?: number; detail?: string }) =>
    // keepalive: шаг, отправленный при закрытии вкладки, обязан дойти — он и есть место ухода.
    request<void>('/api/funnel', { method: 'POST', headers: account(), body: JSON.stringify(body), keepalive: true }),

  /** Вход по логину или почте — п. 3.1. */
  loginAccount: (login: string, password: string) =>
    request<AccountSession>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ login, password }),
    }),

  /** Выход: пропуск перестаёт действовать сразу, а не по сроку. */
  logoutAccount: () =>
    request<void>('/api/auth/logout', { method: 'POST', headers: account() }),

  /** Кто вошёл: этим клиент проверяет при запуске, жив ли сохранённый пропуск — п. 3.1. */
  currentAccount: () => request<Account>('/api/auth/me', { headers: account() }),

  /**
   * Запомнить язык в учётной записи — п. 3.5.
   *
   * Уходит следом за переключением языка, а не вместо него: экран переключается сразу, а
   * запись — когда ответит сервер. Отказ здесь не беда (в игре без входа записи нет вовсе),
   * поэтому зовущий его гасит.
   */
  saveLocale: (value: string) =>
    request<Account>('/api/auth/locale', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ locale: value }),
    }),

  /**
   * Запомнить размер текста в учётной записи — п. 11.1.
   *
   * Всё то же, что и у языка: интерфейс перерисовывается сразу, запись догоняет, а отказ
   * гасит зовущий — без входа записи нет вовсе, и настройка остаётся в браузере.
   */
  saveTextScale: (value: number) =>
    request<Account>('/api/auth/text-scale', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ textScale: value }),
    }),

  /** Запомнить громкость музыки и эффектов — обе ступени разом, как и стоят переключатели. */
  saveSound: (musicVolume: number, sfxVolume: number) =>
    request<Account>('/api/auth/sound', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ musicVolume, sfxVolume }),
    }),

  /**
   * Запомнить раскладку горячих клавиш — п. 11.1.
   *
   * Уходит ЦЕЛИКОМ: раскладку правят на своём экране и сохраняют разом, а посылать по
   * одной паре значило бы держать на сервере порядок правок. Пустая раскладка значит «как
   * было»: сервер очищает колонку, и клиент берёт свою раскладку по умолчанию.
   */
  saveHotkeys: (value: Record<string, string>) =>
    request<Account>('/api/auth/hotkeys', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ hotkeys: value }),
    }),

  /**
   * Запомнить подсказки — field-backlog, пункты 30 и 130: нужны ли подсказки новичку и какие
   * отклонены насовсем. Уходят целиком; пустой список — «вернуть все».
   */
  saveHints: (noviceHints: boolean, dismissedHints: string[]) =>
    request<Account>('/api/auth/hints', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ noviceHints, dismissedHints }),
    }),

  /**
   * Окна, которые не открываются сами, — field-backlog, пункт 164. Уходят целиком; пустой
   * список — «открывать все».
   */
  saveQuietWindows: (quietWindows: string[]) =>
    request<Account>('/api/auth/windows', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ quietWindows }),
    }),

  /** Знаки знамён у хозяев на карте — field-backlog, пункт 202. */
  saveGlyphs: (bannerGlyphs: boolean) =>
    request<Account>('/api/auth/glyphs', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ bannerGlyphs }),
    }),

  /** Меньше движения — field-backlog, пункт 170: `null` — как в системе. */
  saveMotion: (reduceMotion: boolean | undefined) =>
    request<Account>('/api/auth/motion', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ reduceMotion: reduceMotion ?? null }),
    }),

  /** Список игр, видимых всем, кто видит IP сервера — п. 3.1. */
  listGames: (openOnly = true) => request<GameSummary[]>(`/api/games?openOnly=${openOnly}`),

  createGame: (payload: CreateGamePayload) =>
    request<JoinedGame>('/api/games', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(payload),
    }),

  getGame: (gameId: string) => request<GameDetails>(`/api/games/${gameId}`),

  /** Присоединение к чужой игре — п. 3.2. */
  joinGame: (
    gameId: string,
    playerName: string,
    homeStarName?: string,
    raceName?: string,
    raceTraits?: string[],
    raceCode?: string,
    // Знамя — цвет империи, п. 3.2; пусто — знамя расы или первое свободное.
    banner?: string,
    // Своя раса из кабинета — пункт 99.
    raceTemplateId?: string,
  ) =>
    request<JoinedGame>(`/api/games/${gameId}/join`, {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ playerName, homeStarName, raceName, raceTraits, raceCode, banner, raceTemplateId }),
    }),

  /** Свои в лобби — пункт 148: открытая партия → сколько партий сыграно вместе с её участниками. */
  playedTogether: () => request<Record<string, number>>('/api/games/together', { headers: account() }),

  /**
   * «Сыграть с людьми» — field-backlog, пункт 65: место за ближайшим столом подбора или новый стол;
   * он стартует сам, когда за ним двое людей.
   */
  quickMatch: (playerName: string) =>
    request<JoinedGame>('/api/games/quick-match', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ playerName }),
    }),

  /** Код приглашения своей партии — field-backlog, пункт 15; заводится при первой просьбе. */
  inviteCode: (gameId: string, accessToken: string) =>
    request<{ code: string }>(`/api/games/${gameId}/invite`, { headers: auth(accessToken) }),

  /** Что ждёт пришедшего по приглашению — без входа тоже. */
  invitePreview: (code: string) =>
    request<InvitePreview>(`/api/invites/${encodeURIComponent(code)}`),

  /** Войти по приглашению — гостю тоже (пункт 61). */
  joinInvite: (code: string, playerName: string, empirePlayerId?: string) =>
    request<JoinedGame>(`/api/invites/${encodeURIComponent(code)}/join`, {
      method: 'POST',
      headers: account(),
      // Какую империю ИИ взять — пункт 66; пусто — ту, что предлагает приглашение.
      body: JSON.stringify({ playerName, empirePlayerId }),
    }),

  /** Отпуск в сетевой партии — field-backlog, пункт 177; ноль дней — вернуться сейчас. */
  vacation: (gameId: string, accessToken: string, days: number) =>
    request<GameDetails>(`/api/games/${gameId}/vacation`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, days }),
    }),

  /** Старт игры: свободные слоты добираются ИИ-игроками до восьми — п. 3.2. */
  startGame: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/start`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Конец хода игрока — п. 11.1: игрок объявляет, что закончил, и ждёт остальных.
   * Галактика считается, когда закончили все люди партии, поэтому ответ говорит, посчитан
   * ли ход (`advanced`) и кого ещё ждут. Тем, кто закончил раньше, о пересчёте расскажет
   * подписка на события.
   */
  endTurn: (gameId: string, accessToken: string) =>
    request<EndTurnResult>(`/api/games/${gameId}/turn/end`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Что изменилось за последний посчитанный ход — п. 11.1. Нужен, когда подписки не
   * было: после перезагрузки страницы или обрыва связи.
   */
  getTurnReport: (gameId: string, accessToken: string) =>
    request<TurnReport | null>(`/api/games/${gameId}/turn/report`, { headers: auth(accessToken) }),

  /**
   * Подписка на события партии — п. 11.1: сосед закончил ход, галактика пересчиталась.
   * Возвращает функцию отписки. Пропуск идёт параметром: EventSource заголовков не умеет.
   */
  subscribe: (gameId: string, accessToken: string, onEvent: (event: GameEvent) => void) => {
    const source = new EventSource(
      `${API_BASE}/api/games/${gameId}/events?accessToken=${accessToken}`,
    );
    const handler = (message: MessageEvent<string>) => {
      // Событие партии значит, что на сервере что-то сменилось без нашего запроса.
      forgetReads();
      try {
        onEvent(JSON.parse(message.data) as GameEvent);
      } catch {
        // Битое сообщение — не повод ронять подписку: следующее придёт целым.
      }
    };
    for (const type of ['SUBSCRIBED', 'PLAYER_READY', 'TURN_ADVANCED', 'GAME_STARTED', 'PLAYER_JOINED', 'TURN_PHASE']) {
      source.addEventListener(type, handler as EventListener);
    }
    return () => source.close();
  },

  /**
   * Перераспределение жителей колонии — п. 4.1. Присылаются все три занятия сразу:
   * их сумма должна сойтись с населением планеты. В ответ приходит планета с новой
   * выработкой, поэтому перезапрашивать карту галактики не нужно.
   */
  setPopulation: (
    gameId: string,
    planetId: string,
    accessToken: string,
    farmers: number,
    workers: number,
    scientists: number,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/population`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, farmers, workers, scientists }),
    }),

  /**
   * Перевозка жителей в другую свою колонию — п. 4.1.1.
   *
   * Грузовой флот резервируется из расчёта один грузовик на единицу населения и
   * освобождается, когда рейс дойдёт — в конце следующего хода. В ответ приходит
   * колония-отправитель: жители сходят с неё сразу.
   */
  transferPopulation: (
    gameId: string,
    planetId: string,
    accessToken: string,
    targetPlanetId: string,
    population: number,
    // С какого дела взяты жители и на какое встают по прибытии — п. 4.1.1. Без них
    // колонии расставляют жителей сами (так везёт окно перевозки, где дела не выбирают).
    job?: ColonistJob,
    targetJob?: ColonistJob,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/transfer`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId, population, job, targetJob }),
    }),

  /**
   * Смена стройки колонии — п. 10: код здания либо особый проект HOUSING или TRADE_GOODS.
   * В ответ приходит планета с новой стройкой, доходом и приростом.
   */
  setProject: (gameId: string, planetId: string, accessToken: string, projectCode: string,
               keepCurrent?: boolean) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/project`, {
      method: 'POST',
      headers: auth(accessToken),
      // keepCurrent — прежняя стройка не пропадает, а сдвигается первой в очередь (п. 10).
      body: JSON.stringify({ accessToken, projectCode, keepCurrent }),
    }),

  /**
   * Выкупить стройку колонии за кредиты — п. 10: казна платит за недостающие единицы
   * производства, и вещь достраивается тем же ходом. Не хватило денег — 409.
   */
  buyProject: (gameId: string, planetId: string, accessToken: string) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/buy`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /**
   * Продать постройку колонии — п. 10: казна получает половину цены здания, содержание
   * платить больше не за что. За ход колония продаёт одну постройку — вторая получит 409.
   */
  sellBuilding: (gameId: string, planetId: string, accessToken: string, buildingCode: string) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/sell`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, buildingCode }),
    }),

  /**
   * Добавить проект в очередь стройки — п. 10. Колония без стройки берётся за него сразу:
   * очередь забирается только после достроенного, и первый проект ждал бы вечно.
   */
  enqueueProject: (
    gameId: string,
    planetId: string,
    accessToken: string,
    projectCode: string,
    /**
     * Поставить в ГОЛОВУ очереди, а не в конец, — п. 10: так проект кладёт щелчок по
     * списку стройки. Правило одно на всех, и живёт оно на сервере
     * (`ColonyService.enqueue`, поле `top`): им же список колоний закладывает проект
     * многим колониям разом.
     */
    top?: boolean,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/queue`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, projectCode, top: top === true ? true : undefined }),
    }),

  /**
   * Поставить один проект сразу многим колониям — п. 10, список колоний.
   *
   * Проект встаёт в голову очереди (`top`), сдвигая её вниз: приказ отсюда касается всей
   * империи, и ждать за тем, что каждая колония успела набрать, он не может. Проект
   * должен быть доступен каждой колонии набора — иначе 409 с именем той, которой он не по
   * силам, и очереди остальных не меняются. В ответ приходят все изменённые колонии.
   */
  enqueueProjects: (
    gameId: string,
    planetIds: string[],
    accessToken: string,
    projectCode: string,
  ) =>
    request<Planet[]>(`/api/games/${gameId}/colonies/queue`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, planetIds, projectCode, top: true }),
    }),

  /** Убрать проект из очереди стройки — п. 10. Вложенное производство остаётся колонии. */
  removeFromQueue: (gameId: string, planetId: string, accessToken: string, index: number) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/queue/${index}`, {
      method: 'DELETE',
      headers: auth(accessToken),
    }),

  /** Переставить проект в очереди стройки — п. 10: `toIndex` считается от нуля. */
  moveInQueue: (
    gameId: string,
    planetId: string,
    accessToken: string,
    index: number,
    toIndex: number,
  ) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/queue/${index}/move`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, toIndex }),
    }),

  /**
   * Заселение планеты готовой колониальной базой — п. 4.1. `planetId` — колония, которая
   * построила базу, `targetPlanetId` — свободная планета той же системы. В ответ приходит
   * система целиком: планет в ней изменилось две.
   */
  colonize: (gameId: string, planetId: string, accessToken: string, targetPlanetId: string) =>
    request<StarSystem>(`/api/games/${gameId}/planets/${planetId}/colonize`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /**
   * Высадка десанта на чужую колонию той же системы — п. 12. `planetId` — своя колония,
   * откуда идёт десант. В ответ приходит исход боя и система целиком.
   */
  invade: (
    gameId: string,
    planetId: string,
    accessToken: string,
    targetPlanetId: string,
    troops: number,
  ) =>
    request<InvasionResult>(`/api/games/${gameId}/planets/${planetId}/invade`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId, troops }),
    }),

  /** Знакомые империи и отношения с ними — п. 15. */
  getRelations: (gameId: string, accessToken: string) =>
    request<DiplomacyRelation[]>(`/api/games/${gameId}/diplomacy`, { headers: auth(accessToken) }),

  /**
   * Дипломатическое действие — п. 15: мир, война, договор, его разрыв или требование
   * дани. Договор нужен действиям PROPOSE_TREATY и BREAK_TREATY.
   */
  diplomacy: (
    gameId: string,
    accessToken: string,
    targetPlayerId: string,
    action: DiplomacyActionCode,
    treaty?: TreatyCode,
    /** Обмен и подарки — п. 15: что отдаём, что просим, сколько дарим. */
    deal?: { offeredTech?: string; requestedTech?: string; credits?: number },
  ) =>
    request<DiplomacyRelation>(`/api/games/${gameId}/diplomacy`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlayerId, action, treaty, ...deal }),
    }),

  /** Наследие ушедших, ждущее выбора, — backlog-promo, пункт 34. */
  getArtifactChoices: (gameId: string, accessToken: string) =>
    request<ArtifactChoice[]>(`/api/games/${gameId}/artifacts`, { headers: auth(accessToken) }),

  /** Взять выбранные технологии наследия — пункт 34. */
  pickArtifacts: (gameId: string, accessToken: string, choiceId: string, codes: string[]) =>
    request<unknown>(`/api/games/${gameId}/artifacts/${choiceId}`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, codes }),
    }),

  /** Последние короткие знаки — field-backlog, пункт 183: вам, всем и ваши. */
  getSignals: (gameId: string, accessToken: string) =>
    request<GameSignal[]>(`/api/games/${gameId}/signals`, { headers: auth(accessToken) }),

  /** Отправить готовую реплику одному человеку или всем — пункт 183. */
  sendSignal: (gameId: string, accessToken: string, code: string, toPlayerId?: string) =>
    request<GameSignal[]>(`/api/games/${gameId}/signals`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, code, toPlayerId }),
    }),

  /** Сыграть снова тем же составом — пункт 149: завести новую партию или войти в заведённую. */
  rematch: (gameId: string) =>
    request<JoinedGame>(`/api/games/${gameId}/rematch`, { method: 'POST', headers: account() }),

  /** Предложения, ждущие итога, — field-backlog, пункт 182. */
  getProposals: (gameId: string, accessToken: string) =>
    request<DiplomacyProposals>(`/api/games/${gameId}/diplomacy/proposals`, { headers: auth(accessToken) }),

  /** Принять или отклонить предложение — пункт 182; исполнится в конце хода. */
  answerProposal: (gameId: string, accessToken: string, proposalId: string, accept: boolean) =>
    request<DiplomacyProposals>(`/api/games/${gameId}/diplomacy/proposals/${proposalId}`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, accept }),
    }),

  /** Что можно обменять с этой империей — п. 15: чем поделиться и что попросить. */
  getTradeableTechnologies: (gameId: string, accessToken: string, otherPlayerId: string) =>
    request<TechTrade>(
      `/api/games/${gameId}/diplomacy/${otherPlayerId}/technologies`,
      { headers: auth(accessToken) },
    ),

  /**
   * Задание шпиону — п. 13: отправить к сопернику воровать технологии или устраивать
   * саботаж либо отозвать домой, где агент пополняет запас очков разведки.
   */
  assignSpy: (
    gameId: string,
    accessToken: string,
    spyId: string,
    mission: SpyMissionCode,
    targetPlayerId?: string,
  ) =>
    request<Spy>(`/api/games/${gameId}/spies`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, spyId, mission, targetPlayerId }),
    }),

  /** Разведка империи — п. 13: накопленные очки и приход за ход. */
  getEspionage: (gameId: string, accessToken: string) =>
    request<Espionage>(`/api/games/${gameId}/espionage`, { headers: auth(accessToken) }),

  /**
   * Офицерский резерв — п. 6: кто предлагает службу, кто служит, сколько мест занято.
   *
   * Спрашивается при открытии экрана: предложения приходят и уходят каждый ход, и держать
   * их в хранилище значило бы показывать вчерашний резерв.
   */
  getLeaders: (gameId: string, accessToken: string) =>
    request<Leaders>(`/api/games/${gameId}/leaders`, { headers: auth(accessToken) }),

  /** Нанять лидера — п. 6: плата разовая, технологии лидера достаются сразу. */
  hireLeader: (gameId: string, accessToken: string, leaderId: string) =>
    request<Leaders>(`/api/games/${gameId}/leaders/${leaderId}/hire`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /** Отказать предложившему или уволить служащего — п. 6. */
  dismissLeader: (gameId: string, accessToken: string, leaderId: string) =>
    request<Leaders>(`/api/games/${gameId}/leaders/${leaderId}/dismiss`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /**
   * Назначить лидера — п. 6: колониального в систему, корабельного во флот.
   * Пустая цель возвращает его в резерв.
   */
  assignLeader: (gameId: string, accessToken: string, leaderId: string, targetId: string | null) =>
    request<Leaders>(`/api/games/${gameId}/leaders/${leaderId}/assign`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ targetId }),
    }),

  /**
   * Окно «Инфо» — п. 11.1: летопись империй для графика и описание знакомых рас.
   * Запрашивается при открытии экрана: летопись растёт каждый ход, и держать её в
   * хранилище значило бы показывать вчерашний график.
   */
  getInfo: (gameId: string, accessToken: string) =>
    request<EmpireInfo>(`/api/games/${gameId}/info`, { headers: auth(accessToken) }),

  /**
   * Последние выборы Высшего совета — п. 3; пусто, если совет ещё не собирался.
   *
   * Голосование случается внутри посчитанного хода, поэтому сцена спрашивает его
   * отдельно, а не берёт из карты: в карте его нет и быть не может.
   */
  getCouncil: (gameId: string, accessToken: string) =>
    request<Council | null>(`/api/games/${gameId}/council`, { headers: auth(accessToken) }),

  /**
   * Не подчиниться избранному правителю галактики — п. 3.
   *
   * Партия после отказа продолжается, поэтому вызвавший обязан перечитать её целиком:
   * победы больше нет, а у отказника — война со всеми, кто голосовал за избранного.
   */
  refuseCouncil: (gameId: string, accessToken: string) =>
    request<Council>(`/api/games/${gameId}/council/refuse`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /**
   * Голос игрока в открытом совете — п. 3: за кандидата или {@code null} — воздержаться.
   * Итог подводит конец хода, до тех пор голос можно переменить.
   */
  voteCouncil: (gameId: string, accessToken: string, choicePlayerId: string | null) =>
    request<Council>(`/api/games/${gameId}/council/vote`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ choicePlayerId }),
    }),

  /** Флот империи — п. 8: проекты кораблей с учётом расы. */
  getFleet: (gameId: string, accessToken: string) =>
    request<Fleet>(`/api/games/${gameId}/ships`, { headers: auth(accessToken) }),

  /** Флоты игрока по системам — п. 8. */
  getFleets: (gameId: string, accessToken: string) =>
    request<FleetGroup[]>(`/api/games/${gameId}/fleets`, { headers: auth(accessToken) }),

  /**
   * Перелёт флота в другую известную систему — п. 8. Времени в пути нет: флот
   * оказывается на месте сразу.
   */
  /** Бои партии, которые ещё идут: по ним открывается сцена — п. 8. */
  getBattles: (gameId: string, accessToken: string) =>
    request<Battle[]>(`/api/games/${gameId}/battles`, { headers: auth(accessToken) }),

  /** Состояние поля боя: корабли, очередь хода и чей ход сейчас — п. 8. */
  getBattle: (gameId: string, battleId: string, accessToken: string) =>
    request<Battle>(`/api/games/${gameId}/battles/${battleId}`, { headers: auth(accessToken) }),

  /**
   * Ход корабля: перелёт, залп, пропуск или отступление — п. 8. В ответе приходит поле
   * после хода и события, случившиеся за это время: свой залп и ходы кораблей ИИ.
   */
  battleAction: (gameId: string, battleId: string, accessToken: string, payload: BattleActionPayload) =>
    request<Battle>(`/api/games/${gameId}/battles/${battleId}/action`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify(payload),
    }),

  /**
   * Ходы ИИ и чудищ, стоящих в очереди раньше игрока, — п. 8: бой может начаться с их хода
   * (чудище быстрее фрегата). Если очередь у человека, сервер не делает ничего.
   */
  playBattleOpponents: (gameId: string, battleId: string, accessToken: string) =>
    request<Battle>(`/api/games/${gameId}/battles/${battleId}/opponents`, {
      method: 'POST',
      headers: auth(accessToken),
    }),

  /** Корпуса и компоненты, доступные империи — п. 8. */
  getShipCatalog: (gameId: string, accessToken: string) =>
    request<ShipCatalog>(`/api/games/${gameId}/ship-designs/catalog`, { headers: auth(accessToken) }),

  /** Действующие проекты кораблей игрока — п. 8. */
  getShipDesigns: (gameId: string, accessToken: string) =>
    request<ShipDesign[]>(`/api/games/${gameId}/ship-designs`, { headers: auth(accessToken) }),

  /**
   * Сохраняет проект в ячейку — п. 8. Занятая ячейка переписывается, а построенные по
   * прежнему проекту корабли остаются прежними.
   */
  saveShipDesign: (gameId: string, accessToken: string, payload: SaveShipDesignPayload) =>
    request<ShipDesign>(`/api/games/${gameId}/ship-designs`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify(payload),
    }),

  /** Убирает проект из ячейки — построенные корабли остаются в строю (п. 8). */
  deleteShipDesign: (gameId: string, designId: string, accessToken: string) =>
    request<ShipDesign[]>(`/api/games/${gameId}/ship-designs/${designId}`, {
      method: 'DELETE',
      headers: auth(accessToken),
    }),

  /**
   * Перелёт флота — п. 8. Лететь может весь флот или его часть: `ships` перечисляет,
   * сколько кораблей какого проекта уходит. Без списка летит весь флот целиком.
   */
  moveFleet: (
    gameId: string,
    fleetId: string,
    accessToken: string,
    targetSystemId: string,
    ships?: { designId: string; ships: number }[],
  ) =>
    request<FleetGroup>(`/api/games/${gameId}/fleets/${fleetId}/move`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetSystemId, ships }),
    }),

  /**
   * Приказ «разведывать самим» — backlog-promo, пункт 24: флот летит к ближайшему белому
   * пятну и дальше ходит сам. Нечего разведывать — отказ 409 словами.
   */
  exploreFleet: (gameId: string, fleetId: string, accessToken: string, on: boolean) =>
    request<FleetGroup>(`/api/games/${gameId}/fleets/${fleetId}/explore`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, on }),
    }),

  /**
   * Списание кораблей со службы — п. 8: экран флота и есть то место, откуда корабль
   * убирают из строя. В ответ приходят все флоты игрока: списанный целиком флот из
   * списка исчезает, и обновлять его строкой было бы нечего.
   */
  scrapFleetShips: (
    gameId: string,
    fleetId: string,
    accessToken: string,
    ships: { designId: string; ships: number }[],
  ) =>
    request<FleetGroup[]>(`/api/games/${gameId}/fleets/${fleetId}/scrap`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, ships }),
    }),

  /**
   * Основать колонию колониальным кораблём флота — п. 4.1.
   *
   * В ответ приходит система целиком: в ней появилась колония, а во флоте стало на
   * корабль меньше.
   */
  colonizeWithFleet: (gameId: string, fleetId: string, accessToken: string, targetPlanetId: string) =>
    request<StarSystem>(`/api/games/${gameId}/fleets/${fleetId}/colonize`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /** Поставить заставу кораблём-заставой — п. 8: дальность империи растёт. */
  outpostWithFleet: (gameId: string, fleetId: string, accessToken: string, targetPlanetId: string) =>
    request<StarSystem>(`/api/games/${gameId}/fleets/${fleetId}/outpost`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /** Высадить десант с транспортов флота — п. 12: захват чужой колонии. */
  /** Подчинить чужую колонию телепатией — п. 7, п. 12: вместо десанта. */
  mindControlWithFleet: (
    gameId: string,
    fleetId: string,
    accessToken: string,
    targetPlanetId: string,
  ) =>
    request<StarSystem>(`/api/games/${gameId}/fleets/${fleetId}/mind-control`, {
      method: 'POST',
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  invadeWithFleet: (gameId: string, fleetId: string, accessToken: string, targetPlanetId: string) =>
    request<InvasionResult>(`/api/games/${gameId}/fleets/${fleetId}/invade`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, targetPlanetId }),
    }),

  /** Встречи флотов, ждущие решения, — п. 8. */
  getEncounters: (gameId: string, accessToken: string) =>
    request<Encounter[]>(`/api/games/${gameId}/encounters`, { headers: auth(accessToken) }),

  /**
   * Решение при встрече флотов — п. 8: атаковать или разойтись. «Авто» включено по
   * умолчанию: ручной бой пока заглушка, и сцена показывает уже посчитанный исход.
   */
  decideEncounter: (
    gameId: string,
    encounterId: string,
    accessToken: string,
    decision: 'ATTACK' | 'IGNORE' | 'BOMBARD',
    auto = true,
  ) =>
    request<Encounter>(`/api/games/${gameId}/encounters/${encounterId}`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, decision, auto }),
    }),

  /** Исследования игрока — п. 9: текущая цель, вложенные очки и изученное. */
  getResearch: (gameId: string, accessToken: string) =>
    request<PlayerResearch>(`/api/games/${gameId}/research`, { headers: auth(accessToken) }),

  /**
   * Выбор технологии для исследования — п. 9. Уровень идёт вместе с разделом: сервер
   * проверяет, что игрок не перепрыгнул через неизученные уровни раздела.
   */
  chooseResearch: (
    gameId: string,
    accessToken: string,
    categoryCode: string,
    levelOrder: number,
    optionCode: string,
  ) =>
    request<PlayerResearch>(`/api/games/${gameId}/research`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, categoryCode, levelOrder, optionCode }),
    }),

  /**
   * Как колония ведёт очередь — п. 10: повтор по кругу и автострой.
   *
   * Признаки шлются по одному: пустое поле значит «оставить как было», и окно, которое
   * знает об одной кнопке, не гасит вторую.
   */
  setBuildMode: (gameId: string, planetId: string, accessToken: string,
                 mode: { repeatBuild?: boolean; autoBuild?: boolean; autoJobs?: boolean }) =>
    request<Planet>(`/api/games/${gameId}/planets/${planetId}/build-mode`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, ...mode }),
    }),

  /**
   * Шаблоны стройки — п. 10. Адрес вне партии: шаблон принадлежит учётной записи и нужен
   * в главном меню, где партии ещё нет.
   */
  listBuildTemplates: () => request<BuildTemplate[]>('/api/build-templates', { headers: account() }),

  saveBuildTemplate: (template: { id?: string; name: string; tier: number; projects: string[] }) =>
    request<BuildTemplate>('/api/build-templates', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(template),
    }),

  deleteBuildTemplate: (templateId: string) =>
    request<void>(`/api/build-templates/${templateId}`, { method: 'DELETE', headers: account() }),

  /**
   * Заложить колонии шаблон — п. 10: берётся то, что колония может построить сейчас, и в
   * том порядке, в каком записано; остальное пропускается.
   */
  enqueueTemplate: (gameId: string, planetId: string, accessToken: string, templateId: string) =>
    // Пропущенное — с причиной словами (field-backlog, пункт 97): молча пропущенное читается
    // как поломка шаблона.
    request<{ planet: Planet; skipped: { name: string; reason: string }[] }>(`/api/games/${gameId}/planets/${planetId}/queue/template`, {
      method: 'POST',
      headers: { ...auth(accessToken), ...account() },
      body: JSON.stringify({ accessToken, templateId }),
    }),

  /** Сохранение партии: сервер снимает слепок на конец завершённого хода. */
  saveGame: (gameId: string, accessToken: string) =>
    request<GameSave>(`/api/games/${gameId}/save`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Сохранённые партии, свежие сверху — список для диалога загрузки.
   *
   * Пропуск записи идёт ради одного поля — `loadable`: автосохранение чужой партии
   * поднять нельзя (п. 3), и видно это должно быть в самом списке.
   */
  listSaves: () => request<GameSave[]>('/api/saves', { headers: account() }),

  /**
   * Партии, где у этой записи есть место, — «вернуться в партию» (п. 3).
   *
   * Без этого списка вернуться в партию, поднятую соседом из автосохранения, нельзя
   * ничем: в списке открытых её нет (она уже идёт), а её опознавателя игроку никто не
   * сообщал.
   */
  myGames: () => request<GameSummary[]>('/api/games/mine', { headers: account() }),

  /** Сравнение с собой прошлым — field-backlog, пункт 115; пусто — сравнивать не с чем. */
  pastSelf: (gameId: string, accessToken: string) =>
    request<PastSelf | undefined>(`/api/games/${gameId}/past-self`, { headers: auth(accessToken) }),

  /** История своих партий — field-backlog, пункт 18. */
  gameHistory: () => request<GameHistory>('/api/auth/game-history', { headers: account() }),

  /** Уровень следующей партии по итогу прошлой — field-backlog, пункт 10; пусто — предлагать нечего. */
  difficultySuggestion: () =>
    request<DifficultySuggestion>('/api/auth/difficulty-suggestion', { headers: account() }),

  /** Библиотека проектов кораблей учётной записи — пункт 96. */
  shipTemplates: () => request<ShipTemplate[]>('/api/ship-templates', { headers: account() }),

  saveShipTemplate: (template: ShipTemplate) =>
    request<ShipTemplate>('/api/ship-templates', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify(template),
    }),

  deleteShipTemplate: (id: string) =>
    request<void>(`/api/ship-templates/${id}`, { method: 'DELETE', headers: account() }),

  /** Свои расы учётной записи — пункты 26 и 176. */
  raceTemplates: () => request<RaceTemplate[]>('/api/race-templates', { headers: account() }),

  saveRaceTemplate: (template: RaceTemplate) =>
    request<RaceTemplate>('/api/race-templates', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ id: template.id, name: template.name, traits: template.traits }),
    }),

  deleteRaceTemplate: (id: string) =>
    request<void>(`/api/race-templates/${id}`, { method: 'DELETE', headers: account() }),

  /** Что выбрал бы советник — пункт 23. */
  advice: (gameId: string, accessToken: string) =>
    request<Advice>(`/api/games/${gameId}/advice`, { headers: auth(accessToken) }),

  /** Что можно отменить до конца хода — пункт 74. */
  listUndo: (gameId: string, accessToken: string) =>
    request<TurnUndo[]>(`/api/games/${gameId}/undo`, { headers: auth(accessToken) }),

  /** Отменить действие этого хода; в ответ — что ещё можно отменить. */
  undo: (gameId: string, accessToken: string, undoId: string) =>
    request<TurnUndo[]>(`/api/games/${gameId}/undo/${undoId}`, { method: 'POST', headers: auth(accessToken) }),

  /** «Играть дальше» после победы — пункт 131. */
  playOn: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/play-on`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /** Свободное сообщение одному человеку или всем — пункт 16. */
  sendMessage: (gameId: string, accessToken: string, text: string, toPlayerId?: string) =>
    request<GameSignal[]>(`/api/games/${gameId}/signals`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken, code: 'TEXT', text, toPlayerId }),
    }),

  /** Кого игрок выключил — пункт 16. */
  mutedPlayers: (gameId: string, accessToken: string) =>
    request<string[]>(`/api/games/${gameId}/signals/muted`, { headers: auth(accessToken) }),

  /** Выключить или вернуть сообщения игрока — пункт 16. */
  mutePlayer: (gameId: string, accessToken: string, playerId: string, muted: boolean) =>
    request<string[]>(`/api/games/${gameId}/signals/muted/${playerId}?muted=${muted}`, {
      method: 'PUT',
      headers: auth(accessToken),
    }),

  /** Пожаловаться на сообщение — пункт 16: ложится в «Сообщения о проблемах». */
  reportMessage: (gameId: string, accessToken: string, signalId: string) =>
    request<void>(`/api/games/${gameId}/signals/${signalId}/report`, {
      method: 'POST',
      headers: { ...auth(accessToken), ...account() },
    }),

  /** Галактика недели — пункт 146: условия и итоги по расам. */
  weeklyGalaxy: () => request<WeeklyGalaxy>('/api/weekly', { headers: account() }),

  /** Ученик называет наставника; пусто — снимает согласие (пункт 184). */
  chooseMentor: (gameId: string, accessToken: string, mentorPlayerId: string | null) =>
    request<void>(`/api/games/${gameId}/mentor`, {
      method: 'PUT',
      headers: auth(accessToken),
      body: JSON.stringify({ mentorPlayerId }),
    }),

  /** Ученики наставника — каждый своими глазами (пункт 184). */
  mentorStudents: (gameId: string, accessToken: string) =>
    request<MentorView[]>(`/api/games/${gameId}/mentor/students`, { headers: auth(accessToken) }),

  /** Метка наставника на карте ученика; пустой знак снимает её (пункт 184). */
  markForStudent: (gameId: string, accessToken: string, studentPlayerId: string, systemId: string, code: string | null) =>
    request<MapMark[]>(`/api/games/${gameId}/mentor/marks`, {
      method: 'PUT',
      headers: auth(accessToken),
      body: JSON.stringify({ studentPlayerId, systemId, code }),
    }),

  /** Партии, открытые зрителям, — пункт 59. */
  spectatableGames: () => request<SpectatableGame[]>('/api/spectate', { headers: account() }),

  /** Партия глазами зрителя — с отставанием на несколько ходов (пункт 59). */
  spectate: (gameId: string) => request<SpectatorFrame>(`/api/spectate/${gameId}`, { headers: account() }),

  /** Уведомления о ходе — пункт 22: ключ сервера, подписан ли браузер, письма. */
  notifySettings: () => request<NotifySettingsState>('/api/notify', { headers: account() }),

  saveNotifyMail: (notifyMail: boolean) =>
    request<Account>('/api/notify/mail', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ notifyMail }),
    }),

  subscribePush: (endpoint: string, p256dh: string, auth: string) =>
    request<void>('/api/notify/push', {
      method: 'POST',
      headers: account(),
      body: JSON.stringify({ endpoint, p256dh, auth }),
    }),

  unsubscribePush: (endpoint: string) =>
    request<void>(`/api/notify/push?endpoint=${encodeURIComponent(endpoint)}`, {
      method: 'DELETE',
      headers: account(),
    }),

  /** Советник — пункт 23: пусто — по умолчанию. */
  saveAdvisor: (advisor: boolean | null) =>
    request<Account>('/api/auth/advisor', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ advisor }),
    }),

  /** Сцены, увиденные целиком, — пункт 163. */
  saveSeenScenes: (seenScenes: string[]) =>
    request<Account>('/api/auth/scenes', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ seenScenes }),
    }),

  /** Игровой псевдоним по умолчанию — личный кабинет. */
  saveName: (name: string) =>
    request<Account>('/api/auth/name', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ name }),
    }),

  /** Своё знамя — пункт 144: предлагается первым в новой партии, если свободно. */
  saveBanner: (banner: string | null) =>
    request<Account>('/api/auth/banner', {
      method: 'PUT',
      headers: account(),
      body: JSON.stringify({ banner }),
    }),

  /** Имя флота — field-backlog, пункт 141; пустое снимает его. */
  renameFleet: (gameId: string, fleetId: string, accessToken: string, name: string) =>
    request<void>(`/api/games/${gameId}/fleets/${fleetId}/name`, {
      method: 'PUT',
      body: JSON.stringify({ accessToken, name }),
    }),

  /**
   * Итоги оконченной партии — field-backlog, пункт 33. По учётной записи, как и возврат:
   * сцену открывают и из «Ваших партий», где пропуска места может не быть.
   */
  gameEnding: (gameId: string) =>
    request<GameEnding>(`/api/games/${gameId}/ending`, { headers: account() }),

  /**
   * Возврат в свою партию — п. 3: запись получает пропуск СВОЕГО места.
   *
   * Пропуск слота выдаётся заново при каждой загрузке сохранения, а лежащий в браузере
   * к поднятой партии не подходит. Узнаёт игрока учётная запись, а не пропуск.
   */
  rejoinGame: (gameId: string) =>
    request<JoinedGame>(`/api/games/${gameId}/rejoin`, { method: 'POST', headers: account() }),

  /**
   * Покинуть партию — backlog-promo, пункт 11: империю ведёт ИИ, партия ушедшего не ждёт.
   * Вернуться — «Ваши партии» главного меню (`rejoinGame`).
   */
  /** Прогресс к путям к победе и ближайшая веха — field-backlog, пункты 51 и 11. */
  victoryProgress: (gameId: string, accessToken: string) =>
    request<VictoryProgress>(`/api/games/${gameId}/victory-progress`, { headers: auth(accessToken) }),

  /** Эволюционная мутация (п. 7): очки и стороны, которые можно взять. */
  mutationOptions: (gameId: string, accessToken: string) =>
    request<{ picks: number; options: MutationOption[] }>(`/api/games/${gameId}/race/mutation`, {
      headers: auth(accessToken),
    }),

  /** Ответ на мутацию: выбранные стороны прибавляются к расе, остаток очков сгорает. */
  mutate: (gameId: string, accessToken: string, traits: string[]) =>
    request<void>(`/api/games/${gameId}/race/mutation`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accessToken, traits }),
    }),

  /** Согласие с итогом по могуществу (09.10.2026): до конца хода ответ можно переменить. */
  answerMight: (gameId: string, accessToken: string, agree: boolean) =>
    request<void>(`/api/games/${gameId}/might/answer`, {
      method: 'POST',
      headers: { ...auth(accessToken), 'Content-Type': 'application/json' },
      body: JSON.stringify({ agree }),
    }),

  /** Записка к партии — field-backlog, пункт 110: только своему игроку. */
  gameNote: (gameId: string, accessToken: string) =>
    request<{ note?: string }>(`/api/games/${gameId}/note`, { headers: auth(accessToken) }),

  saveGameNote: (gameId: string, accessToken: string, note: string) =>
    request<{ note?: string }>(`/api/games/${gameId}/note`, {
      method: 'PUT',
      headers: auth(accessToken),
      body: JSON.stringify({ note }),
    }),

  /** Хроника партии — field-backlog, пункт 138: всё заметное по ходам, на языке читателя. */
  chronicle: (gameId: string, accessToken: string) =>
    request<EndingMoment[]>(`/api/games/${gameId}/chronicle`, { headers: auth(accessToken) }),

  /** Что ИИ сделал за вас — field-backlog, пункт 112: решения опекуна за время отсутствия. */
  stewardDecisions: (gameId: string, accessToken: string) =>
    request<StewardDecision[]>(`/api/games/${gameId}/steward`, { headers: auth(accessToken) }),

  /** Решения опекуна прочитаны — следующая отлучка начнёт список заново. */
  stewardSeen: (gameId: string, accessToken: string) =>
    request<StewardDecision[]>(`/api/games/${gameId}/steward/seen`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * «Я здесь» — field-backlog, пункт 127: отвечает на предупреждение о передаче империи ИИ и
   * забирает её у ИИ обратно. Ответ — состав партии с новым сроком.
   */
  imHere: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/presence`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Забрать объявленный ход — field-backlog, пункт 128: пока соперники не закончили, ход не
   * посчитан. Ответ — состав партии; ход уже посчитан — 409.
   */
  undoEndTurn: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/turn/undo`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /**
   * Признать поражение — field-backlog, пункт 81: империю дальше ведёт ИИ. Ответ — партия ПОСЛЕ
   * признания: окончена она (один человек против ИИ) или идёт дальше без вас.
   */
  /** Можно ли объявить победу — field-backlog, пункт 154; меню гасит пункт до нажатия. */
  victoryClaim: (gameId: string, accessToken: string) =>
    request<VictoryClaim>(`/api/games/${gameId}/victory-claim`, { headers: auth(accessToken) }),

  /** Объявить победу — пункт 154: партия кончается победой с пометкой «досрочно». */
  declareVictory: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/declare-victory`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  concede: (gameId: string, accessToken: string) =>
    request<GameDetails>(`/api/games/${gameId}/concede`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  leaveGame: (gameId: string, accessToken: string) =>
    request<unknown>(`/api/games/${gameId}/leave`, {
      method: 'POST',
      headers: auth(accessToken),
      body: JSON.stringify({ accessToken }),
    }),

  /** Загрузка сохранения: сервер поднимает из слепка новую партию. */
  loadSave: (saveId: string) =>
    request<JoinedGame>(`/api/saves/${saveId}/load`, { method: 'POST', headers: account() }),

  /** Автосохранение партии — field-backlog, пункт 160; пусто — его ещё нет. */
  autosave: (gameId: string) =>
    request<GameSave | undefined>(`/api/saves/auto/${gameId}`, { headers: account() }),

  /** Слепок файлом — пункт 160: сам файл как JSON. */
  exportSave: (saveId: string) =>
    request<unknown>(`/api/saves/${saveId}/file`, { headers: account() }),

  /** Партия из файла — пункт 160: только одиночная, поднимает её принёсший файл. */
  /** Признаки живой игры — пункт 45: только администратору и модератору. */
  liveStats: () => request<LiveStats>('/api/stats/live', { headers: account() }),

  /** Свои наборы справочников — пункт 42: только основателю и администратору. */
  listMods: () => request<ModSet[]>('/api/mods', { headers: account() }),
  saveMod: (body: { name: string; buildings?: string; ships?: string; technologies?: string; leaders?: string }) =>
    request<ModSet>('/api/mods', { method: 'POST', headers: { ...account(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body) }),
  deleteMod: (id: string) => request<void>('/api/mods/' + id, { method: 'DELETE', headers: account() }),
  /** Файл справочника игры — основа набора (пункт 42). */
  modBase: (part: string) => request<unknown>('/api/mods/base/' + part, { headers: account() }),

  importSave: (content: string) =>
    request<JoinedGame>('/api/saves/import', { method: 'POST', headers: account(), body: content }),

  /**
   * Удаление сохранения — только администратором (п. 3.1).
   *
   * Сохранения лежат на сервере общей кучей и хозяина не имеют: их видят и загружают все.
   * Поэтому убирать их — дело того, кто отвечает за сервер; обычному игроку сервер
   * ответит отказом, сколько бы кнопок ни нарисовал клиент.
   */
  deleteSave: (saveId: string) =>
    request<void>(`/api/saves/${saveId}`, { method: 'DELETE', headers: account() }),

  /**
   * Карта галактики — п. 11.3. Игрок видит только свои системы; `revealAll` («Показать
   * галактику» в «Инфо») отдаёт галактику целиком.
   */
  getMap: (gameId: string, accessToken: string, revealAll = false) =>
    request<GalaxyMap>(`/api/games/${gameId}/map?revealAll=${revealAll}`, {
      headers: auth(accessToken),
    }),

  deleteGame: (gameId: string, accessToken: string) =>
    request<void>(`/api/games/${gameId}`, { method: 'DELETE', headers: auth(accessToken) }),

  reference: {
    galaxySizes: () => request<GalaxySizeOption[]>('/api/reference/galaxy-sizes'),
    /** Уровни сложности и что ИИ на каждом делает — field-backlog, пункты 9 и 87. */
    aiSkills: () => request<AiSkillInfo[]>('/api/reference/ai-skills'),
    planetSizes: () => request<PlanetSizeRef[]>('/api/reference/planet-sizes'),
    planetClimates: () => request<PlanetClimateRef[]>('/api/reference/planet-climates'),
    minerals: () => request<MineralRichnessRef[]>('/api/reference/minerals'),
    races: () => request<Race[]>('/api/reference/races'),

    /**
     * Справочник зданий — нужен экрану шаблонов стройки (п. 10).
     *
     * Спрашивается СВОИМ запросом, а не кладётся в общий справочник при подключении:
     * шаблоны открывают редко, а грузить полсотни зданий каждому входящему незачем.
     */
    buildings: () => request<BuildingRef[]>('/api/reference/buildings'),
    /**
     * Заводит демонстрационный бой — п. 8: две эскадры сходятся сами, без игрока.
     * Пропуска не требует: сцену смотрят из главного меню, до входа в партию.
     */
    startDemoBattle: () =>
      request<DemoBattle>('/api/reference/demo-battle', { method: 'POST' }),

    /** Шаг демонстрации — п. 8: ходит корабль, чья очередь. */
    demoBattleStep: (battleId: string) =>
      request<Battle>(`/api/reference/demo-battle/${battleId}/step`, { method: 'POST' }),

    /** Конструктор расы — п. 7: бюджет очков и группы особенностей. */
    raceTraits: () => request<RaceDesign>('/api/reference/race-traits'),

    /**
     * Правка цен особенностей — п. 7. Сервер пишет их в свой JSON-справочник и отдаёт
     * конструктор заново: экран показывает то, что действительно легло в файл.
     */
    saveRaceTraitCosts: (payload: SaveRaceTraitCostsPayload) =>
      request<RaceDesign>('/api/reference/race-traits', {
        method: 'PUT',
        body: JSON.stringify(payload),
      }),
    /** Дерево технологий для экрана исследований — п. 9. */
    research: () => request<ResearchTree>('/api/reference/research'),
    /** Справочник «Как устроено» — field-backlog, пункты 75 и 200: порядок хода и правила. */
    rules: () => request<RulesBook>('/api/reference/rules'),
  },
};
