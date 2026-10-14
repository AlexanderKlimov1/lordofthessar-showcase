import { useEffect, useState } from 'react';
import type { DifficultySuggestion } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { GalaxyPicture } from './GalaxyPicture';
import { galaxySizeName } from '../planetLabels';
import { RuleNote } from '../RuleNote';
import { GALAXY_FEATURES, featureNameKey, featureNoteKey } from './galaxyFeatures';
import { AI_SKILLS, SkillLines, skillNameKey } from './aiSkills';
import type { ModSet } from '../../api/types';
import { canMod } from '../profile/ModsSection';
import { chooseMod, chosenMod } from '../../state/chosenMod';

/**
 * Окно новой игры — п. 3, окно NEW GAME MOO II.
 *
 * Разметка снята с оригинала: плашка названия сверху по центру, ряд настроек картинками, у
 * каждой свой заголовок сверху и плашка текущего значения снизу, блок переключателей
 * справа от них, а внизу две кнопки по краям — «отмена» слева, «принять» справа. Доли
 * ширины взяты оттуда же: картинка примерно седьмая часть окна, плашка значения шире
 * картинки, кнопки по краям — шестая.
 *
 * **Настройка переключается нажатием по кругу** — это правило оригинала дословно: «All of
 * the New Game Menu's options work by clicking until it cycles round to what you want —
 * there are no drop-down lists or radio button groups» (StrategyWiki, Starting a game).
 * Поэтому здесь нет ни списков, ни переключателей-радио: щелчок по картинке даёт следующее
 * значение, а по последнему — первое.
 *
 * Пояснений на сцене нет намеренно. Настроек три, каждая названа заголовком и подписана
 * значением; абзац о том, что такое случайные события и сколько звёзд в галактике, занимал
 * пол-окна и читался один раз в жизни.
 */
interface NewGameScreenProps {
  gameName: string;
  galaxySize: string;
  totalPlayers: number;
  galacticEvents: boolean;
  /** Пути к победе — п. 3: все, кроме покорения, выключаются здесь. */
  council: boolean;
  wardenholdVictory: boolean;
  mightVictory: boolean;
  /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
  turnSeconds?: number;
  onTurnSeconds: (value: number | undefined) => void;
  /** Разрешить зрителей — field-backlog, пункт 59. */
  spectators: boolean;
  onSpectators: (value: boolean) => void;
  /** Закрытая партия — пункт 67: только по ссылке-приглашению. */
  inviteOnly: boolean;
  onInviteOnly: (value: boolean) => void;
  /** Ход итога по могуществу — пункт 24: 100, 200 или 300. */
  mightTurn: number;
  onMightTurn: (value: number) => void;
  /** Тихая галактика — пункт 186: ИИ войну первым не начинает. */
  peacefulAi: boolean;
  onPeacefulAi: (value: boolean) => void;
  /** «Партия под вечер» — пункт 24: набор настроек одной кнопкой. */
  onEvening: () => void;
  /** Особенность галактики — пункт 94: RANDOM, NONE или код. */
  galaxyFeature: string;
  onGalaxyFeature: (value: string) => void;
  /** Уровень сложности — пункт 9: BEGINNER, EASY, NORMAL или HARD. */
  aiSkill: string;
  onAiSkill: (value: string) => void;
  onGameName: (value: string) => void;
  onGalaxySize: (value: string) => void;
  onTotalPlayers: (value: number) => void;
  onGalacticEvents: (value: boolean) => void;
  onCouncil: (value: boolean) => void;
  onWardenholdVictory: (value: boolean) => void;
  onMightVictory: (value: boolean) => void;
  /** Дальше выбор расы — он же и создаёт партию. */
  onAccept: () => void;
  onCancel: () => void;
}

/**
 * Сколько империй бывает в партии — п. 3: столько же принимает сервер
 * ({@code CreateGameRequest.totalPlayers}, 2…8).
 *
 * Список, а не два числа с шагом: значение переключается по кругу, и крайние ступени
 * должны смыкаться — после восьми снова две.
 */
const PLAYER_COUNTS = [2, 3, 4, 5, 6, 7, 8];

