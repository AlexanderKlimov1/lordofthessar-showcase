import { useEffect, useMemo, useState } from 'react';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { RaceGroup } from './RaceGroup';
import { PLACED_GROUPS, RACE_COLUMNS } from './raceColumns';
import { priceDrift, returnedPicks, spentPicks, toggleInGroup, type PriceDrift } from './racePicks';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Экран сборки своей расы — п. 7, окном race picks MOO II.
 *
 * Разметка снята со снимка оригинала (StrategyWiki, Race design options): окно, в нём
 * название расы плашкой по центру сверху, три столбца сторон и снизу полоса
 * CLEAR — PICKS — ACCEPT. Списки раскрыты всегда, выбранное помечено прямоугольной
 * кнопкой — см. `RaceGroup`.
 *
 * **Столбцы назначены таблицей** ({@link RACE_COLUMNS}), а не текут по порядку
 * справочника: слева лестницы хозяйства, посередине война с разведкой и правительства,
 * справа — ОДИН список особых способностей, в который оригинал сводит всё остальное.
 * Газетные колонки (CSS columns) раскладывали группы в порядке файла, и правительство
 * оказывалось первым в левом столбце, а восемь мелких групп — россыпью заголовков.
 *
 * Палитра — не оригинальная зелёная, а та же, что у правой панели карты: поле
 * `space-900`, разделители `space-700`, заголовки акцентом, нижняя полоса `space-800`,
 * как у кнопки хода. Экраны игры стоят рядом, и разной палитрой они читались бы как
 * два разных приложения.
 *
 * Счётчика SCORE из оригинала здесь нет: он показывает силу расы в процентах, а формулы
 * оценки у игры пока нет — писать в рамку выдуманное число хуже, чем не писать ничего.
 * Точка расширения: оценка встанет рядом с очками.
 */
