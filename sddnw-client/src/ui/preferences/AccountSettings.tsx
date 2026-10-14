import { useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import { t, useT } from '../../i18n';
import { readAccount, clearAccount } from '../../state/session';

/**
 * Раздел «Учётная запись» настроек — backlog-promo, пункт 45: сменить пароль, сменить почту,
 * удалить запись. Пока запись бесплатна, забытый пароль — новая регистрация; когда в ней лежит
 * покупка, это потерянные деньги, а удаление записи самим игроком требуют правила о
 * персональных данных. Каждое действие спрашивает нынешний пароль.
 */
export function AccountSettings() {
  useT();
  const account = readAccount()?.account;
  if (!account || account.role === 'GUEST') {
    return <p className="text-14 text-ink-dim">{t('account.guestOnly')}</p>;
  }
  return (
    <div className="max-w-xl space-y-6">
      <p className="text-14 text-ink-dim">{t('account.what', { email: account.email ?? account.login })}</p>
      <ChangePassword />
      <ChangeEmail pending={account.pendingEmail} />
      <DeleteAccount />
    </div>
  );
}

function Result({ error, notice }: { error: string | null; notice: string | null }) {
  return (
    <>
      {error ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
      {notice ? <p className="text-14 text-good">{notice}</p> : null}
    </>
  );
}

const message = (cause: unknown): string => (cause instanceof GameApiError ? cause.message : t('account.failed'));

function ChangePassword() {
  const [password, setPassword] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi.changePassword(password, next)
      .then(() => {
        setNotice(t('account.password.done'));
        setPassword('');
        setNext('');
      })
      .catch((cause: unknown) => setError(message(cause)))
      .finally(() => setBusy(false));
  };
  return (
    <form className="space-y-2" onSubmit={submit}>
      <h3 className="text-16 text-ink-bright">{t('account.password.title')}</h3>
      <input className="field w-full" type="password" autoComplete="current-password" value={password}
             placeholder={t('account.password.current')} onChange={(event) => setPassword(event.target.value)} />
      <input className="field w-full" type="password" autoComplete="new-password" value={next}
             placeholder={t('account.password.new')} onChange={(event) => setNext(event.target.value)} />
      <Result error={error} notice={notice} />
      <button type="submit" className="link text-accent" disabled={busy || !password || !next}>
        ▸ {t('account.password.submit')}
      </button>
    </form>
  );
}

function ChangeEmail({ pending }: { pending?: string }) {
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    gameApi.changeEmail(password, email)
      .then((answer) => {
        setNotice(answer.notice ?? t('account.email.sent'));
        setPassword('');
      })
      .catch((cause: unknown) => setError(message(cause)))
      .finally(() => setBusy(false));
  };
  return (
    <form className="space-y-2" onSubmit={submit}>
      <h3 className="text-16 text-ink-bright">{t('account.email.title')}</h3>
      {pending ? <p className="text-14 text-ink-soft">{t('account.email.pending', { email: pending })}</p> : null}
      <input className="field w-full" type="text" autoComplete="email" value={email}
             placeholder={t('account.email.new')} onChange={(event) => setEmail(event.target.value)} />
      <input className="field w-full" type="password" autoComplete="current-password" value={password}
             placeholder={t('account.password.current')} onChange={(event) => setPassword(event.target.value)} />
      <Result error={error} notice={notice} />
      <button type="submit" className="link text-accent" disabled={busy || !password || !email.trim()}>
        ▸ {t('account.email.submit')}
      </button>
    </form>
  );
}

function DeleteAccount() {
  const [password, setPassword] = useState('');
  const [sure, setSure] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    // Удаление в два нажатия: его не отменить.
    if (!sure) {
      setSure(true);
      return;
    }
    setBusy(true);
    setError(null);
    gameApi.deleteMyAccount(password)
      .then(() => {
        clearAccount();
        window.location.assign(window.location.pathname);
      })
      .catch((cause: unknown) => {
        setError(message(cause));
        setSure(false);
      })
      .finally(() => setBusy(false));
  };
  return (
    <form className="space-y-2" onSubmit={submit}>
      <h3 className="text-16 text-danger">{t('account.delete.title')}</h3>
      <p className="text-14 text-ink-soft">{t('account.delete.what')}</p>
      <input className="field w-full" type="password" autoComplete="current-password" value={password}
             placeholder={t('account.password.current')} onChange={(event) => setPassword(event.target.value)} />
      <Result error={error} notice={null} />
      <button type="submit" className="link text-danger" disabled={busy || !password}>
        ▸ {sure ? t('account.delete.confirm') : t('account.delete.submit')}
      </button>
    </form>
  );
}
