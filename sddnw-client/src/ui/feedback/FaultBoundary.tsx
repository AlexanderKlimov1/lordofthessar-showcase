import { Component, type ErrorInfo, type ReactNode } from 'react';
import { t } from '../../i18n';
import { recordFault } from '../../state/faults';
import { clearActiveSession, clearView, readActiveSession } from '../../state/session';
import { ProblemReportDialog } from './ProblemReportDialog';

/**
 * Перехватчик сбоев отрисовки — field-backlog, пункт 156 (P0).
 *
 * Без него ошибка в любом экране роняла всё дерево React, и игрок видел БЕЛЫЙ ЭКРАН: ни
 * слова, ни выхода, — а требовательный тестер, увидевший такое, решит, что игра сырая, и
 * уйдёт молча. Здесь вместо белого экрана — что случилось (сбой на экране, а не на сервере:
 * партия там цела), и три выхода: попробовать снова, вернуться в меню и сообщить о проблеме
 * (пункт 157).
 *
 * «В меню» забывает текущую партию и вид в этом браузере: если сбой рождает сам экран
 * партии, перезагрузка вернула бы игрока ровно в него. Партия на сервере не трогается —
 * вернуться в неё можно из «Ваших партий».
 *
 * Классовый компонент нарочно: перехватывать сбои отрисовки React умеет только так.
 */
export class FaultBoundary extends Component<{ children: ReactNode }, { failed: boolean; reporting: boolean }> {
  state = { failed: false, reporting: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    recordFault('render', `${error.message} ${info.componentStack?.split('\n').slice(0, 4).join(' ') ?? ''}`);
  }

  render(): ReactNode {
    if (!this.state.failed) {
      return this.props.children;
    }
    const active = readActiveSession();
    return (
      <div className="flex h-full w-full items-center justify-center bg-space-950 p-8">
        <section className="panel w-full max-w-lg">
          <div className="panel-title">{t('fault.render.title')}</div>
          <p className="mb-4 text-xs leading-relaxed text-ink">{t('fault.render.text')}</p>
          <div className="flex flex-wrap items-baseline gap-5 text-xs">
            <button type="button" className="link text-accent" onClick={() => window.location.reload()}>
              ▸ {t('fault.retry')}
            </button>
            <button
              type="button"
              className="link"
              onClick={() => {
                clearView();
                clearActiveSession();
                window.location.reload();
              }}
            >
              ▸ {t('fault.toMenu')}
            </button>
            <button type="button" className="link" onClick={() => this.setState({ reporting: true })}>
              ▸ {t('problem.open')}
            </button>
          </div>
        </section>
        {this.state.reporting ? (
          <ProblemReportDialog
            standalone
            gameId={active?.gameId}
            screen="fault"
            onClose={() => this.setState({ reporting: false })}
          />
        ) : null}
      </div>
    );
  }
}
