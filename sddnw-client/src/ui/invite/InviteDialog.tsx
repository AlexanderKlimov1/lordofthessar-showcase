import { useEffect, useState } from 'react';
import type { InvitePreview } from '../../api/types';
import { gameApi } from '../../api/client';
import { clearInvite } from '../../state/invite';
import { readAccount } from '../../state/session';
import { t, useT } from '../../i18n';
import { useModalEscape } from '../useModalEscape';
import { galaxySizeName } from '../planetLabels';
import { turnTimerLabel } from '../newgame/NewGameScreen';

/**
 * Окно приглашения — field-backlog, пункты 15 и 61: пришедшему по ссылке друга говорится, куда
 * его позвали и какое место ему достанется, — в собирающейся партии или империя, которую сейчас
 * ведёт ИИ, — и срок хода с правилом «трёх сроков». Вход — одним нажатием, гостю тоже.
 * Открывает его меню (`App`), когда после входа ждёт непринятое приглашение.
 */
export function InviteDialog({ code, onJoined, onClose }: {
  code: string;
  onJoined: (gameId: string) => void;
  onClose: () => void;
}) {
  useT();
  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Выбранная империя ИИ — пункт 66; по умолчанию та, что предлагает приглашение.
  const [empireId, setEmpireId] = useState<string | null>(null);
  const close = () => {
    clearInvite();
    onClose();
  };
  useModalEscape(true, close);

  useEffect(() => {
    let alive = true;
    gameApi.invitePreview(code)
      .then((answer) => {
        if (alive) {
          setPreview(answer);
          setEmpireId(answer.empires?.find((one) => one.name === answer.empire)?.playerId ?? null);
        }
      })
      .catch((failure: Error) => { if (alive) setError(failure.message); });
    return () => { alive = false; };
  }, [code]);

  const join = () => {
    const account = readAccount()?.account;
    const name = account?.role === 'GUEST' || !account?.name
      ? t('account.guest.name', { n: account?.name ?? '' }) : account.name;
    setBusy(true);
    setError(null);
    gameApi.joinInvite(code, name, preview?.seat === 'EMPIRE' ? empireId ?? undefined : undefined)
      .then((joined) => {
        clearInvite();
        onJoined(joined.game.id);
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-space-950/80 px-4">
      <div className="panel w-full max-w-lg space-y-3">
        <div className="panel-title">{t('invite.title')}</div>
        {preview === null && !error ? <p className="text-ink-dim">{t('requests.loading')}</p> : null}
        {preview ? (
          <>
            <p className="text-18 text-ink-bright">{preview.gameName}</p>
            <p className="text-14 text-ink-soft">
              {t(preview.host ? 'invite.from' : 'invite.game', {
                host: preview.host ?? '',
                size: preview.galaxySize ? galaxySizeName(preview.galaxySize) : '',
                humans: preview.humanPlayers,
                total: preview.totalPlayers,
              })}
            </p>
            <p className="text-14 text-ink">
              {preview.seat === 'LOBBY' ? t('invite.seat.lobby')
                : preview.seat === 'EMPIRE' ? t('invite.seat.empire', { empire: preview.empire ?? '', turn: preview.turn })
                  : t('invite.seat.none')}
            </p>
            {/*
              ВЫБОР ИМПЕРИИ — field-backlog, пункт 66: пришедший берёт не «наименьшее место», а ту
              империю, которую хочет, — по колониям и по тому, воюет ли она с пригласившим. Взять
              воюющую можно: это честная партия друг против друга, но сказано это заранее.
            */}
            {preview.seat === 'EMPIRE' && (preview.empires?.length ?? 0) > 1 ? (
              <fieldset className="space-y-1">
                <legend className="text-13 text-ink-dim">{t('invite.choose')}</legend>
                {preview.empires!.map((one) => (
                  <label key={one.playerId} className="flex cursor-pointer items-baseline gap-2 text-14 text-ink">
                    <input type="radio" name="empire" checked={empireId === one.playerId}
                      onChange={() => setEmpireId(one.playerId)} />
                    <span className="inline-block h-2 w-2 shrink-0" style={{ backgroundColor: one.color }} />
                    <span>
                      {t('invite.empireLine', { name: one.name, race: one.raceName ?? '', n: one.colonies })}
                      {one.atWarWithHost ? <span className="ml-1 text-warn">! {t('invite.atWar')}</span> : null}
                    </span>
                  </label>
                ))}
              </fieldset>
            ) : null}
            <p className="text-14 text-ink-soft">
              {t('invite.clock', { time: turnTimerLabel(preview.turnSeconds) })}
              {/* Подарок обоим — за доигранную вместе партию (пункт 174): пришедший видит это до входа. */}
              <span className="block text-13 text-ink-dim">✧ {t('invite.gift')}</span>
            </p>
          </>
        ) : null}
        {error ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
        <div className="flex justify-end gap-4 pt-1">
          <button type="button" className="link" onClick={close}>{t('invite.decline')}</button>
          <button
            type="button"
            className="link text-accent disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={busy || !preview?.seat}
            onClick={join}
          >
            ▸ {t('invite.join')}
          </button>
        </div>
      </div>
    </div>
  );
}