export function NewGameScreen({
  gameName,
  galaxySize,
  totalPlayers,
  galacticEvents,
  council,
  wardenholdVictory,
  mightVictory,
  turnSeconds,
  onTurnSeconds,
  spectators,
  onSpectators,
  inviteOnly,
  onInviteOnly,
  mightTurn,
  onMightTurn,
  peacefulAi,
  onPeacefulAi,
  onEvening,
  galaxyFeature,
  onGalaxyFeature,
  aiSkill,
  onAiSkill,
  onGameName,
  onGalaxySize,
  onTotalPlayers,
  onGalacticEvents,
  onCouncil,
  onWardenholdVictory,
  onMightVictory,
  onAccept,
  onCancel,
}: NewGameScreenProps) {
  useT();
  /*
    Свой набор справочников (пункт 42) — только основателю: по кругу «справочники игры» и наборы из
    кабинета. Выбор живёт в `chosenMod` до заказа партии — как своя раса из кабинета.
  */
  const [mods, setMods] = useState<ModSet[]>([]);
  const [modId, setModId] = useState<string | null>(chosenMod());
  useEffect(() => {
    if (canMod()) {
      gameApi.listMods().then(setMods).catch(() => setMods([]));
    }
  }, []);
  const cycleMod = () => {
    const order = [null, ...mods.map((mod) => mod.id)];
    const next = order[(order.indexOf(modId) + 1) % order.length] ?? null;
    setModId(next);
    chooseMod(next);
  };
  useModalEscape(true, onCancel);
  const galaxySizes = useGameStore((state) => state.galaxySizes);
  const [pressed, setPressed] = useState<string | null>(null);
  // Подсказка уровня по прошлой партии — пункт 10: только ПРЕДЛАГАЕТ, выбор остаётся игроку.
  const [suggestion, setSuggestion] = useState<DifficultySuggestion>({});
  useEffect(() => {
    gameApi.difficultySuggestion().then((answer) => setSuggestion(answer ?? {})).catch(() => undefined);
  }, []);

  const index = Math.max(0, galaxySizes.findIndex((size) => size.code === galaxySize));
  const selected = galaxySizes[index];
  // Насколько картинка галактики полна: от самой мелкой к самой крупной. Считается по
  // месту в справочнике, а не по коду, — размеров в игре может стать больше.
  const fill = galaxySizes.length > 1 ? 0.62 + 0.38 * (index / (galaxySizes.length - 1)) : 1;

  const cycleSize = () => {
    if (galaxySizes.length === 0) {
      return;
    }
    onGalaxySize(galaxySizes[(index + 1) % galaxySizes.length].code);
  };

  const cycleFeature = () => {
    const at = GALAXY_FEATURES.indexOf(galaxyFeature);
    onGalaxyFeature(GALAXY_FEATURES[(at + 1) % GALAXY_FEATURES.length]);
  };

  const cycleSkill = () => {
    const at = AI_SKILLS.indexOf(aiSkill);
    onAiSkill(AI_SKILLS[(at + 1) % AI_SKILLS.length]);
  };

  const cycleTurnSeconds = () => {
    const at = TURN_SECONDS.indexOf(turnSeconds);
    onTurnSeconds(TURN_SECONDS[(at + 1) % TURN_SECONDS.length]);
  };

  const cyclePlayers = () => {
    const at = PLAYER_COUNTS.indexOf(totalPlayers);
    onTotalPlayers(PLAYER_COUNTS[(at + 1) % PLAYER_COUNTS.length]);
  };

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      {/*
        Окно, а не экран во всю ширину: в оригинале новая игра — именно окно посреди
        экрана, и его пропорции (примерно 8:5) держат разметку узнаваемой. Ширина взята
        от окна браузера с потолком: на широком мониторе окно не растягивается в ленту.
      */}
      <section className="my-auto w-[min(92vw,46rem)] border border-space-600 bg-space-900/95 p-[3%] shadow-lg shadow-black/60">
        {/* Плашка названия — по центру сверху, как в оригинале. */}
        <h2 className="mx-auto mb-[3%] w-[52%] border border-space-600 bg-space-800 py-2 text-center text-20 uppercase tracking-[0.35em] text-ink-bright">
          {t('menu.newGame')}
        </h2>

        {/*
          Название партии. Своего места в оригинале у него нет — там партию не называют
          вовсе, — поэтому строка стоит под плашкой названия и занимает ту же ширину:
          лишнего сегмента в разметке не заводим.
        */}
        <label className="mx-auto mb-[4%] flex w-[52%] items-baseline gap-2 text-12 text-ink-dim">
          <span className="whitespace-nowrap">{t('menu.gameName')}</span>
          <input
            className="field"
            maxLength={128}
            value={gameName}
            onChange={(event) => onGameName(event.target.value)}
          />
        </label>

        <div className="flex items-start justify-between gap-[4%]">
          <Option
            title={t('menu.galaxySize')}
            value={galaxySizeName(galaxySize, selected?.label)}
            note={selected
              ? t('menu.galaxy.summary', {
                w: selected.widthParsecs,
                h: selected.heightParsecs,
                stars: selected.totalStarCount,
              })
              : ''}
            pressed={pressed === 'size'}
            onCycle={() => {
              setPressed('size');
              cycleSize();
            }}
          >
            <GalaxyPicture stars={selected?.totalStarCount ?? 40} fill={fill} />
          </Option>

          <Option
            title={t('newGame.players')}
            value={t('newGame.players.value', { n: totalPlayers })}
            note={t('newGame.players.note')}
            pressed={pressed === 'players'}
            onCycle={() => {
              setPressed('players');
              cyclePlayers();
            }}
          >
            {/* Число во всю картинку — как цифра игроков в окне оригинала. */}
            <span className="flex h-full w-full items-center justify-center text-[3.2em] leading-none text-ink-bright">
              {totalPlayers}
            </span>
          </Option>

          {/*
            Блок переключателей — в оригинале он стоит справа от картинок и собран в свою
            рамку: тактический бой, случайные события, налёты антаран. У нас в нём
            случайные события и ПУТИ К ПОБЕДЕ (п. 3, backlog-promo, пункт 2): партию
            настраивают здесь, и то, чем она кончится, — такая же её настройка, как размер
            галактики. Покорение стоит в списке тоже, но не выключается: без него партия,
            где соперников не осталось, не кончилась бы ничем. Прочие включены по умолчанию.
          */}
          <div className="w-[32%] space-y-2 border border-space-700 bg-space-950/60 p-3">
            <Flag on={galacticEvents} label={t('menu.events')} onToggle={() => onGalacticEvents(!galacticEvents)} />
            {/* Удача названа удачей (пункт 104): перекос событий против сильнейшего сказан, а не спрятан. */}
            {galacticEvents ? <RuleNote text={t('rule.eventsBias')} /> : null}
            <div className="pt-2 text-11 uppercase tracking-[0.2em] text-ink-dim">{t('newGame.victory')}</div>
            <Flag on label={t('newGame.victory.conquest')} note={t('newGame.victory.always')} />
            {/* Покорение союзом — пункт 145, отступление от оригинала: сказано там, где выбирают пути. */}
            <RuleNote text={t('rule.alliedVictory')} />
            <Flag
              on={council}
              label={t('newGame.victory.council')}
              note={t('rule.council')}
              onToggle={() => onCouncil(!council)}
            />
            <Flag
              on={wardenholdVictory}
              label={t('newGame.victory.wardenhold')}
              note={t('rule.wardenhold')}
              onToggle={() => onWardenholdVictory(!wardenholdVictory)}
            />
            <Flag
              on={mightVictory}
              label={t('newGame.victory.might', { turn: mightTurn })}
              note={t('rule.might', { turn: mightTurn })}
              onToggle={() => onMightVictory(!mightVictory)}
            />
            {/*
              Срок итога (пункт 24) — по кругу: 100, 200, 300. Сотый — «партия под вечер»; итог
              спрашивает согласия людей, и при огромном отрыве может прийти раньше (пункт 80).
            */}
            {mightVictory ? (
              <button
                type="button"
                className="flex w-full items-baseline justify-between gap-2 pl-7 text-left text-13 text-ink hover:text-accent"
                onClick={() => onMightTurn(MIGHT_TURNS[(MIGHT_TURNS.indexOf(mightTurn) + 1) % MIGHT_TURNS.length])}
              >
                <span className="text-11 uppercase tracking-[0.2em] text-ink-dim">{t('newGame.mightTurn')}</span>
                <span>{mightTurn}</span>
              </button>
            ) : null}
            {mightVictory ? <RuleNote text={t('rule.mightEarly')} /> : null}
            {/*
              Тихая галактика (пункт 186): другой вид партии, не уровень сложности. В оригинале такой
              настройки нет — сказано строкой отступления.
            */}
            {mods.length > 0 ? (
              <button
                type="button"
                className="flex w-full items-baseline justify-between gap-2 text-left text-14 text-ink hover:text-accent"
                title={t('newGame.mod.note')}
                onClick={cycleMod}
              >
                <span className="text-11 uppercase tracking-[0.2em] text-ink-dim">{t('newGame.mod')}</span>
                <span>{mods.find((mod) => mod.id === modId)?.name ?? t('newGame.mod.none')}</span>
              </button>
            ) : null}
            {modId ? <RuleNote text={t('rule.mod')} /> : null}
            <Flag
              on={peacefulAi}
              label={t('newGame.peaceful')}
              note={t('newGame.peaceful.note')}
              onToggle={() => onPeacefulAi(!peacefulAi)}
            />
            {peacefulAi ? <RuleNote text={t('rule.peaceful')} /> : null}
            {/*
              Зрители (пункт 59): партию смотрят со стороны без тумана войны, но с отставанием на
              несколько ходов. Согласие даёт создатель, и входящий за стол видит его до входа.
            */}
            <Flag
              on={spectators}
              label={t('newGame.spectators')}
              note={t('newGame.spectators.note')}
              onToggle={() => onSpectators(!spectators)}
            />
            {/*
              Закрытая партия (пункт 67): хозяин стола собирает своих — в списке открытых её нет,
              а ссылку-приглашение он берёт в лобби.
            */}
            <Flag
              on={inviteOnly}
              label={t('newGame.inviteOnly')}
              note={t('newGame.inviteOnly.note')}
              onToggle={() => onInviteOnly(!inviteOnly)}
            />
            {/*
              Особенность галактики (пункт 94) — по кругу, как всё в этом окне. Умолчание — жребий
              (решение хозяина проекта): каждая партия приносит новый узор, если игрок не против.
            */}
            <button
              type="button"
              className="flex w-full items-baseline justify-between gap-2 pt-2 text-left text-13 text-ink hover:text-accent"
              title={t(featureNoteKey(galaxyFeature))}
              onClick={cycleFeature}
            >
              <span className="text-11 uppercase tracking-[0.2em] text-ink-dim">{t('newGame.feature')}</span>
              <span>{t(featureNameKey(galaxyFeature))}</span>
            </button>
            {/*
              Уровень сложности (пункты 9, 10, 87) — по кругу, умолчание — обычный. Под ним — что ИИ
              этого уровня делает (признаками его правила, с сервера) и подсказка по прошлой партии.
            */}
            <button
              type="button"
              className="flex w-full items-baseline justify-between gap-2 pt-2 text-left text-13 text-ink hover:text-accent"
              onClick={cycleSkill}
            >
              <span className="text-11 uppercase tracking-[0.2em] text-ink-dim">{t('newGame.difficulty')}</span>
              <span>{t(skillNameKey(aiSkill))}</span>
            </button>
            <SkillLines code={aiSkill} className="space-y-0.5 text-11 text-ink-soft" />
            {suggestion.suggested && suggestion.suggested !== aiSkill ? (
              <p className="text-11 text-ink-dim">
                {t(suggestion.reason === 'EASY_WIN' ? 'difficulty.suggest.easyWin'
                  : suggestion.reason === 'FIRST_GAME' ? 'difficulty.suggest.firstGame' : 'difficulty.suggest.earlyLoss', {
                  turn: suggestion.turn ?? 0,
                  level: t(skillNameKey(suggestion.last)),
                })}{' '}
                <button type="button" className="link" onClick={() => onAiSkill(suggestion.suggested!)}>
                  {t('difficulty.suggest.take', { level: t(skillNameKey(suggestion.suggested)) })}
                </button>
              </p>
            ) : null}
            {/*
              Срок хода (backlog-promo, пункт 11) — по кругу, как всё в этом окне: истёк, и
              ход считается сам, опоздавший пропускает свои решения. Нужен партии людей —
              одиночную партию часы не торопят: ждать в ней некому.
            */}
            <button
              type="button"
              className="flex w-full items-baseline justify-between gap-2 pt-2 text-left text-13 text-ink hover:text-accent"
              onClick={cycleTurnSeconds}
            >
              <span className="text-11 uppercase tracking-[0.2em] text-ink-dim">{t('newGame.turnTimer')}</span>
              <span>{turnTimerLabel(turnSeconds)}</span>
            </button>
          </div>
        </div>

        {/* Полоса кнопок: отмена слева, принять справа — как CANCEL и ACCEPT оригинала. */}
        <div className="mt-[5%] flex items-center justify-between">
          <button
            type="button"
            className="w-[18%] border border-space-600 bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-ink hover:text-accent"
            onClick={onCancel}
          >
            {t('common.cancel')}
          </button>
          {/* Партия под вечер (пункт 24): малая галактика, три империи, итог на сотом ходу — одной кнопкой. */}
          <button
            type="button"
            className="link text-14"
            title={t('newGame.evening.note')}
            onClick={onEvening}
          >
            {t('newGame.evening')}
          </button>
          <button
            type="button"
            className="w-[18%] border border-accent bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-accent"
            onClick={onAccept}
          >
            {t('newGame.accept')}
          </button>
        </div>
      </section>
    </div>
  );
}

