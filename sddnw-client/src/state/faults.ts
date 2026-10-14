/**
 * Последние сбои клиента — field-backlog, пункты 156 и 157.
 *
 * Сообщение о проблеме несёт себя само: игрок дописывает строку, а что именно сломалось,
 * говорит этот список — отказы запросов, ошибки консоли, сбои отрисовки и неперехваченные
 * обещания. Держится он в памяти страницы и короткий: нужен «что было перед жалобой», а не
 * журнал всей вечерней партии. В хранилище браузера не кладётся: после перезагрузки сбой
 * уже другой.
 */
export interface Fault {
  at: string;
  kind: 'request' | 'console' | 'render' | 'rejection' | 'error';
  text: string;
}

const LIMIT = 30;
const faults: Fault[] = [];

export const recordFault = (kind: Fault['kind'], text: string): void => {
  faults.push({ at: new Date().toISOString(), kind, text: text.slice(0, 500) });
  if (faults.length > LIMIT) {
    faults.splice(0, faults.length - LIMIT);
  }
};

export const recentFaults = (): Fault[] => [...faults];

/**
 * Ловушки сбоев на всю страницу: ошибки консоли, неперехваченные исключения и обещания.
 * Ставятся один раз при запуске (`main.tsx`). Консоль при этом печатает как печатала —
 * список лишь записывает, что в неё ушло.
 */
export const armFaultTraps = (): void => {
  const error = console.error.bind(console);
  console.error = (...args: unknown[]) => {
    recordFault('console', args.map((arg) => (arg instanceof Error ? arg.message : String(arg))).join(' '));
    error(...args);
  };
  window.addEventListener('error', (event) => recordFault('error', event.message));
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason instanceof Error ? event.reason.message : String(event.reason);
    recordFault('rejection', reason);
  });
};