export function RacePicksScreen({
  raceName,
  traits: initialTraits,
  basedOn,
  confirmLabel,
  saved,
  onAccept,
  onBack,
}: {
  /**
   * Своя раса из записи — пункт 176: цены сторон и свободные очки на миг сохранения. По ним
   * подешевевшие стороны горят зелёным, подорожавшие красным, а счётчик очков — зелёным, если
   * балансировка расе прибавила свободных очков, и красным, если убавила.
   */
  saved?: { prices: Record<string, number>; free: number; templateId?: string };
  /**
   * От какой готовой расы начата сборка — field-backlog, пункт 123; пусто — с чистого листа.
   * Готовая раса может стоить больше бюджета, и тогда экран говорит словами, сколько снять.
   */
  basedOn?: string;
  /** Название расы: в оригинале оно стоит в рамке над столбцами. */
  raceName: string;
  traits: string[];
  /** Подпись ACCEPT: экран заканчивает и создание партии, и вход в чужую. */
  confirmLabel: string;
  onAccept: (traits: string[]) => void;
  onBack: () => void;
}) {
  const design = useGameStore((state) => state.raceDesign);
  const [traits, setTraits] = useState<string[]>(initialTraits);
  const [drift, setDrift] = useState<PriceDrift | null>(null);
  const { t } = useT();
  /** Ответ на «сохранить в свои расы» — строкой у полосы. */
  const [savedNote, setSavedNote] = useState<{ ok: boolean; text: string } | null>(null);
  const saveOwn = () => {
    setSavedNote(null);
    gameApi.saveRaceTemplate({ id: saved?.templateId, name: raceName.trim() || t('race.picks.unnamed'), traits })
      .then(() => setSavedNote({ ok: true, text: t('race.saved.done') }))
      .catch((cause: Error) => setSavedNote({ ok: false, text: cause.message }));
  };

  /*
    Сборка не пропадает молча — field-backlog, пункт 97. Справочник рас клиент берёт при
    входе, а балансировка правит цены на лету: собранная раньше раса (экран помнит последнюю
    сборку сеанса) может оказаться дороже бюджета, а сторона — пропасть. Поэтому экран
    перечитывает справочник при открытии и, если для ЭТОЙ сборки что-то сдвинулось, говорит
    словами, что именно; исчезнувшую сторону снимает, но называет. Решает игрок.
  */
  useEffect(() => {
    let alive = true;
    gameApi.reference
      .raceTraits()
      .then((fresh) => {
        if (!alive) {
          return;
        }
        const before = useGameStore.getState().raceDesign;
        const found = priceDrift(before, fresh, initialTraits);
        useGameStore.getState().setRaceDesign(fresh);
        if (found) {
          setDrift(found);
          if (found.removed.length) {
            const known = new Set(fresh.groups.flatMap((group) => group.options.map((option) => option.code)));
            setTraits((chosen) => chosen.filter((code) => known.has(code)));
          }
        }
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
    // Только при открытии: сравнивается сборка, с которой экран открыли.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useModalEscape(true, onBack);

  const spent = useMemo(() => spentPicks(design, traits), [design, traits]);
  const returned = useMemo(() => returnedPicks(design, traits), [design, traits]);
  const budget = design?.picks ?? 0;
  const antiBudget = design?.antiPicks ?? 0;
  const left = budget - spent;
  // Своя раса из записи (пункт 176): свободных очков стало больше, чем при сохранении, — зелёный,
  // меньше — красный. Тот же сдвиг виден и по сторонам, но итог — то, что игрок почувствует.
  const freeMoved = saved === undefined || saved.free === left ? undefined
    : left > saved.free ? 'good' as const : 'danger' as const;
  // Перебрать можно двумя способами, и они независимы: потратить больше бюджета или
  // вернуть слабостями больше потолка. Вторая раса укладывается в бюджет и всё равно
  // незаконна, поэтому «Ок» гасится по обоим числам, а не по одному остатку.
  const overspent = left < 0;
  const oversold = returned > antiBudget;

  const byCode = new Map((design?.groups ?? []).map((group) => [group.code, group]));
  // Группа, которой не нашлось места в таблице столбцов, уходит в правый последней:
  // новая группа справочника должна быть видна игроку, а не пропасть молча.
  const forgotten = (design?.groups ?? []).filter((group) => !PLACED_GROUPS.has(group.code));

  const pick = (groupCode: string) => (code: string) =>
    setTraits((chosen) => toggleInGroup(design, chosen, groupCode, code));

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(96vw,72rem)] border border-space-600 bg-space-900/95 p-[2.5%] shadow-lg shadow-black/60">
        {/* Название расы плашкой по центру — заголовок окна оригинала. */}
        <h2 className="mx-auto mb-[2.5%] w-[42%] border border-space-600 bg-space-800 py-1.5 text-center text-18 uppercase tracking-[0.3em] text-accent">
          {raceName.trim() || t('race.picks.unnamed')}
        </h2>

        {design ? (
          <div className="flex items-start gap-[4%] text-13">
            {RACE_COLUMNS.map((column, index) => (
              <div key={index} className="min-w-0 flex-1">
                {column.heading ? (
                  <h3 className="mb-1 uppercase tracking-[0.2em] text-accent">
                    {byCode.get('special')?.name ?? ''}
                  </h3>
                ) : null}
                {[...column.groups, ...(index === RACE_COLUMNS.length - 1
                  ? forgotten.map((group) => group.code)
                  : [])]
                  .map((code) => byCode.get(code))
                  .filter(Boolean)
                  .map((group) => (
                    <RaceGroup
                      key={group!.code}
                      design={design}
                      group={group!}
                      traits={traits}
                      headless={Boolean(column.heading)}
                      savedPrices={saved?.prices}
                      onPick={pick(group!.code)}
                    />
                  ))}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-ink-faint">{t('race.picks.notLoaded')}</p>
        )}

      {savedNote ? (
        <p className={'mt-2 text-14 ' + (savedNote.ok ? 'text-good' : 'text-danger')}>
          {savedNote.ok ? '✓ ' : '⚠ '}{savedNote.text}
        </p>
      ) : null}
      {drift ? <DriftNote drift={drift} overspent={overspent} oversold={oversold} spent={spent} budget={budget} /> : null}
      {/*
        Своя раса от готовой — пункт 123 (и пункт 97: сборка должна удаваться). Готовые расы не
        обязаны укладываться в бюджет, поэтому перерасход называется числом и не обрезается
        молча: что снять, решает игрок.
      */}
      {basedOn && !drift ? (
        <p className={'mt-2 text-14 ' + (overspent || oversold ? 'text-warn' : 'text-ink-soft')}>
          {t('race.picks.basedOn', { name: basedOn })}{' '}
          {overspent
            ? t('race.picks.basedOn.overspent', { n: -left })
            : oversold
              ? t('race.picks.basedOn.oversold', { n: returned - antiBudget })
              : t('race.picks.basedOn.fits')}
        </p>
      ) : null}

      {/*
        Нижняя полоса оригинала: CLEAR слева, счётчик очков по центру, ACCEPT справа.
        Возврат к именам — там же слева: в оригинале отсюда некуда возвращаться, раса
        собирается в один заход, а у нас перед этим экраном стоит диалог с именами.
      */}
        <footer className="relative mt-[2.5%] flex items-center justify-between border-t border-space-700 pt-3 text-13 uppercase tracking-[0.2em] text-ink">
          <span className="flex items-center gap-3">
            <BarButton onClick={onBack}>{t('race.picks.back')}</BarButton>
            <BarButton onClick={() => setTraits([])}>{t('race.picks.clear')}</BarButton>
            {/* Своя раса — в запись (пункты 26 и 176): только законная, как и вход в партию. */}
            {overspent || oversold || traits.length === 0 ? null : (
              <BarButton onClick={saveOwn}>{saved?.templateId ? t('race.saved.update') : t('race.saved.save')}</BarButton>
            )}
          </span>

        <span className="absolute left-1/2 flex -translate-x-1/2 items-center gap-2">
          <span className={figureLabelClass('value')}>{t('race.picks.points')}</span>
          <span
            className={
              'border border-space-600 bg-space-950 px-3 py-0.5'
            }
          >
            <Figure accent="value" tone={overspent ? 'danger' : freeMoved}>{left}</Figure>
          </span>
          {freeMoved && saved ? (
            <span className={figureLabelClass('note') + (freeMoved === 'good' ? ' text-good' : ' text-danger')}
                  title={t('race.saved.freeWas', { n: saved.free })}>
              {freeMoved === 'good' ? '▲' : '▼'} {t('race.saved.wasShort', { n: saved.free })}
            </span>
          ) : null}
          <span className={figureLabelClass('value')}>{tf('race.picks.of', { n: <Figure accent="value">{budget}</Figure> })}</span>
          <span className={`ml-4 ${figureLabelClass('value')}`}>{t('race.picks.weaknesses')}</span>
          <span
            className={
              'border border-space-600 bg-space-950 px-3 py-0.5'
            }
          >
            <Figure accent="value" tone={oversold ? 'danger' : undefined}>{returned}</Figure>
          </span>
          <span className={figureLabelClass('value')}>{tf('race.picks.of', { n: <Figure accent="value">{antiBudget}</Figure> })}</span>
        </span>

        <button
          type="button"
          className={
            'border px-4 py-0.5 uppercase tracking-[0.2em] ' +
            (overspent || oversold
              ? 'pointer-events-none border-space-700 text-ink-faint'
              : 'border-space-600 text-accent hover:bg-space-700')
          }
          onClick={() => onAccept(traits)}
        >
          {confirmLabel}
        </button>
        </footer>
      </section>
    </div>
  );
}

/** Кнопка нижней полосы: тот же вид, что у кнопок оригинала — рамка и разрядка. */
function BarButton({ children, onClick }: { children: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className="border border-space-600 px-4 py-0.5 uppercase tracking-[0.2em] hover:bg-space-700 hover:text-accent"
      onClick={onClick}
    >
      {children}
    </button>
  );
}

/**
 * Что сдвинулось в собранной расе с прошлой сборки — пункт 97: цены сторон, бюджет, пропавшие
 * стороны, — и что теперь делать, если раса вышла за бюджет. Словами и числами: «цена
 * выросла: раса стоит 32 из 30» понятно, а погасшая кнопка «Ок» без объяснения — нет.
 */
function DriftNote({
  drift,
  overspent,
  oversold,
  spent,
  budget,
}: {
  drift: PriceDrift;
  overspent: boolean;
  oversold: boolean;
  spent: number;
  budget: number;
}) {
  const parts = [
    ...drift.prices.map((row) => t('race.drift.price', { name: row.name, from: row.from, to: row.to })),
    ...drift.removed.map((name) => t('race.drift.removed', { name })),
    ...(drift.budget ? [t('race.drift.budget', drift.budget)] : []),
    ...(drift.antiBudget ? [t('race.drift.antiBudget', drift.antiBudget)] : []),
  ];
  return (
    <div className="mt-[2%] border border-warn/60 bg-space-950 px-3 py-2 text-13 leading-relaxed text-ink">
      <span className="text-warn">⚠ {t('race.drift.title')}</span> {parts.join('; ')}.
      {overspent ? ' ' + t('race.drift.overspent', { spent, budget }) : ''}
      {!overspent && oversold ? ' ' + t('race.drift.oversold') : ''}
    </div>
  );
}
