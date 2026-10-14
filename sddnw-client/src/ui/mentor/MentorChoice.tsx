import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { t, useT } from '../../i18n';

/**
 * Наставник — field-backlog, пункт 184, строкой меню «Игра»: ученик сам называет, кому показать
 * свою империю (и снимает согласие тем же нажатием), а наставник отсюда открывает империи своих
 * учеников. Окна здесь нет: меню нижней полосы окон не рисует, оно их зовёт (`onStudents`).
 */
export function MentorChoice({ onStudents }: { onStudents: () => void }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const players = useGameStore((state) => state.players);
  const setLobby = useGameStore((state) => state.setLobby);
  const setError = useGameStore((state) => state.setError);
  const self = players.find((player) => player.id === credentials?.playerId);
  if (!game || !credentials || !self) {
    return null;
  }
  const others = players.filter((player) => player.playerType === 'HUMAN' && player.id !== self.id);
  const students = players.filter((player) => player.mentorPlayerId === self.id);
  const mentor = others.find((player) => player.id === self.mentorPlayerId);

  const choose = (mentorPlayerId: string | null) => {
    gameApi.chooseMentor(game.id, credentials.accessToken, mentorPlayerId)
      .then(() => gameApi.getGame(game.id))
      .then((details) => setLobby(details.game, details.players))
      .catch((failure: Error) => setError(failure.message));
  };

  return (
    <div className="space-y-1">
      <p className="text-ink-soft" title={t('mentor.what')}>
        {mentor ? t('mentor.shownTo', { name: mentor.name }) : t('mentor.title')}
      </p>
      <div className="flex flex-wrap gap-3">
        {mentor ? (
          <button type="button" className="link" onClick={() => choose(null)}>{t('mentor.stop')}</button>
        ) : (
          others.map((player) => (
            <button key={player.id} type="button" className="link" onClick={() => choose(player.id)}>
              {t('mentor.showTo', { name: player.name })}
            </button>
          ))
        )}
      </div>
      {students.length > 0 ? (
        <button type="button" className="link text-lamp" onClick={onStudents}>
          {t('mentor.students', { n: students.length })}
        </button>
      ) : null}
    </div>
  );
}
