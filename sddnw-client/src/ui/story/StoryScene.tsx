import { useEffect } from 'react';
import { useGameStore } from '../../state/gameStore';
import type { Planet, ResearchTree } from '../../api/types';
import { Ambassador, Hall } from '../diplomacy/AudienceScreen';
import { findTechnology } from '../research/researchRules';
import { climateColor, planetImage } from '../system/planetVisuals';
import { useModalEscape } from '../useModalEscape';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { RuleNote } from '../RuleNote';
import type { Story } from './storyEvents';
import { playLeitmotifMoment } from '../../audio/music';

/**
 * Сцена редкого события — backlog-promo, пункт 10.
 *
 * Три вида, выбранные хозяином проекта: <b>посол</b> (знакомство и объявление войны —
 * в MOO II обе вести приносит посол своей расы в своём зале, и зал у нас уже есть — тот же,
 * что у переговоров), <b>колония</b> (взята или потеряна: планета крупно и флаг нового
 * хозяина) и <b>наследие Стражей</b> (клад Wardenhold: технологии карточками, как у сцены
 * кражи).
 *
 * Сцена ничего не решает — всё уже посчитано и записано в итогах хода; её дело рассказать
 * так, чтобы событие запомнилось. Поэтому кнопка одна, а Enter закрывает так же, как Esc.
 * Числа сил стоят и здесь (пункт 6): потеря без причины читается как несправедливость.
 */
export function StoryScene({
  story,
  planet,
  tree,
  onClose,
  onNewGuestGame,
}: {
  story: Story;
  /** Планета колонии из карты — по ней рисуется снимок; нет — круг цвета климата. */
  planet?: Planet;
  tree: ResearchTree | null;
  onClose: () => void;
  /**
   * Поражение — не конец (field-backlog, пункт 8): гостю, потерявшему родной мир, — новая
   * гостевая партия одной кнопкой, а не возврат в меню. Нет — кнопки нет.
   */
  onNewGuestGame?: () => void;
}) {
  useT();
  useModalEscape(true, onClose, true);
  /*
    Лейтмотив — в мигах, которые запоминают (пункт 180): ПЕРВОЕ знакомство в партии и наследие
    Стражей. Первое — одно на партию: тема на каждом посольстве стала бы фоном, а не приметой.
  */
  const gameId = useGameStore((state) => state.game?.id);
  useEffect(() => {
    if (story.kind === 'guardians') {
      playLeitmotifMoment();
      return;
    }
    if (story.kind !== 'contact' || !gameId) {
      return;
    }
    const key = `sddnw.motif.contact.${gameId}`;
    try {
      if (window.localStorage.getItem(key)) {
        return;
      }
      window.localStorage.setItem(key, '1');
    } catch {
      // Хранилище закрыто — тема прозвучит, и это не беда.
    }
    playLeitmotifMoment();
    // Звучит один раз на открытие сцены.
  }, []);

  return (
    <div className="fixed inset-0 z-[58] flex flex-col bg-space-950">
      {story.kind === 'contact' || story.kind === 'war' ? (
        <AmbassadorStory story={story} />
      ) : story.kind === 'colony' ? (
        <ColonyStory story={story} planet={planet} />
      ) : story.kind === 'find' ? (
        <FindStory story={story} planet={planet} />
      ) : story.kind === 'trophy' ? (
        <TrophyStory story={story} />
      ) : story.kind === 'legacy' ? (
        <TechCardsStory title={t('story.legacy.title')} subtitle={t('story.legacy.subtitle', { system: story.system })}
          technologies={story.technologies} tree={tree} />
      ) : (
        <GuardiansStory story={story} tree={tree} />
      )}
      <footer className="relative flex flex-none justify-center gap-3 px-6 pb-8">
        {onNewGuestGame ? (
          <button
            type="button"
            className="border border-lamp bg-space-900 px-8 py-1 text-13 uppercase tracking-[0.2em] text-lamp hover:text-ink-bright"
            onClick={onNewGuestGame}
          >
            {t('story.newGuestGame')}
          </button>
        ) : null}
        <button
          type="button"
          className="border border-space-600 bg-space-900 px-8 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
          onClick={onClose}
        >
          {t('common.ok')}
        </button>
      </footer>
    </div>
  );
}

