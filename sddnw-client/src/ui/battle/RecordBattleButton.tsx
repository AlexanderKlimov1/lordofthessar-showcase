import { useEffect, useRef, useState } from 'react';
import { t, useT } from '../../i18n';
import { BattleRecorder, RECORD_LIMIT_MS, saveGif } from './battleRecorder';

/**
 * Кнопка «записать бой» — field-backlog, пункт 59: игрок снимает свой бой в GIF и делится им.
 * Первое нажатие начинает запись, второе останавливает и отдаёт файл; запись кончается и сама,
 * дойдя до предела ({@link RECORD_LIMIT_MS}). Сервера запись не касается вовсе.
 */
export function RecordBattleButton({ field, name }: {
  field: () => SVGSVGElement | null;
  name: string;
}) {
  useT();
  const recorder = useRef<BattleRecorder | null>(null);
  const [state, setState] = useState<'idle' | 'recording' | 'saving'>('idle');
  const [seconds, setSeconds] = useState(0);

  const stop = async () => {
    const current = recorder.current;
    if (!current) return;
    recorder.current = null;
    setState('saving');
    const blob = await current.stop();
    if (blob) {
      saveGif(blob, name);
    }
    setState('idle');
  };
  const stopRef = useRef(stop);
  stopRef.current = stop;

  useEffect(() => {
    if (state !== 'recording') return undefined;
    const timer = window.setInterval(() => {
      setSeconds(Math.floor((recorder.current?.elapsed() ?? 0) / 1000));
    }, 250);
    return () => window.clearInterval(timer);
  }, [state]);

  // Ушли со сцены посреди записи — запись бросается: файл без спроса не сохраняем.
  useEffect(() => () => {
    void recorder.current?.stop();
    recorder.current = null;
  }, []);

  const start = () => {
    const next = new BattleRecorder(field, () => void stopRef.current());
    next.start();
    recorder.current = next;
    setSeconds(0);
    setState('recording');
  };

  return (
    <button
      type="button"
      data-sfx=""
      className={'absolute right-2 top-2 border px-2 py-0.5 text-12 '
        + (state === 'recording'
          ? 'border-danger bg-space-950/80 text-danger'
          : 'border-space-600 bg-space-950/70 text-ink-soft hover:text-accent')}
      disabled={state === 'saving'}
      title={t('battle.record.title', { n: RECORD_LIMIT_MS / 1000 })}
      onClick={() => (state === 'recording' ? void stop() : start())}
    >
      {state === 'recording'
        ? <><span aria-hidden className="mr-1">■</span>{t('battle.record.stop', { n: seconds })}</>
        : state === 'saving'
          ? t('battle.record.saving')
          : <><span aria-hidden className="mr-1">●</span>{t('battle.record.start')}</>}
    </button>
  );
}
