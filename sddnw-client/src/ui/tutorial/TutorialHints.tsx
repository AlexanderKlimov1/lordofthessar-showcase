import { useEffect, useState } from 'react';
import { progressKey } from '../progressLabel';

import { useT } from '../../i18n';
import { useGameStore } from '../../state/gameStore';
import { clearTutorial, readTutorial, writeTutorial } from '../../state/session';
import { TUTORIAL_STEPS } from './tutorialSteps';
import { funnelStep } from '../../state/funnel';
import { useModalOpen } from '../useModalEscape';
import { dismissHint, isDismissed, noviceHintsOn, TUTORIAL_HINT, useHints } from '../../state/hints';

/**
 * Подсказки обучающей партии — п. 3.
 *
 * <b>Карточка, а не модальное окно.</b> Обучение ведут рядом с игрой, а не вместо неё:
 * подсказка лежит в углу над нижней полосой, игру не загораживает и Esc не отнимает — к
 * очереди модальных экранов ({@link useModalEscape}) она не подключается нарочно. Иначе
 * первое же нажатие Esc, которым закрывают колонию, закрывало бы и обучение.
 *
 * <b>Шаг двигает игрок, а не кнопка «дальше».</b> Примета каждого шага — состояние партии
 * ({@code TUTORIAL_STEPS}), и подсказка сменяется ровно тогда, когда названное сделано.
 * Пролистать обучение, ничего не сделав, нельзя — можно только закончить его целиком.
 *
 * <b>Показывается только в своей партии.</b> Обучающая партия остаётся обычной: её можно
 * бросить и завести рядом ещё одну. Поэтому в хранилище браузера лежит идентификатор
 * партии, а не признак «идёт обучение», — иначе подсказки всплыли бы в соседней игре.
 */