/**
 * Ход, на котором подводится итог по могуществу, — число правила сервера
 * (`MightVictoryRules.TURN_LIMIT`). В окне новой игры партии ещё нет, и спросить его не у
 * кого; в идущей партии число приходит с сервера (`mightVictoryTurn`).
 */
export const MIGHT_VICTORY_TURN = 300;

/** Сроки итога по могуществу — те же, что на сервере (`MightVictoryRules.ALLOWED_TURNS`). */
const MIGHT_TURNS = [100, 200, 300];

/**
 * Сроки хода по кругу — те же, что принимает сервер (`TurnClockRules.ALLOWED_SECONDS`),
 * и первым «без срока»: таймер — выбор для партии людей, а не правило игры.
 */
const TURN_SECONDS: (number | undefined)[] = [undefined, 90, 120, 180, 300, 600, 4 * 3600, 24 * 3600];

/**
 * Подпись срока: «без срока», «1,5 мин», «2 мин» — и медленные партии (пункт 64): «вечерняя, 4 ч»,
 * «по переписке, сутки».
 */
export const turnTimerLabel = (seconds?: number) =>
  seconds === undefined
    ? t('newGame.turnTimer.none')
    : seconds >= 24 * 3600
      ? t('newGame.turnTimer.mail')
      : seconds >= 3600
        ? t('newGame.turnTimer.evening', { n: seconds / 3600 })
        : t('newGame.turnTimer.minutes', { n: String(seconds / 60).replace('.', t('newGame.turnTimer.decimal')) });

