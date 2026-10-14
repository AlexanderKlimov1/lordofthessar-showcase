import { useEffect, useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import { readAccount, writeAccount } from '../../state/session';
import { t, useT, type Key } from '../../i18n';
import { ProfileSettings } from '../preferences/ProfileSettings';
import { AccountSettings } from '../preferences/AccountSettings';
import { TitlesList } from '../history/TitlesList';
import type { Title } from '../../api/types';

/**
 * Учётная запись — раздел личного кабинета: кто я в игре. Уникальный игровой код (номер записи
 * по порядку заведения, выдаёт сервер), роль, права и оплата, почта, когда заведена, игровой
 * псевдоним по умолчанию — им новая партия подписывает игрока, а в самой партии имя по-прежнему
 * меняется при входе. Ниже — своё знамя и счёт по расам (пункт 144) и действия над записью:
 * пароль, почта, удаление (backlog-promo, пункт 45).
 *
 * **Оплаты в игре пока нет** (backlog-promo, пункты 40–43 ждут решения): раздел честно говорит
 * «покупок нет» и показывает действующие права записи, если они выданы.
 */
export function AccountOverview() {
  useT();
  const [account, setAccount] = useState(() => readAccount()?.account);
  const [name, setName] = useState(account?.name ?? '');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null);
  // Звания за трудное (пункт 41) — из истории партий: их считает сервер по сыгранному.
  const [titles, setTitles] = useState<Title[]>([]);
  useEffect(() => {
    gameApi.gameHistory().then((history) => setTitles(history.titles ?? [])).catch(() => undefined);
  }, []);
  // Запись в браузере могла лечь до того, как сервер стал отдавать код и дату заведения: кабинет
  // перечитывает её при открытии — показывать «—» вместо своего кода незачем.
  useEffect(() => {
    gameApi.currentAccount().then((fresh) => {
      const session = readAccount();
      if (session) {
        writeAccount({ ...session, account: fresh });
      }
      setAccount(fresh);
    }).catch(() => undefined);
  }, []);
  if (!account) {
    return null;
  }
  const guest = account.role === 'GUEST';

  const saveName = (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setNote(null);
    gameApi.saveName(name.trim())
      .then((saved) => {
        const session = readAccount();
        if (session) {
          writeAccount({ ...session, account: saved });
        }
        setAccount(saved);
        setNote({ ok: true, text: t('profile.account.nameSaved') });
      })
      .catch((cause: unknown) => setNote({ ok: false, text: cause instanceof GameApiError ? cause.message : t('account.failed') }))
      .finally(() => setBusy(false));
  };

  const rows: { label: string; value: string }[] = [
    { label: t('profile.account.code'), value: account.playerCode !== undefined ? `#${account.playerCode}` : '—' },
    { label: t('profile.account.role'), value: account.roleLabel },
    { label: t('profile.account.email'), value: guest ? t('profile.account.noEmail') : account.email ?? account.login },
    {
      label: t('profile.account.created'),
      value: account.createdAt ? new Date(account.createdAt).toLocaleDateString() : '—',
    },
    {
      label: t('profile.account.payment'),
      value: account.entitlements?.length
        ? account.entitlements.map((kind) => t(`profile.entitlement.${kind}` as Key)).join(', ')
        : t('profile.account.noPayment'),
    },
  ];

  return (
    <div className="max-w-xl space-y-6">
      <table className="text-16">
        <tbody>
          {rows.map((row) => (
            <tr key={row.label}>
              <td className="py-0.5 pr-6 text-ink-dim">{row.label}</td>
              <td className="py-0.5 text-ink-bright">{row.value}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {guest ? (
        <p className="text-14 text-ink-dim">{t('profile.account.guestName', { name: account.name })}</p>
      ) : (
        <form className="space-y-2" onSubmit={saveName}>
          <h3 className="text-16 text-ink-bright">{t('profile.account.name')}</h3>
          <p className="text-14 text-ink-dim">{t('profile.account.nameWhat')}</p>
          <input className="field w-full" type="text" maxLength={40} value={name}
                 onChange={(event) => setName(event.target.value)} />
          {note ? (
            <p className={'text-14 ' + (note.ok ? 'text-good' : 'text-danger')}>{note.ok ? '✓ ' : '⚠ '}{note.text}</p>
          ) : null}
          <button type="submit" className="link text-accent" disabled={busy || !name.trim() || name.trim() === account.name}>
            ▸ {t('profile.account.nameSubmit')}
          </button>
        </form>
      )}

      <TitlesList titles={titles} />
      <ProfileSettings />
      <AccountSettings />
    </div>
  );
}
