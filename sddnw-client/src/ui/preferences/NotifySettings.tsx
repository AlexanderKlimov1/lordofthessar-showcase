import { useEffect, useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import type { NotifySettingsState } from '../../api/types';
import { readAccount, writeAccount } from '../../state/session';
import { t, useT } from '../../i18n';
import {
  disableNotify, enableNotify, notifyAvailable, notifyEnabled, pushActive, pushAvailable, subscribePush,
  unsubscribePush,
} from '../notify/turnNotify';

/**
 * Раздел «Уведомления» — field-backlog, пункты 22, 113 и 178: согласие на зов вне игры в партии с
 * людьми. Всё выключено по умолчанию; отказ браузера и отсутствие почты сказаны словами, а не
 * молчаливой галочкой.
 *
 * Два согласия, и они разные: уведомление браузера даётся ЭТОЙ машине (браузер спрашивает
 * разрешения сам) и доходит и до закрытой вкладки, если браузер умеет Web Push; письмо — учётной
 * записи, на подтверждённую почту, одно на пропущенную цепочку ходов, с отпиской в самом письме.
 */
export function NotifySettings() {
  useT();
  const [browserOn, setBrowserOn] = useState(notifyEnabled() || pushActive());
  const [denied, setDenied] = useState(false);
  const [server, setServer] = useState<NotifySettingsState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    gameApi.notifySettings().then(setServer).catch(() => setServer(null));
  }, []);

  const fail = (cause: unknown) => setError(cause instanceof GameApiError ? cause.message : String(cause));

  const toggleBrowser = async (on: boolean) => {
    setError(null);
    setBusy(true);
    try {
      if (on) {
        const granted = await enableNotify();
        setDenied(!granted);
        if (granted && server?.pushKey && pushAvailable()) {
          // Закрытую вкладку достаёт только подписка Web Push; не вышла — остаётся зов из вкладки.
          await subscribePush(server.pushKey, gameApi.subscribePush).catch(fail);
        }
        setBrowserOn(granted);
      } else {
        disableNotify();
        await unsubscribePush(gameApi.unsubscribePush);
        setBrowserOn(false);
      }
    } finally {
      setBusy(false);
    }
  };

  const toggleMail = (on: boolean) => {
    setError(null);
    setBusy(true);
    gameApi.saveNotifyMail(on)
      .then((account) => {
        const session = readAccount();
        if (session) {
          writeAccount({ ...session, account });
        }
        setServer((current) => (current ? { ...current, notifyMail: on } : current));
      })
      .catch(fail)
      .finally(() => setBusy(false));
  };

  return (
    <div className="max-w-xl space-y-4">
      <p className="text-14 text-ink-dim">{t('preferences.notify.what')}</p>

      {notifyAvailable() ? (
        <label className="flex cursor-pointer items-start gap-3 text-16 text-ink">
          <input type="checkbox" className="mt-1" checked={browserOn} disabled={busy}
                 onChange={(event) => void toggleBrowser(event.target.checked)} />
          <span>
            {t('preferences.notify.turn')}
            <span className="block text-14 text-ink-dim">
              {pushAvailable() ? t('preferences.notify.closedTab') : t('preferences.notify.openTabOnly')}
            </span>
          </span>
        </label>
      ) : (
        <p className="text-14 text-ink-dim">{t('preferences.notify.unavailable')}</p>
      )}
      {denied ? <p className="text-14 text-warn">! {t('preferences.notify.denied')}</p> : null}

      <label className={'flex items-start gap-3 text-16 ' + (server?.hasEmail ? 'cursor-pointer text-ink' : 'text-ink-off')}
             title={server && !server.hasEmail ? t('preferences.notify.mailNoEmail') : undefined}>
        <input type="checkbox" className="mt-1" checked={Boolean(server?.notifyMail)}
               disabled={busy || !server?.hasEmail} onChange={(event) => toggleMail(event.target.checked)} />
        <span>
          {t('preferences.notify.mail')}
          <span className="block text-14 text-ink-dim">
            {server && !server.hasEmail ? t('preferences.notify.mailNoEmail') : t('preferences.notify.mailHow')}
          </span>
        </span>
      </label>

      {error ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
      <p className="text-14 text-ink-soft">{t('preferences.notify.limits')}</p>
    </div>
  );
}