/** Посол в своём зале: имя империи её цветом сверху, речь внизу. */
function AmbassadorStory({ story }: { story: Extract<Story, { kind: 'contact' | 'war' }> }) {
  const war = story.kind === 'war';
  return (
    <>
      <Hall colour={story.colour} />
      <header className="relative flex flex-none justify-center pt-6">
        <p className="text-22 uppercase tracking-[0.2em]" style={{ color: story.colour }}>
          {t('story.ambassador.title', { empire: story.empire })}
        </p>
      </header>
      <div className="relative min-h-0 flex-1">
        <Ambassador colour={story.colour} raceCode={story.raceCode} name={story.empire} />
      </div>
      <p
        className={`relative flex-none px-6 pb-4 text-center text-18 leading-snug ${
          war ? 'text-danger' : 'text-ink-bright'
        }`}
      >
        {war
          ? t('story.ambassador.war', { empire: story.empire })
          : t('story.ambassador.contact', { empire: story.empire })}
      </p>
    </>
  );
}

/**
 * Колония: планета крупно и флаг того, чья она теперь. Строка говорит, кто, у кого и
 * какими силами — нападавшему и оборонявшемуся своими словами.
 */
function ColonyStory({ story, planet }: { story: Extract<Story, { kind: 'colony' }>; planet?: Planet }) {
  const image = planet ? planetImage(planet.climate, planet.id) : null;
  // Хорошо ли это для игрока: взял сам или отбился.
  const good = story.attacker ? story.captured : !story.captured;
  const line = story.attacker
    ? story.captured
      ? t('story.colony.captured', { colony: story.colony })
      : t('story.colony.repelledYou', { colony: story.colony })
    : story.captured
      ? t('story.colony.lost', { colony: story.colony, empire: story.empire ?? '' })
      : t('story.colony.held', { colony: story.colony, empire: story.empire ?? '' });
  return (
    <>
      <header className="relative flex flex-none justify-center pt-6">
        <p className="text-22 uppercase tracking-[0.2em] text-ink-bright">{story.colony}</p>
      </header>
      <div className="relative flex min-h-0 flex-1 items-center justify-center">
        <div className="relative aspect-square h-[60%]">
          {image ? (
            <img src={image} alt={story.colony} className="h-full w-full rounded-full object-cover" />
          ) : (
            <div
              className="h-full w-full rounded-full"
              style={{ background: climateColor(planet?.climate ?? '') }}
            />
          )}
          {/* Флаг нового хозяина — тот же вымпел, что у заселённой планеты на схеме системы. */}
          <svg viewBox="0 0 100 100" className="absolute left-[2%] -top-[16%] h-[45%] w-[45%]" aria-hidden="true">
            <line x1="20" y1="95" x2="20" y2="10" stroke="#d6dde8" strokeWidth="3" />
            <polygon points="20,10 75,28 20,46" fill={story.colour} stroke="#05070f" strokeWidth="1.5" />
          </svg>
        </div>
      </div>
      <div className="relative flex flex-none flex-col items-center gap-1 px-6 pb-4 text-center">
        <p className={`text-18 leading-snug ${good ? 'text-good' : 'text-danger'}`}>{line}</p>
        {story.attack !== undefined && story.defence !== undefined ? (
          <p className={figureLabelClass('note')}>
            {tf('story.colony.forces', { attack: <Figure accent="note">{story.attack}</Figure>, defence: <Figure accent="note">{story.defence}</Figure> })}
          </p>
        ) : null}
        {/* Путь назад (пункт 37): что нужно, чтобы отбить, — ответ «что теперь», а не «почему». */}
        {story.retake ? <p className="text-16 leading-snug text-lamp">{story.retake}</p> : null}
      </div>
    </>
  );
}

/**
 * Находка — backlog-promo, пункт 33: планета крупно, и что на ней лежит. Награда, которая
 * случилась на глазах, держит сильнее строки в итогах; что она даёт, сказано тут же.
 */
