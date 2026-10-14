import { useEffect, useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import type { GameFeedback, ProblemAnswerKind, ProblemReport, RefusalCount } from '../../api/types';
import { LIKED } from '../ending/FeedbackBox';
import { useRelease } from '../../state/release';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { locale, t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { ActionError } from './ActionError';

/**
 * Сообщения о проблемах — field-backlog, пункт 157: страница хозяина сервера.
 *
 * Сделана по образцу «Запросов на регистрацию»: это про сервер, а не про партию, и открывается
 * из главного меню, только администратору. Наверху — ждущие разбора, у каждого строка игрока,
 * где и когда, и раскрываемое «что собрал клиент» (последние сбои): по нему чаще всего и
 * видно, что сломалось. Разобранное отмечается, а не удаляется.
 *
 * Круг замыкается ответом (пункт 158): «исправлено в версии…» (по умолчанию — текущая, та,
 * что в меню) или «не будем исправлять» с причиной. Написавший прочтёт ответ один раз, при
 * следующем входе; здесь видно, прочёл ли уже.
 */
export function ProblemReportsScreen({ onClose }: { onClose: () => void }) {
  const [reports, setReports] = useState<ProblemReport[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const release = useRelease();
  // Открытая форма ответа: у какого сообщения и какой ответ, версия и слова.
  const [answering, setAnswering] = useState<{ id: string; kind: ProblemAnswerKind } | null>(null);
  const [answerVersion, setAnswerVersion] = useState('');
  const [answerText, setAnswerText] = useState('');
  const [tab, setTab] = useState<'reports' | 'refusals' | 'feedback'>('reports');
  useModalEscape(answering === null, onClose);
  useT();

  useEffect(() => {
    let cancelled = false;
    gameApi
      .listProblems()
      .then((loaded) => !cancelled && setReports(loaded))
      .catch((failure: unknown) => {
        if (!cancelled) {
          setReports([]);
          setError(failure instanceof GameApiError ? failure.message : t('problems.listFailed'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const toggle = (report: ProblemReport) => {
    setBusy(true);
    gameApi
      .resolveProblem(report.id, !report.resolved)
      .then(replace)
      .catch((failure: unknown) => setError(failure instanceof GameApiError ? failure.message : t('problems.listFailed')))
      .finally(() => setBusy(false));
  };

  const replace = (updated: ProblemReport) =>
    setReports((current) => (current ?? []).map((row) => (row.id === updated.id ? updated : row)));

  const startAnswer = (report: ProblemReport, kind: ProblemAnswerKind) => {
    setAnswering({ id: report.id, kind });
    setAnswerVersion(release ?? '');
    setAnswerText('');
  };

  const sendAnswer = (event: React.FormEvent) => {
    event.preventDefault();
    if (!answering || busy) {
      return;
    }
    setBusy(true);
    gameApi
      .answerProblem(answering.id, {
        kind: answering.kind,
        version: answering.kind === 'FIXED' ? answerVersion.trim() || undefined : undefined,
        text: answerText.trim() || undefined,
      })
      .then((updated) => {
        replace(updated);
        setAnswering(null);
      })
      .catch((failure: unknown) => setError(failure instanceof GameApiError ? failure.message : t('problems.listFailed')))
      .finally(() => setBusy(false));
  };

  const waiting = (reports ?? []).filter((report) => !report.resolved);

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900 text-xs">
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="absolute left-3 uppercase tracking-[0.2em] text-ink-dim">{t('requests.admin')}</span>
        <span className="border border-space-600 bg-space-950 px-6 py-0.5 uppercase tracking-[0.3em] text-accent">
          {t(tab === 'reports' ? 'problems.title' : tab === 'refusals' ? 'refusals.title' : 'feedback.adminTitle')}
        </span>
        {/*
          Частые отказы (пункт 77) — второй вкладкой: это та же «где игрокам плохо», только
          собранная сервером, а не написанная игроком.
        */}
        <span className="absolute right-3 flex gap-3 uppercase tracking-[0.2em]">
          <button type="button" className={tab === 'reports' ? 'text-accent' : 'link'} onClick={() => setTab('reports')}>
            {t('problems.title')}
          </button>
          <button type="button" className={tab === 'refusals' ? 'text-accent' : 'link'} onClick={() => setTab('refusals')}>
            {t('refusals.title')}
          </button>
          {/* Отзывы после партий (пункты 46 и 187) — третьей вкладкой: тот же вопрос «как игрокам». */}
          <button type="button" className={tab === 'feedback' ? 'text-accent' : 'link'} onClick={() => setTab('feedback')}>
            {t('feedback.adminTitle')}
          </button>
        </span>
      </header>

      {tab === 'feedback' ? <FeedbackList /> : tab === 'refusals' ? <RefusalList /> : (
      <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
        {reports === null ? <p className="text-ink-dim">{t('requests.loading')}</p> : null}
        {error ? <ActionError className="mb-2" message={error} /> : null}
        {reports !== null && reports.length === 0 ? <p className="text-ink-faint">{t('problems.none')}</p> : null}

        <ul className="max-w-5xl space-y-2">
          {(reports ?? []).map((report) => (
            <li key={report.id} className={`border border-space-700 px-3 py-2 ${report.resolved ? 'opacity-60' : ''}`}>
              <div className="flex items-baseline justify-between gap-4">
                <span className="text-ink-dim">
                  {when(report.createdAt)} · {report.accountName ?? '—'}
                  {report.accountRole ? ` (${report.accountRole})` : ''}
                  {report.turn !== undefined ? ` · ${t('problems.turn', { n: report.turn })}` : ''}
                  {report.screen ? ` · ${report.screen}` : ''}
                  {report.clientVersion ? ` · ${report.clientVersion}` : ''}
                </span>
                <span className="flex flex-none gap-3">
                  {report.context ? (
                    <button type="button" className="link" onClick={() => setOpen(open === report.id ? null : report.id)}>
                      {open === report.id ? t('problems.hideContext') : t('problems.showContext')}
                    </button>
                  ) : null}
                  <button type="button" className="link disabled:opacity-40" disabled={busy} onClick={() => toggle(report)}>
                    {report.resolved ? t('problems.reopen') : t('problems.resolve')}
                  </button>
                </span>
              </div>
              <p className="mt-1 whitespace-pre-wrap text-sm text-ink">{report.text}</p>
              {report.answerKind ? (
                <p className="mt-1 text-ink-soft">
                  {report.answerKind === 'FIXED'
                    ? t('problems.answered.fixed', { version: report.answerVersion ?? '' })
                    : t('problems.answered.wontFix')}
                  {report.answerText ? ` — ${report.answerText}` : ''}
                  <span className="ml-2 text-ink-faint">
                    ({report.answerSeen ? t('problems.answered.seen') : t('problems.answered.unseen')})
                  </span>
                </p>
              ) : null}
              {answering?.id === report.id ? (
                <form className="mt-2 flex flex-wrap items-center gap-2" onSubmit={sendAnswer}>
                  <span className="text-ink-dim">
                    {answering.kind === 'FIXED' ? t('problems.answer.fixedIn') : t('problems.answer.wontFixWhy')}
                  </span>
                  {answering.kind === 'FIXED' ? (
                    <input
                      className="field w-24"
                      value={answerVersion}
                      maxLength={32}
                      onChange={(event) => setAnswerVersion(event.target.value)}
                    />
                  ) : null}
                  <input
                    className="field min-w-[16rem] flex-1"
                    value={answerText}
                    maxLength={1000}
                    placeholder={answering.kind === 'FIXED' ? t('problems.answer.notePlaceholder') : ''}
                    required={answering.kind === 'WONT_FIX'}
                    autoFocus
                    onChange={(event) => setAnswerText(event.target.value)}
                    onKeyDown={(event) => event.key === 'Escape' && setAnswering(null)}
                  />
                  <button type="submit" className="link text-accent disabled:opacity-40" disabled={busy}>
                    {t('problems.answer.send')}
                  </button>
                  <button type="button" className="link" onClick={() => setAnswering(null)}>
                    {t('common.cancel')}
                  </button>
                </form>
              ) : (
                <div className="mt-1 flex gap-3">
                  <button type="button" className="link" onClick={() => startAnswer(report, 'FIXED')}>
                    {t('problems.answer.fixed')}
                  </button>
                  <button type="button" className="link" onClick={() => startAnswer(report, 'WONT_FIX')}>
                    {t('problems.answer.wontFix')}
                  </button>
                </div>
              )}
              {open === report.id && report.context ? (
                <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap bg-space-950 p-2 text-11 text-ink-soft">
                  {pretty(report.context)}
                </pre>
              ) : null}
            </li>
          ))}
        </ul>
      </div>
      )}

      <footer
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="flex flex-none items-center justify-between border-t border-space-600 bg-space-800 px-3 uppercase tracking-[0.2em] text-ink"
      >
        <button type="button" className="link" onClick={onClose}>{t('requests.back')}</button>
        <span className={`normal-case tracking-normal ${figureLabelClass('value')}`}>
          {tf('problems.waitingCount', { n: <Figure accent="value">{waiting.length}</Figure> })}
        </span>
      </footer>
    </div>
  );
}

/**
 * Частые отказы живым игрокам — field-backlog, пункт 77. Верх списка — работа для пункта 76:
 * там интерфейс позволил сделать то, чего нельзя, или не объяснил почему. Скрипты проекта в
 * счёт не идут (`RefusalCountService`).
 */
function RefusalList() {
  const [rows, setRows] = useState<RefusalCount[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useT();
  useEffect(() => {
    let cancelled = false;
    gameApi
      .listRefusals()
      .then((loaded) => !cancelled && setRows(loaded))
      .catch((failure: unknown) => {
        if (!cancelled) {
          setRows([]);
          setError(failure instanceof GameApiError ? failure.message : t('problems.listFailed'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);
  return (
    <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
      <p className="mb-2 max-w-5xl text-ink-dim">{t('refusals.what')}</p>
      {rows === null ? <p className="text-ink-dim">{t('requests.loading')}</p> : null}
      {error ? <ActionError className="mb-2" message={error} /> : null}
      {rows !== null && rows.length === 0 ? <p className="text-ink-faint">{t('refusals.none')}</p> : null}
      <table className="max-w-5xl text-left">
        <tbody>
          {(rows ?? []).map((row) => (
            <tr key={row.key} className="border-b border-space-800 align-baseline">
              <td className="py-1 pr-4 text-right"><Figure accent="value">{row.refusals}</Figure></td>
              <td className="py-1 pr-4 text-ink">{row.text}</td>
              <td className="py-1 pr-4 font-mono text-ink-faint">{row.key}</td>
              <td className="py-1 text-ink-dim">{when(row.lastAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Отзывы после партий — field-backlog, пункты 46 и 187: сверху средняя оценка и что нравится
 * больше всего (ответы пункта 187 — проверка главных видов удовольствия, пункт 78), ниже сами
 * отзывы с партией, на которой оставлены. Имени игрока нет: нужен ответ «как», а не «кто».
 */
function FeedbackList() {
  const [rows, setRows] = useState<GameFeedback[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useT();
  useEffect(() => {
    let cancelled = false;
    gameApi
      .listFeedback()
      .then((loaded) => !cancelled && setRows(loaded))
      .catch((failure: unknown) => {
        if (!cancelled) {
          setRows([]);
          setError(failure instanceof GameApiError ? failure.message : t('problems.listFailed'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);
  const list = rows ?? [];
  const average = list.length === 0 ? 0 : list.reduce((sum, row) => sum + row.rating, 0) / list.length;
  return (
    <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
      {rows === null ? <p className="text-ink-dim">{t('requests.loading')}</p> : null}
      {error ? <ActionError className="mb-2" message={error} /> : null}
      {rows !== null && rows.length === 0 ? <p className="text-ink-faint">{t('feedback.none')}</p> : null}
      {list.length > 0 ? (
        <div className="mb-3 max-w-5xl">
          <p className="text-ink">{t('feedback.average', { avg: average.toFixed(1), n: list.length })}</p>
          <p className="text-ink-dim">
            {LIKED.map((option) => `${t(option.label)}: ${list.filter((row) => row.liked === option.code).length}`)
              .join(' · ')}
          </p>
        </div>
      ) : null}
      <table className="max-w-5xl text-left">
        <tbody>
          {list.map((row, index) => (
            <tr key={index} className="border-b border-space-800 align-baseline">
              <td className="py-1 pr-4 text-lamp">{'★'.repeat(row.rating)}</td>
              <td className="py-1 pr-4 text-ink-soft">{row.liked ? t(`feedback.liked.${row.liked}` as Key) : '—'}</td>
              <td className="py-1 pr-4 text-ink">{row.comment ?? ''}</td>
              <td className="py-1 pr-4 text-ink-faint">
                {[row.outcome, row.victoryKind, row.galaxySize, row.empires, row.turn].filter((part) => part !== undefined).join(' · ')}
              </td>
              <td className="py-1 text-ink-dim">{when(row.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Когда пришло сообщение — по часам хозяина. */
function when(createdAt: string): string {
  const moment = new Date(createdAt);
  return Number.isNaN(moment.getTime()) ? '—' : moment.toLocaleString(locale() === 'ru' ? 'ru-RU' : 'en-GB');
}

/** Собранное клиентом — разложенным: одной строкой JSON его не прочесть. */
function pretty(context: string): string {
  try {
    return JSON.stringify(JSON.parse(context), null, 2);
  } catch {
    return context;
  }
}
