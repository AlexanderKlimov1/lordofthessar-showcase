import { useState } from 'react';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { inviteLink } from '../../state/invite';
import { t, useT } from '../../i18n';

/**
 * «Пригласить друга» — field-backlog, пункт 15: ссылка за этот стол. Код просится у сервера при
 * первом нажатии (он заводится лениво), ссылка показывается строкой и копируется одним нажатием.
 * Окна нет: ссылку видно там же, где её попросили, — в меню «Игра» и в лобби.
 */
export function InviteLink({ className = '' }: { className?: string }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [link, setLink] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!game || !credentials || game.status === 'FINISHED') {
    return null;
  }
  const ask = () => {
    setError(null);
    gameApi.inviteCode(game.id, credentials.accessToken)
      .then((answer) => setLink(inviteLink(answer.code)))
      .catch((failure: Error) => setError(failure.message));
  };
  const copy = () => {
    if (!link) {
      return;
    }
    void navigator.clipboard?.writeText(link).then(() => setCopied(true)).catch(() => undefined);
  };

  return (
    <div className={className}>
      {link === null ? (
        <button type="button" className="link text-left" onClick={ask}>{t('invite.make')}</button>
      ) : (
        <div className="space-y-1">
          <p className="text-ink-soft">{t('invite.share')}</p>
          <input readOnly value={link} className="field w-full text-12" onFocus={(event) => event.target.select()} />
          <button type="button" className="link" onClick={copy}>
            {copied ? t('invite.copied') : t('invite.copy')}
          </button>
        </div>
      )}
      {error ? <p className="text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
    </div>
  );
}