function FindStory({ story, planet }: { story: Extract<Story, { kind: 'find' }>; planet?: Planet }) {
  const image = planet ? planetImage(planet.climate, planet.id) : null;
  return (
    <>
      <header className="relative flex flex-none flex-col items-center gap-1 pt-6">
        <p className="text-22 uppercase tracking-[0.2em] text-accent">{planet?.findLabel ?? t('planet.find')}</p>
        <p className="text-14 text-ink-soft">{t('story.find.subtitle', { system: story.system, planet: story.planet })}</p>
      </header>
      <div className="relative flex min-h-0 flex-1 items-center justify-center">
        <div className="aspect-square h-[55%]">
          {image ? (
            <img src={image} alt={story.planet} className="h-full w-full rounded-full object-cover" />
          ) : (
            <div className="h-full w-full rounded-full" style={{ background: climateColor(planet?.climate ?? '') }} />
          )}
        </div>
      </div>
      <p className="relative flex-none px-6 pb-4 text-center text-18 leading-snug text-ink-bright">
        {t(`story.find.${planet?.find ?? 'GOLD_DEPOSITS'}` as Key)}
      </p>
    </>
  );
}

/** Трофей — пункт 33: сколько кораблей взято абордажем и что с ними можно сделать. */
function TrophyStory({ story }: { story: Extract<Story, { kind: 'trophy' }> }) {
  return (
    <>
      <header className="relative flex flex-none justify-center pt-6">
        <p className="text-22 uppercase tracking-[0.2em] text-accent">{t('story.trophy.title')}</p>
      </header>
      <div className="relative flex min-h-0 flex-1 items-center justify-center">
        <p className="text-[6rem] text-lamp" aria-hidden>⚓</p>
      </div>
      <p className="relative flex-none px-6 pb-4 text-center text-18 leading-snug text-ink-bright">
        {t('story.trophy.text', { n: story.ships })}
      </p>
    </>
  );
}

/** Технологии карточками с описанием — общее у наследия Стражей и наследия ушедших. */
function TechCardsStory({ title, subtitle, technologies, tree }: {
  title: string; subtitle: string; technologies: string[]; tree: ResearchTree | null;
}) {
  return (
    <>
      <header className="relative flex flex-none flex-col items-center gap-1 pt-6">
        <p className="text-22 uppercase tracking-[0.2em] text-accent">{title}</p>
        <p className="text-14 text-ink-soft">{subtitle}</p>
      </header>
      <div className="relative flex min-h-0 flex-1 flex-wrap content-center items-center justify-center gap-4 overflow-y-auto p-6">
        {technologies.map((code) => {
          const technology = findTechnology(tree, code);
          return (
            <div key={code} className="panel w-[min(22rem,90vw)] border-2 border-space-600 p-4">
              <p className="mb-2 text-center text-18 text-accent">{technology?.name ?? code}</p>
              <p className="text-14 leading-relaxed text-ink">
                {technology?.description ?? t('espionage.stolen.noDescription')}
              </p>
            </div>
          );
        })}
      </div>
    </>
  );
}

/** Наследие Стражей: технологии карточками с описанием, как у сцены кражи. */
function GuardiansStory({ story, tree }: { story: Extract<Story, { kind: 'guardians' }>; tree: ResearchTree | null }) {
  return (
    <>
      <header className="relative flex flex-none flex-col items-center gap-1 pt-6">
        <p className="text-22 uppercase tracking-[0.2em] text-accent">{t('story.guardians.title')}</p>
        <p className="text-14 text-ink-soft">{t('story.guardians.subtitle', { system: story.system })}</p>
        {/* Сквозная история мира (пункт 43): кто такие Стражи — открывается здесь, кладом Тессара. */}
        <p className="max-w-[min(44rem,90vw)] text-center text-13 italic leading-snug text-ink-dim">{t('story.guardians.lore')}</p>
        {/* Сколько даёт клад, оригинал не публиковал: три технологии — наше число
            (SpecialStarReward), и сказано оно здесь, где награду и получают; пункт 12. */}
        <RuleNote className="text-13" hint="rule.guardiansReward" text={t('rule.guardiansReward')} />
      </header>
      <div className="relative flex min-h-0 flex-1 flex-wrap content-center items-center justify-center gap-4 overflow-y-auto p-6">
        {story.technologies.map((code) => {
          const technology = findTechnology(tree, code);
          return (
            <div key={code} className="panel w-[min(22rem,90vw)] border-2 border-space-600 p-4">
              <p className="mb-2 text-center text-18 text-accent">{technology?.name ?? code}</p>
              <p className="text-14 leading-relaxed text-ink">
                {technology?.description ?? t('espionage.stolen.noDescription')}
              </p>
            </div>
          );
        })}
      </div>
    </>
  );
}