/**
 * Флажок блока переключателей. Без `onToggle` он закреплён: виден, но не выключается —
 * так стоит покорение, которое действует всегда.
 */
function Flag({
  on,
  label,
  note,
  onToggle,
}: {
  on: boolean;
  label: string;
  note?: string;
  onToggle?: () => void;
}) {
  return (
    <button
      type="button"
      className="flex w-full items-center gap-3 text-left text-14 text-ink disabled:cursor-default disabled:text-ink-soft"
      disabled={!onToggle}
      title={note}
      onClick={onToggle}
    >
      <span
        className={
          'inline-block h-4 w-4 flex-none border '
          + (on
            ? onToggle ? 'border-accent bg-accent' : 'border-ink-faint bg-ink-faint'
            : 'border-space-600 bg-space-900')
        }
      />
      {label}
    </button>
  );
}

/**
 * Одна настройка: заголовок сверху, картинка, плашка значения снизу.
 *
 * Доли взяты у оригинала: картинка почти квадратная и занимает примерно седьмую часть
 * ширины окна, а плашка значения шире её. Нажатие по всей картинке, а не по стрелкам, —
 * настройки оригинала переключаются именно так.
 */
function Option({
  title,
  value,
  note,
  pressed,
  onCycle,
  children,
}: {
  title: string;
  value: string;
  note: string;
  pressed: boolean;
  onCycle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="w-[32%]">
      <div className="mb-2 text-center text-14 uppercase tracking-[0.2em] text-ink-dim">
        {title}
      </div>
      <button
        type="button"
        className={
          'mx-auto block aspect-[8/7] w-[68%] border bg-space-950 p-1 '
          + (pressed ? 'border-accent' : 'border-space-600 hover:border-accent')
        }
        title={t('newGame.cycle')}
        onClick={onCycle}
      >
        {children}
      </button>
      <div className="mx-auto mt-2 w-[85%] border border-space-700 bg-space-800 py-1 text-center text-13 text-accent">
        {value}
      </div>
      {/* Одна строка параметров — не пояснение, а сами числа выбранного. */}
      <div className="mt-1 text-center text-11 text-ink-faint">{note}</div>
    </div>
  );
}
