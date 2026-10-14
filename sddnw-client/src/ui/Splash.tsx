import { Logo } from './brand/Logo';

/**
 * Экран ожидания между переходами: подключение, создание игры, генерация галактики.
 *
 * Ожидание показывает знак игры, медленно дышащий: пауза — часть вечера, а не поломка, и
 * пустой экран со строкой читался бы как зависание.
 */
export function Splash({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4">
      <Logo variant="mark" height={3} className="sddnw-breathe" />
      <div className="text-sm uppercase tracking-[0.35em] text-accent">{title}</div>
      {hint ? <div className="text-xs text-ink-dim">{hint}</div> : null}
    </div>
  );
}
