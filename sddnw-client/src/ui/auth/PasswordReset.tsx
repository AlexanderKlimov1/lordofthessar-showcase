import { useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import type { AccountSession } from '../../api/types';
import { t, useT } from '../../i18n';
import { writeAccount } from '../../state/session';

/**
 * «Забыли пароль?» — backlog-promo, пункт 45: почта — и ссылка уходит письмом. Ответ сервера
 * один, есть такая запись или нет: форма не должна отвечать на вопрос «зарегистрирован ли
 * адрес». Стоит под формой входа строкой, раскрывается по нажатию.
 */
export function ForgotPassword({ disabled }: { disabled: boolean }) {
  useT();
  const [open, setOpen] = useState(false);
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!open) {
    return (
      <p className="mt-2 text-11 text-ink-faint">
        <button type="button" className="link" disabled={disabled} onClick={() => setOpen(true)}>
          {t('auth.forgot.open')}
        </button>
      </p>
    );
  }
  const send = (event: React.FormEvent) => {
    event.preventDefault();
    if (!email.trim() || busy) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi.requestPasswordReset(email.trim())
      .then((answer) => setNotice(answer.notice))
      .catch((cause: unknown) => setError(cause instanceof GameApiError ? cause.message : t('auth.forgot.failed')))
      .finally(() => setBusy(false));
  };
  return (
    <form className="mt-3 space-y-2 border-t border-space-700 pt-3 text-xs" onSubmit={send}>
      <p className="text-ink-soft">{t('auth.forgot.what')}</p>
      <input
        className="field w-full"
        type="text"
        autoComplete="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        placeholder={t('auth.forgot.email')}
      />
      {notice ? <p className="text-ink">{notice}</p> : null}
      {error ? <p className="text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
      <div className="flex justify-end gap-4">
        <button type="button" className="link" onClick={() => setOpen(false)}>{t('common.close')}</button>
        <button type="submit" className="link text-accent" disabled={busy || !email.trim()}>
          ▸ {t('auth.forgot.send')}
        </button>
      </div>
    </form>
  );
}

/**
 * Новый пароль по ссылке из письма — пункт 45: ссылка `/?reset=<код>` открывает эту форму вместо
 * формы входа. Сервер гасит прежние пропуска записи и сразу выдаёт новый — игрок входит.
 */
export function ResetPassword({ token, onAuthorized, onCancel }: {
  token: string;
  onAuthorized: (session: AccountSession) => void;
  onCancel: () => void;
}) {
  useT();
  const [password, setPassword] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mismatch = again.length > 0 && again !== password;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (busy || !password || mismatch) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi.resetPassword(token, password)
      .then((session) => {
        writeAccount(session);
        onAuthorized(session);
      })
      .catch((cause: unknown) => setError(cause instanceof GameApiError ? cause.message : t('auth.forgot.failed')))
      .finally(() => setBusy(false));
  };
  return (
    <form className="space-y-3 text-xs" onSubmit={submit}>
      <p className="text-ink-soft">{t('auth.reset.what')}</p>
      <input className="field w-full" type="password" autoComplete="new-password" value={password}
             onChange={(event) => setPassword(event.target.value)} placeholder={t('auth.reset.password')} />
      <input className="field w-full" type="password" autoComplete="new-password" value={again}
             onChange={(event) => setAgain(event.target.value)} placeholder={t('auth.reset.again')} />
      {mismatch ? <p className="text-warn"><span aria-hidden className="mr-1">!</span>{t('auth.reset.mismatch')}</p> : null}
      {error ? <p className="text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
      <div className="flex justify-end gap-4">
        <button type="button" className="link" onClick={onCancel}>{t('common.close')}</button>
        <button type="submit" className="link text-accent" disabled={busy || !password || mismatch || again.length === 0}>
          ▸ {t('auth.reset.submit')}
        </button>
      </div>
    </form>
  );
}
