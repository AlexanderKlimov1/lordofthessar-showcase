import { useEffect, useState } from 'react';
import type { DiplomacyProposal, DiplomacyProposals } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { opensItself } from '../../state/windows';
import { useModalEscape } from '../useModalEscape';
import { t, useT, type Key } from '../../i18n';

/**
 * Предложения, ждущие вашего ответа, — field-backlog, пункт 182.
 *
 * В партии с людьми мир, договор, дань и обмен, адресованные живому игроку, решает он сам, а не
 * доверие за него. Ход считается на сервере целиком, поэтому ответ не исполняется сразу: он
 * записывается и исполняется в конце хода, как голос в совете, — и передумать можно, пока ход не
 * посчитан. Не ответил до конца следующего хода — решает доверие, как прежде; об этом сказано в
 * самом окне, чтобы молчание не выглядело отказом.
 *
 * Окно открывается само в начале хода, если ждёт хоть одно неотвеченное предложение (его можно
 * приглушить, пункт 164), а закрытое оставляет напоминание над картой — тем же узором, что голос
 * совета. Своё предложение, ждущее чужого ответа, видно строкой ниже: иначе тишина после «предложение
 * отправлено» читалась бы как потерянное действие.
 */
export function ProposalsDialog({ blocked }: { blocked: boolean }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [proposals, setProposals] = useState<DiplomacyProposals | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const running = game?.status === 'IN_PROGRESS';

  useEffect(() => {
    if (!game || !credentials || !running) {
      return;
    }
    gameApi
      .getProposals(game.id, credentials.accessToken)
      .then((found) => {
        setProposals(found);
        if (found.incoming.some((proposal) => proposal.accepted === undefined) && opensItself('proposals')) {
          setOpen(true);
        }
      })
      .catch(() => undefined);
  }, [game?.id, game?.turn, credentials?.accessToken, running]);

  const incoming = proposals?.incoming ?? [];
  const outgoing = proposals?.outgoing ?? [];
  const unanswered = incoming.filter((proposal) => proposal.accepted === undefined).length;
  const shown = open && !blocked && (incoming.length > 0 || outgoing.length > 0);
  useModalEscape(shown, () => setOpen(false));

  const answer = (proposal: DiplomacyProposal, accept: boolean) => {
    if (!game || !credentials) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .answerProposal(game.id, credentials.accessToken, proposal.id, accept)
      .then(setProposals)
      .catch((failure) => setError(failure instanceof GameApiError ? failure.message : String(failure)))
      .finally(() => setBusy(false));
  };

  if (!shown) {
    return unanswered > 0 && !blocked ? (
      <button
        type="button"
        className="fixed left-1/2 top-12 z-30 -translate-x-1/2 border border-accent bg-space-900 px-5 py-1 text-13 uppercase tracking-[0.2em] text-accent hover:text-ink-bright"
        onClick={() => setOpen(true)}
      >
        {t('proposals.reminder', { n: unanswered })}
      </button>
    ) : null;
  }

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex max-h-full w-full max-w-4xl flex-col overflow-auto">
        <h2 className="border-b border-space-700 pb-2 text-24 uppercase tracking-[0.3em] text-ink-bright">
          {t('proposals.title')}
        </h2>
        <p className="mt-2 text-16 text-ink-soft">{t('proposals.lead')}</p>
        <ul className="mt-3 divide-y divide-space-800 border-t border-space-800">
          {incoming.map((proposal) => (
            <li key={proposal.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 py-2 text-18">
              <span className="min-w-0 flex-1 text-ink">
                {t('proposals.from', { name: proposal.fromName, what: describe(proposal) })}
                <span className="block text-14 text-ink-dim">
                  {t('proposals.deadline', { turn: proposal.deadlineTurn })}
                </span>
              </span>
              <button
                type="button"
                disabled={busy}
                aria-pressed={proposal.accepted === true}
                className={proposal.accepted === true ? 'text-good' : 'link'}
                onClick={() => answer(proposal, true)}
              >
                {proposal.accepted === true ? `✓ ${t('proposals.accepted')}` : t('proposals.accept')}
              </button>
              <button
                type="button"
                disabled={busy}
                aria-pressed={proposal.accepted === false}
                className={proposal.accepted === false ? 'text-danger' : 'link'}
                onClick={() => answer(proposal, false)}
              >
                {proposal.accepted === false ? `✕ ${t('proposals.declined')}` : t('proposals.decline')}
              </button>
            </li>
          ))}
        </ul>
        {incoming.length === 0 ? <p className="mt-2 text-16 text-ink-dim">{t('proposals.none')}</p> : null}
        {outgoing.length > 0 ? (
          <section className="mt-4">
            <div className="text-16 uppercase tracking-[0.2em] text-ink-dim">{t('proposals.outgoing')}</div>
            <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
              {outgoing.map((proposal) => (
                <li key={proposal.id} className="py-1 text-16 text-ink-soft">
                  {t('proposals.to', { name: proposal.toName, what: describe(proposal), turn: proposal.deadlineTurn })}
                </li>
              ))}
            </ul>
          </section>
        ) : null}
        {error ? <p className="mt-2 text-14 text-danger">! {error}</p> : null}
        <p className="mt-3 text-14 text-ink-dim">{t('proposals.when')}</p>
        <div className="mt-4 flex justify-end border-t border-space-700 pt-2 text-20">
          <button type="button" className="link" onClick={() => setOpen(false)}>
            {t('proposals.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Что предлагают — словами: договор называется по виду, обмен — обеими технологиями. */
function describe(proposal: DiplomacyProposal): string {
  switch (proposal.action) {
    case 'PROPOSE_TREATY':
      return t('proposals.what.treaty', {
        treaty: proposal.treaty ? t(`diplomacy.treaty.${proposal.treaty}` as Key) : '',
      });
    case 'EXCHANGE_TECH':
      return t('proposals.what.exchange', {
        offered: proposal.offeredTechName ?? '',
        requested: proposal.requestedTechName ?? '',
      });
    case 'DEMAND_TRIBUTE':
      return t('proposals.what.tribute');
    default:
      return t('proposals.what.peace');
  }
}
