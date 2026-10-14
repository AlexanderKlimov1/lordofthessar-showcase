import { useState } from 'react';
import type { GameEnding } from '../../api/types';
import { t, useT } from '../../i18n';
import { bannerGlyph } from '../race/banners';
import { titleName } from '../history/TitlesList';

/**
 * Поделиться итогом партии — field-backlog, пункт 17: карточка картинкой для соцсетей и ссылка на
 * страницу игры. Связанность и бесплатный рассказ об игре одним движением.
 *
 * **В карточку идёт только законченное и своё**: партия кончилась, туман войны больше ничего не
 * бережёт — график мощи показывает всех, как и сама сцена конца. Раса, знамя, путь к победе,
 * число ходов, место и лучший свой проект (пункт 98).
 *
 * Картинка рисуется холстом на месте, без сервера: 1200×630 — размер превью ссылки в соцсетях.
 * Поделиться можно средствами системы (Web Share, где есть), скачать файл или скопировать ссылку.
 */
export function ShareResult({ ending }: { ending: GameEnding }) {
  useT();
  const [image, setImage] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const link = window.location.origin + '/';

  const make = () => {
    const url = drawCard(ending);
    setImage(url);
    return url;
  };

  const share = async () => {
    const url = image ?? make();
    const text = shareText(ending);
    try {
      const blob = await (await fetch(url)).blob();
      const file = new File([blob], 'lord-of-thessar.png', { type: 'image/png' });
      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], text, url: link });
        return;
      }
      if (navigator.share) {
        await navigator.share({ text, url: link });
        return;
      }
    } catch {
      // Отменённое окно системы — не ошибка: игрок передумал.
      return;
    }
    copy();
  };

  const copy = () => {
    navigator.clipboard?.writeText(`${shareText(ending)} ${link}`)
      .then(() => setNote(t('share.copied')))
      .catch(() => setNote(link));
  };

  return (
    <section className="panel">
      <div className="panel-title">{t('share.title')}</div>
      <p className="text-14 text-ink-dim">{t('share.what')}</p>
      {image ? <img src={image} alt={t('share.title')} className="mt-2 w-full max-w-xl border border-space-700" /> : null}
      <div className="mt-2 flex flex-wrap gap-4 text-14">
        <button type="button" className="link text-accent" onClick={() => void share()}>▸ {t('share.share')}</button>
        <a className="link" href={image ?? undefined} download="lord-of-thessar.png"
           onClick={(event) => {
             if (!image) {
               // Первая загрузка рисует карточку тут же и подставляет её в ссылку.
               event.currentTarget.href = make();
             }
           }}>
          ▸ {t('share.download')}
        </a>
        <button type="button" className="link" onClick={copy}>▸ {t('share.copy')}</button>
      </div>
      {note ? <p className="mt-1 text-14 text-good">✓ {note}</p> : null}
    </section>
  );
}

/** Строка рядом со ссылкой: исход, путь и ход — без чужих тайн, партия окончена. */
function shareText(ending: GameEnding): string {
  const outcome = ending.won ? t('ending.won') : ending.victoryKind === 'CONCEDED' ? t('ending.conceded') : t('ending.lost');
  return t('share.text', { outcome, kind: ending.kindLabel, turn: ending.turn, race: ending.raceName ?? '' });
}

/** Карточка 1200×630 холстом: заголовок, исход, раса со знаменем, место, лучший проект и график мощи. */
function drawCard(ending: GameEnding): string {
  const width = 1200;
  const height = 630;
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d')!;
  ctx.fillStyle = '#05070f';
  ctx.fillRect(0, 0, width, height);
  // Звёзды фона — считанный жребий от номера: карточка одна и та же при каждой отрисовке.
  for (let i = 0; i < 260; i += 1) {
    const x = (Math.sin(i * 12.9898) * 43758.5453) % 1;
    const y = (Math.sin(i * 78.233) * 12345.6789) % 1;
    ctx.fillStyle = `rgba(200,210,255,${0.15 + ((i * 37) % 60) / 100})`;
    ctx.fillRect(Math.abs(x) * width, Math.abs(y) * height, 1.5, 1.5);
  }
  const self = ending.history.find((line) => line.self);
  const lamp = '#f2c66d';

  ctx.fillStyle = '#9fb0d0';
  ctx.font = '600 26px Georgia, serif';
  ctx.fillText(t('app.name').toUpperCase(), 48, 64);
  ctx.font = '22px Georgia, serif';
  ctx.fillStyle = '#7b8aa8';
  ctx.fillText(ending.gameName, 48, 98);

  ctx.font = '600 60px Georgia, serif';
  ctx.fillStyle = ending.won ? lamp : '#e8ecf6';
  ctx.fillText(shareOutcome(ending), 48, 176);
  ctx.font = '28px Georgia, serif';
  ctx.fillStyle = '#c9d2e6';
  ctx.fillText(`${ending.kindLabel} · ${t('share.turn', { n: ending.turn })} · ${t('share.place', { place: ending.place, of: ending.empires })}`, 48, 222);

  ctx.font = '30px Georgia, serif';
  ctx.fillStyle = self?.color ?? '#c9d2e6';
  ctx.fillText(`${bannerGlyph(self?.color)} ${ending.raceName ?? self?.name ?? ''}`, 48, 290);
  if (ending.designs?.length) {
    ctx.fillStyle = '#c9d2e6';
    ctx.font = '24px Georgia, serif';
    ctx.fillText(t('share.bestDesign', { name: ending.designs[0].name, n: ending.designs[0].built }), 48, 332);
  }
  // Звания, принесённые партией (пункт 41), — справа от расы, знаком и цветом лампы.
  if (ending.titles?.length) {
    ctx.fillStyle = lamp;
    ctx.font = '24px Georgia, serif';
    ctx.textAlign = 'right';
    ctx.fillText(`✦ ${ending.titles.map(titleName).join(' · ')}`, width - 48, 290);
    ctx.textAlign = 'left';
  }

  // График мощи: все империи, своя линия толще — как на сцене конца.
  const box = { x: 48, y: 370, w: width - 96, h: 220 };
  ctx.strokeStyle = '#1e2a44';
  ctx.strokeRect(box.x, box.y, box.w, box.h);
  const points = ending.history.flatMap((line) => line.points);
  const maxTurn = Math.max(1, ...points.map((point) => point.turn));
  const maxMight = Math.max(1, ...points.map((point) => point.might));
  for (const line of [...ending.history].sort((a, b) => Number(a.self) - Number(b.self))) {
    ctx.strokeStyle = line.color;
    ctx.lineWidth = line.self ? 5 : 2;
    ctx.globalAlpha = line.self ? 1 : 0.7;
    ctx.beginPath();
    line.points.forEach((point, index) => {
      const x = box.x + (point.turn / maxTurn) * box.w;
      const y = box.y + box.h - (point.might / maxMight) * (box.h - 8);
      if (index === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    });
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
  ctx.fillStyle = '#7b8aa8';
  ctx.font = '18px Georgia, serif';
  ctx.fillText(window.location.host, width - 48 - ctx.measureText(window.location.host).width, 64);
  return canvas.toDataURL('image/png');
}

function shareOutcome(ending: GameEnding): string {
  return ending.won ? t('ending.won') : ending.victoryKind === 'CONCEDED' ? t('ending.conceded') : t('ending.lost');
}
