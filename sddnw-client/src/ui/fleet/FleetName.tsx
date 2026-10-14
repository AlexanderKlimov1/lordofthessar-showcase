import { useState } from 'react';
import type { FleetGroup } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { ActionError } from '../feedback/ActionError';

/** Предел длины — тот же, что на сервере (`FleetService.FLEET_NAME_MAX`). */
const MAX_LENGTH = 48;

/**
 * Имя и подвиги флота — field-backlog, пункт 141: «Третья ударная, семь побед».
 *
 * Кораблей поимённо нет, а флот — сущность, и сюжет партии складывается из него. Строка под
 * плашкой окна флота: подвиги (если были) и ссылка «назвать» — щелчок открывает поле, Enter
 * сохраняет, Esc отменяет. Имя — текст игрока, словарь его не трогает.
 */
export function FleetName({ fleet }: { fleet: FleetGroup }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const espionage = useGameStore((state) => state.espionage);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const won = fleet.battlesWon ?? 0;
  const lost = fleet.battlesLost ?? 0;

  const save = () => {
    if (!game || !credentials) {
      return;
    }
    setError(null);
    gameApi.renameFleet(game.id, fleet.id, credentials.accessToken, draft)
      .then(async () => {
        setEditing(false);
        setEmpire(espionage, await gameApi.getFleet(game.id, credentials.accessToken));
      })
      .catch((failure: Error) => setError(failure.message));
  };

  if (editing) {
    return (
      <div>
        <input
          autoFocus
          className="field w-full text-14"
          value={draft}
          maxLength={MAX_LENGTH}
          placeholder={t('fleetName.placeholder')}
          aria-label={t('fleetName.title')}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            // Своя клавиатура у поля: Esc отменяет правку, а не закрывает окно флота.
            event.stopPropagation();
            if (event.key === 'Enter') {
              save();
            }
            if (event.key === 'Escape') {
              setEditing(false);
            }
          }}
          onBlur={save}
        />
        {error ? <ActionError className="text-13" message={error} /> : null}
      </div>
    );
  }
  return (
    <p className={'flex items-baseline justify-center gap-3 ' + figureLabelClass('note')}>
      {won + lost > 0 ? (
        <span className="text-lamp">
          {tf('fleetName.record', { won: <Figure accent="note">{won}</Figure>, lost: <Figure accent="note">{lost}</Figure> })}
        </span>
      ) : null}
      <button
        type="button"
        className="link-quiet"
        onClick={() => {
          setDraft(fleet.name ?? '');
          setEditing(true);
        }}
      >
        ✎ {t(fleet.name ? 'fleetName.rename' : 'fleetName.name')}
      </button>
    </p>
  );
}