export function TutorialHints() {
  const { t } = useT();
  const state = useGameStore();
  const gameId = state.game?.id ?? null;

  /**
   * Что запомнено об обучении: партия, шаг и свёрнута ли карточка.
   *
   * Держится в состоянии компонента И в хранилище браузера: первое перерисовывает
   * карточку, второе переживает перезагрузку страницы. Читается один раз — дальше
   * правится здесь же.
   */
  const [saved, setSaved] = useState(() => readTutorial());
  const modal = useModalOpen();
  // Подсказки новичку выключены (ветеран, пункт 30) или карточка отклонена насовсем (пункт 130).
  useHints();
  const silenced = !noviceHintsOn() || isDismissed(TUTORIAL_HINT);
  // Раскрыта ли карточка поверх окна нажатием на строку; закрылось окно — снова свёрнута.
  const [peek, setPeek] = useState(false);
  useEffect(() => {
    if (!modal) {
      setPeek(false);
    }
  }, [modal]);

  const mine = saved !== null && gameId !== null && saved.gameId === gameId;
  const step = mine ? Math.min(saved.step, TUTORIAL_STEPS.length) : 0;
  const current = mine && step < TUTORIAL_STEPS.length ? TUTORIAL_STEPS[step] : null;
  // Шаг обучения — в воронку (field-backlog, пункт 2): на каком шаге подсказок ушли.
  useEffect(() => {
    if (mine) {
      funnelStep('tutorial.step', { gameId, detail: `step-${step}` });
    }
  }, [mine, gameId, step]);

  /*
    Шаг закрывается сам, как только его примета сбылась. Смотрим на КАЖДОЕ изменение
    состояния: действие игрока меняет хранилище (выбрал науку, открыл колонию, закончил
    ход), и подсказка обязана уступить место следующей в тот же миг, а не после нажатия.

    Пропускаем по одному шагу за перерисовку намеренно: если игрок успел сделать сразу
    несколько дел (частое в обучении — колонию открывают до выбора науки), он увидит их
    закрытыми подряд, а не потеряет половину подсказок молча.
  */
  useEffect(() => {
    if (!mine || current === null || !current.done(state)) {
      return;
    }
    const next = { ...saved!, step: step + 1 };
    writeTutorial(next);
    setSaved(next);
  }, [mine, current, state, saved, step]);

  if (!mine || silenced) {
    return null;
  }

  const finish = () => {
    clearTutorial();
    setSaved(null);
  };

  const toggle = (hidden: boolean) => {
    const next = { ...saved!, hidden };
    writeTutorial(next);
    setSaved(next);
  };

  /*
    Свёрнутая карточка оставляет после себя язычок: обучение, которое исчезло совсем,
    игрок считает законченным и больше его не зовёт.
  */
  if (saved!.hidden) {
    return (
      <button
        type="button"
        className="link fixed bottom-24 left-6 z-[45] border border-space-600 bg-space-900/90 px-3 py-1 text-11 uppercase tracking-[0.2em]"
        onClick={() => toggle(false)}
      >
        {t('tutorial.show')}
      </button>
    );
  }

  const done = current === null;

  /*
    Поверх открытого окна — одна строка, а не карточка (backlog-promo, пункт 27): карточка в
    углу ложилась на окно системы, постройки колонии и низ экрана флота — ровно на то, что
    игрок открыл по её же совету. Строка называет шаг и раскрывается нажатием; закроется
    окно — карточка вернётся сама.
  */
  if (modal && !peek) {
    return (
      <button
        type="button"
        className="link fixed bottom-2 left-2 z-[45] max-w-[22rem] truncate border border-space-600 bg-space-900/90 px-3 py-1 text-left text-11"
        onClick={() => setPeek(true)}
      >
        <span className="uppercase tracking-[0.2em] text-accent">{t('tutorial.badge')}</span>{' '}
        {done ? t('tutorial.done.title') : t(current.title)}
      </button>
    );
  }

  /*
    СЛОЙ ВЫБРАН ТАК, ЧТОБЫ ПОДСКАЗКА БЫЛА ВИДНА ТАМ, КУДА ПОСЫЛАЕТ.

    Экраны, о которых говорят шаги, — исследования, колония, флот — лежат на z-40, и
    карточка на z-30 пропадала бы ровно в тот миг, когда игрок делает названное: он
    открывает «Науку» и остаётся на незнакомом экране без единого слова. Поэтому 45.

    Выше не надо: на z-50 и дальше лежит то, что требует решения немедленно, — итоги
    хода, встречи флотов, бой. Загораживать их подсказкой значило бы учить игрока
    закрывать обучение, не читая.
  */
  return (
    <aside className="fixed bottom-24 left-6 z-[45] w-80 border border-space-600 bg-space-900/95 p-4 shadow-lg">
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <span className="text-11 uppercase tracking-[0.2em] text-accent">
          {t('tutorial.badge')}
        </span>
        <span className="text-11 text-ink-faint">
          {done
            ? t('tutorial.done.badge')
            : (() => {
                // До половины — «шаг N из M», с половины — «осталось шагов» (пункт 52). Шаги до
                // карты (раса, знамя, вход в партию) уже сделаны и считаются (пункт 53).
                const given = saved?.given ?? 0;
                const total = TUTORIAL_STEPS.length + given;
                const label = progressKey(step + given, total, { done: 'tutorial.step', left: 'tutorial.stepsLeft' });
                return t(label.key, { n: step + given + 1, left: label.left, total });
              })()}
        </span>
      </div>

      <h2 className="mb-1 text-sm font-semibold text-ink-bright">
        {done ? t('tutorial.done.title') : t(current.title)}
      </h2>
      {/* Первая карточка называет, что уже сделано до карты (пункт 53): шаги засчитаны не даром. */}
      {!done && step === 0 && (saved?.given ?? 0) > 0 ? (
        <p className="mb-1 text-xs text-good">
          ✓ {t(saved!.given! >= 3 ? 'tutorial.given.choices' : 'tutorial.given.entered')}
        </p>
      ) : null}
      <p className="text-xs leading-relaxed text-ink-soft">
        {done ? t('tutorial.done.text') : t(current.textFor?.(state) ?? current.text)}
      </p>
      {/* Выходы из выбора — только в партии обучения (field-backlog, пункт 124). */}
      {saved!.tutorial && (done || current.exit) ? (
        <p className="mt-2 text-xs leading-relaxed text-lamp">
          {t(done ? 'tutorial.exit.summary' : current.exit!)}
        </p>
      ) : null}

      <div className="mt-3 flex items-center justify-between gap-3 border-t border-space-700 pt-2">
        {done ? (
          <span />
        ) : (
          <button type="button" className="link text-11" onClick={() => toggle(true)}>
            {t('tutorial.hide')}
          </button>
        )}
        <button type="button" className="link text-11" onClick={finish}>
          {done ? t('tutorial.done.close') : t('tutorial.quit')}
        </button>
      </div>
      {/* Больше не показывать — пункт 130: запись помнит, вернуть можно в настройках. */}
      {done ? null : (
        <button type="button" className="link mt-2 text-11 text-ink-dim" onClick={() => dismissHint(TUTORIAL_HINT)}>
          {t('hint.dismiss')}
        </button>
      )}
    </aside>
  );
}
