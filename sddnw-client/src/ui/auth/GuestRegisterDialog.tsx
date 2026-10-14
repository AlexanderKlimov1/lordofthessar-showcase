import { useEffect, useState } from 'react';
import { GameApiError, gameApi } from '../../api/client';
import type { RegistrationChallenge } from '../../api/types';
import { useT } from '../../i18n';
import { funnelStep } from '../../state/funnel';
import { useModalEscape } from '../useModalEscape';

/**
 * Гость регистрируется, НЕ ТЕРЯЯ партий, — field-backlog, пункт 1 (P0).
 *
 * Прежде путь к регистрации у гостя был один: выйти — а выход терял гостевую запись вместе
 * с партиями — и завести новую. Здесь та же запись получает почту, имя и пароль
 * (`POST /api/auth/guest/register`), а игроком становится по ссылке из письма; до ссылки
 * гость играет как играл. Поэтому окно не выходит из партии и ничем не грозит: говорить о
 * пропаже здесь нечего (field-backlog, пункт 126).
 *
 * Окно одно на оба входа — главное меню и меню «Игра»; рисует его тот, кто показывает
 * экраны поверх всего (`App`, `MainMenu`), а меню нижней полосы его только зовёт.
 */
export function GuestRegisterDialog({ onClose }: { onClose: () => void }) {
  const { t } = useT();
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [challenge, setChallenge] = useState<RegistrationChallenge | null>(null);
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  useModalEscape(true, onClose);

  /** Вопрос одноразовый: после каждой неудачной заявки берётся новый. */
  const askChallenge = () => {
    gameApi
      .registrationChallenge()
      .then((next) => setChallenge(next))
      .catch(() => setChallenge(null));
    setAnswer('');
  };
  useEffect(askChallenge, []);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .upgradeGuest(email.trim(), name.trim(), password, challenge?.id, answer.trim())
      .then((result) => {
        setNotice(result.notice);
        funnelStep('guest.register');
      })
      .catch((cause: unknown) => {
        setError(cause instanceof GameApiError ? cause.message : t('auth.error.server'));
        askChallenge();
      })
      .finally(() => setBusy(false));
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-8" onClick={onClose}>
      <section className="panel w-full max-w-md" onClick={(event) => event.stopPropagation()}>
        <div className="panel-title">{t('guestRegister.title')}</div>
        <p className="mb-3 text-xs text-ink-soft">{t('guestRegister.text')}</p>

        {error ? (
          <div className="mb-3 border border-red-800 bg-red-950/40 px-3 py-2 text-xs text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</div>
        ) : null}

        {notice ? (
          <>
            <div className="mb-3 border border-space-600 bg-space-800/60 px-3 py-2 text-xs text-ink">
              {notice} {t('guestRegister.keepPlaying')}
            </div>
            <button type="button" className="link text-xs" onClick={onClose}>
              {t('common.close')}
            </button>
          </>
        ) : (
          <form onSubmit={submit}>
            <label className="mb-3 block text-xs text-ink-soft">
              {t('auth.email.register')} <span className="text-ink-faint">{t('auth.email.register.hint')}</span>
              <input className="field mt-1" type="email" maxLength={255} autoComplete="email"
                     value={email} onChange={(event) => setEmail(event.target.value)} />
            </label>
            <label className="mb-3 block text-xs text-ink-soft">
              {t('auth.name')} <span className="text-ink-faint">{t('auth.name.hint')}</span>
              <input className="field mt-1" maxLength={64} autoComplete="nickname"
                     value={name} onChange={(event) => setName(event.target.value)} />
            </label>
            <label className="mb-4 block text-xs text-ink-soft">
              {t('auth.password')} <span className="text-ink-faint">{t('auth.password.hint')}</span>
              <input className="field mt-1" type="password" maxLength={100} autoComplete="new-password"
                     value={password} onChange={(event) => setPassword(event.target.value)} />
            </label>
            {challenge ? (
              <label className="mb-4 block text-xs text-ink-soft">
                {challenge.question} <span className="text-ink-faint">{challenge.hint}</span>
                <input className="field mt-1" maxLength={32} autoComplete="off"
                       value={answer} onChange={(event) => setAnswer(event.target.value)} />
              </label>
            ) : null}
            <div className="flex items-baseline justify-between">
              <button type="submit" className="link text-accent" disabled={busy}>
                {busy ? '…' : t('auth.submit.register')}
              </button>
              <button type="button" className="link text-xs" disabled={busy} onClick={onClose}>
                {t('common.cancel')}
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  );
}
