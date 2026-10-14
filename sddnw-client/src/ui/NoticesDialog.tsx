import { useEffect, useState, useSyncExternalStore } from 'react';
import { gameApi } from '../api/client';
import { NOTICE_KINDS, type Notice, type ProblemAnswer } from '../api/types';
import { locale, t, useT } from '../i18n';
import { useRelease } from '../state/release';
import { useModalEscape } from './useModalEscape';

/**
 * «Что нового», объявления и ответы на сообщения о проблемах — field-backlog, пункты 159,
 * 161 и 158: изменения, которые касаются игрока, не происходят молча, а написавший о
 * проблеме слышит ответ.
 *
 * Спрашивает сервер один раз за открытие страницы, когда игрок уже вошёл; нового нет — окна
 * нет. Сверху — ответы на свои сообщения («ваше сообщение от 12.10 исправлено в версии
 * 0.1.1, спасибо»): это разговор с ним лично, и он важнее общего списка. Ниже — «что нового»
 * по разделам: потерянное и что делать, правила, цены сторон рас, новое, исправленное.
 * «Понятно» отмечает прочитанным в учётной записи, и на другой машине окно уже не всплывёт.
 * Esc — тоже «понятно»: держать человека в окне, которое он прочитал, незачем.
 */
/*
  Занято ли место окна объявлений — field-backlog, пункт 108: сводка «пока вас не было» ждёт,
  пока объявления не будут закрыты (или окажутся пусты), — окна за входом показываются по
  одному, иначе два модальных спорят за Esc и лежат друг на друге.
*/
let noticesBusy = false;
const busyListeners = new Set<() => void>();
const setNoticesBusy = (busy: boolean) => {
  if (noticesBusy !== busy) {
    noticesBusy = busy;
    busyListeners.forEach((listener) => listener());
  }
};

/** Открыто ли (или ещё грузится) окно объявлений и ответов на сообщения о проблемах. */
export const useNoticesBusy = (): boolean =>
  useSyncExternalStore(
    (listener) => {
      busyListeners.add(listener);
      return () => busyListeners.delete(listener);
    },
    () => noticesBusy,
  );

export function NoticesDialog({ signedIn }: { signedIn: boolean }) {
  useT();
  const release = useRelease();
  const [notices, setNotices] = useState<Notice[] | null>(null);
  const [answers, setAnswers] = useState<ProblemAnswer[] | null>(null);
  const [asked, setAsked] = useState(false);

  useEffect(() => {
    if (!signedIn || asked) {
      return;
    }
    setAsked(true);
    gameApi.unseenNotices().then(setNotices).catch(() => setNotices([]));
    gameApi.problemAnswers().then(setAnswers).catch(() => setAnswers([]));
  }, [signedIn, asked]);

  const open = (notices?.length ?? 0) + (answers?.length ?? 0) > 0;
  const loading = signedIn && (notices === null || answers === null);
  useEffect(() => {
    setNoticesBusy(open || loading);
  }, [open, loading]);
  useEffect(() => () => setNoticesBusy(false), []);
  const close = () => {
    if ((notices?.length ?? 0) > 0) {
      gameApi.markNoticesSeen().catch(() => undefined);
    }
    if ((answers?.length ?? 0) > 0) {
      gameApi.markProblemAnswersSeen().catch(() => undefined);
    }
    setNotices([]);
    setAnswers([]);
  };
  useModalEscape(open, close, true);

  if (!open) {
    return null;
  }
  const title = (notices?.length ?? 0) > 0
    ? (release ? t('notices.whatsNew', { version: release }) : t('notices.title'))
    : t('notices.answers');
  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-8">
      <section className="panel max-h-full w-full max-w-xl overflow-y-auto">
        <div className="panel-title">{title}</div>
        {(answers ?? []).length > 0 ? (
          <ul className="mb-4 space-y-3">
            {(answers ?? []).map((answer) => (
              <li key={answer.id} className="text-sm leading-relaxed text-ink">{answerLine(answer)}</li>
            ))}
          </ul>
        ) : null}
        <NoticeList notices={notices ?? []} />
        <button type="button" className="link text-accent" onClick={close}>
          {t('common.ok')}
        </button>
      </section>
    </div>
  );
}

/**
 * Весь список «что нового», свежее сверху, — по ссылке с номером версии в главном меню
 * (пункт 159). Учётной записи не спрашивает и ничего не отмечает: это история игры.
 */
export function WhatsNewDialog({ onClose }: { onClose: () => void }) {
  useT();
  const release = useRelease();
  const [notices, setNotices] = useState<Notice[] | null>(null);
  useModalEscape(true, onClose, true);

  useEffect(() => {
    gameApi.allNotices().then(setNotices).catch(() => setNotices([]));
  }, []);

  const versions = [...new Set((notices ?? []).map((notice) => notice.version))];
  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-8">
      <section className="panel max-h-full w-full max-w-xl overflow-y-auto">
        <div className="panel-title">{release ? t('menu.version', { version: release }) : t('notices.history')}</div>
        {notices === null ? <p className="text-sm text-ink-dim">{t('requests.loading')}</p> : null}
        {notices !== null && notices.length === 0 ? <p className="text-sm text-ink-faint">{t('notices.none')}</p> : null}
        {versions.map((version) => (
          <div key={version} className="mb-2">
            <div className="mb-2 text-xs uppercase tracking-[0.2em] text-ink-dim">
              {t('notices.versionHeading', { version })}
            </div>
            <NoticeList notices={(notices ?? []).filter((notice) => notice.version === version)} />
          </div>
        ))}
        <button type="button" className="link text-accent" onClick={onClose}>
          {t('common.ok')}
        </button>
      </section>
    </div>
  );
}

/** Записи по разделам — в порядке разделов, внутри раздела по дате. */
function NoticeList({ notices }: { notices: Notice[] }) {
  return (
    <>
      {NOTICE_KINDS.map((kind) => {
        const rows = notices.filter((notice) => notice.kind === kind);
        return rows.length === 0 ? null : (
          <div key={kind} className="mb-4">
            <div className={`mb-1 text-xs uppercase tracking-[0.15em] ${kind === 'loss' ? 'text-warn' : 'text-ink-soft'}`}>
              {t(`notices.kind.${kind}`)}
            </div>
            <ul className="space-y-2">
              {rows.map((notice) => (
                <li key={notice.id} className="text-sm leading-relaxed text-ink">
                  <span className="mr-2 text-11 text-ink-faint">{day(notice.date)}</span>
                  {notice.text}
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </>
  );
}

/** «Ваше сообщение от 12.10 («…») исправлено в версии 0.1.1 — спасибо». */
function answerLine(answer: ProblemAnswer): string {
  const params = { date: day(answer.reportedAt), text: answer.excerpt };
  if (answer.kind === 'FIXED') {
    const line = t('notices.answer.fixed', { ...params, version: answer.version ?? '' });
    return answer.text ? `${line} ${answer.text}` : line;
  }
  return t('notices.answer.wontFix', { ...params, reason: answer.text ?? '' });
}

function day(date: string): string {
  return new Date(date).toLocaleDateString(locale() === 'ru' ? 'ru-RU' : 'en-GB');
}
