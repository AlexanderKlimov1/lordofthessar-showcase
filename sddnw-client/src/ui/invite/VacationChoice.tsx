import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { locale, t, useT } from '../../i18n';

/** Сроки отпуска на выбор, дней: больше — уже не отпуск (`VacationService.MAX_DAYS`). */
const DAYS = [1, 3, 7, 14];

/**
 * Отпуск в сетевой партии — field-backlog, пункт 177: сказать заранее, что тебя не будет N дней.
 * ИИ ведёт империю, партия не ждёт и не зовёт; вернувшийся видит, что ИИ сделал за него (журнал
 * опекуна). В отпуске здесь же — «вернуться сейчас». Окна нет: выбор — строкой в меню «Игра».
 */
export function VacationChoice() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const self = useGameStore((state) => state.players.find((player) => player.id === state.credentials?.playerId));
  const setLobby = useGameStore((state) => state.setLobby);
  const setError = useGameStore((state) => state.setError);
  if (!game || !credentials || !self) {
    return null;
  }
  const go = (days: number) => {
    gameApi.vacation(game.id, credentials.accessToken, days)
      .then((details) => setLobby(details.game, details.players))
      .catch((failure: Error) => setError(failure.message));
  };
  if (self.vacationUntil) {
    return (
      <div className="space-y-1">
        <p className="text-ink-soft">{t('vacation.until', { date: vacationDate(self.vacationUntil) })}</p>
        <button type="button" className="link" onClick={() => go(0)}>{t('vacation.back')}</button>
      </div>
    );
  }
  return (
    <div className="space-y-1">
      <p className="text-ink-soft" title={t('vacation.what')}>{t('vacation.title')}</p>
      <div className="flex flex-wrap gap-3">
        {DAYS.map((days) => (
          <button key={days} type="button" className="link" onClick={() => go(days)}>
            {t('vacation.days', { n: days })}
          </button>
        ))}
      </div>
    </div>
  );
}

/** Конец отпуска — датой и часом по часам игрока. */
export const vacationDate = (iso: string): string => {
  const moment = new Date(iso);
  return Number.isNaN(moment.getTime()) ? iso
    : moment.toLocaleString(locale() === 'ru' ? 'ru-RU' : 'en-GB', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
};
