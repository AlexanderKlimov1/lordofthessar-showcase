import { useState } from 'react';
import { createPortal } from 'react-dom';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { t, useT } from '../../i18n';
import { useModalEscape } from '../useModalEscape';
import { ActionError } from '../feedback/ActionError';
import { publishGameNote } from '../GameNote';

/** Пределы — те же, что на сервере (`GameNoteController.MAX_NOTES`, `MAX_LENGTH`). */
export const MAX_NOTES = 3;
const MAX_LENGTH = 200;

/** Заметки партии хранятся одной строкой сервера, по строке на заметку. */
export const splitNotes = (note: string | null | undefined): string[] =>
  (note ?? '').split('\n').map((line) => line.trim()).filter((line) => line.length > 0);

/**
 * Заметки к партии — 08.10.2026, решение хозяина проекта: до трёх строк, которые игрок пишет себе
 * сам (прежде — одна записка, field-backlog, пункт 110). Окно открывается с правой панели; правка
 * уходит на сервер кнопкой «сохранить», Esc закрывает без записи. Рисуется порталом: панель лежит
 * своим слоем, и окно внутри неё ушло бы под экраны, что лежат выше.
 */
export function NotesDialog({ notes, onClose }: { notes: string[]; onClose: () => void }) {
  useT();
  useModalEscape(true, onClose);
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [draft, setDraft] = useState<string[]>(() => [...notes]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = () => {
    if (!game || !credentials) {
      return;
    }
    setSaving(true);
    setError(null);
    gameApi.saveGameNote(game.id, credentials.accessToken, draft.map((line) => line.trim()).filter(Boolean).join('\n'))
      .then((answer) => {
        publishGameNote(game.id, answer.note ?? null);
        onClose();
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setSaving(false));
  };
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onMouseDown={onClose}>
      <div className="panel flex w-[34rem] max-w-full flex-col gap-3 p-4 shadow-lg shadow-black/60"
           role="dialog" aria-label={t('side.notes')} onMouseDown={(event) => event.stopPropagation()}>
        <div className="panel-title text-center">{t('side.notes')}</div>
        <p className="text-14 text-ink-soft">{t('note.title')}</p>
        <div className="space-y-2">
          {draft.map((line, index) => (
            <div key={index} className="flex items-center gap-2">
              <span className="text-lamp" aria-hidden>✎</span>
              <input
                autoFocus={index === draft.length - 1}
                className="field min-w-0 flex-1 text-16"
                value={line}
                maxLength={MAX_LENGTH}
                placeholder={t('note.placeholder')}
                aria-label={t('side.notes.line', { n: index + 1 })}
                onChange={(event) => setDraft(draft.map((one, at) => (at === index ? event.target.value : one)))}
                onKeyDown={(event) => {
                  // Своя клавиатура у поля: буквы не уходят горячим клавишам карты.
                  event.stopPropagation();
                  if (event.key === 'Enter') {
                    save();
                  }
                  if (event.key === 'Escape') {
                    onClose();
                  }
                }}
              />
              <button type="button" className="link hit-24 text-ink-dim" title={t('side.notes.remove')}
                      onClick={() => setDraft(draft.filter((_, at) => at !== index))}>✕</button>
            </div>
          ))}
          {draft.length === 0 ? <p className="text-16 text-ink-faint">{t('note.empty')}</p> : null}
        </div>
        {draft.length < MAX_NOTES ? (
          <button type="button" className="link self-start text-16" onClick={() => setDraft([...draft, ''])}>
            {t('side.notes.add', { n: draft.length, max: MAX_NOTES })}
          </button>
        ) : (
          <p className="text-14 text-ink-faint">{t('side.notes.full', { n: MAX_NOTES })}</p>
        )}
        {error ? <ActionError className="text-14" message={error} /> : null}
        <div className="flex justify-between">
          <button type="button" className="link text-16" onClick={onClose}>{t('common.cancel')}</button>
          <button type="button" className="link text-16 text-accent" disabled={saving} onClick={save}>{t('side.notes.save')}</button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
