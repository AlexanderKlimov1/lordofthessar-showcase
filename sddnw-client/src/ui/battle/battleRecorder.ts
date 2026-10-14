import { GifEncoder } from './gifEncoder';

/**
 * Запись своего боя в GIF — field-backlog, пункт 59: новинку перенимают, когда видят, как в неё
 * играют другие, и бой — то, что хочется показать.
 * <p>
 * <b>Только на клиенте, без сервера:</b> поле боя — один SVG (`BattleField`), и кадр снимается
 * им самим — разметка копируется, превращается в картинку и рисуется в холст. Снимок экрана
 * браузер страницам не даёт без вопроса «чем поделиться», а здесь снимать нечего, кроме своего же
 * рисунка.
 * <p>
 * <b>Анимации переносятся вычисленным стилем.</b> Вспышки, лучи и обломки движутся CSS-анимацией
 * (`sddnw-*` в `index.css`), а копия разметки помнит только начало анимации — в картинке взрыв
 * стоял бы в первом кадре навсегда. Поэтому у каждого элемента в копию переписываются ТЕКУЩИЕ
 * прозрачность, преобразование и смещение пунктира, а сама анимация снимается.
 */
export const RECORD_FPS = 10;
/** Предел записи: GIF длиннее полуминуты весит десятки мегабайт и никуда не пролезет. */
export const RECORD_LIMIT_MS = 30_000;
/** Ширина записи в точках: столько показывают чаты в ленте, крупнее — только тяжелее. */
export const RECORD_WIDTH = 640;

const ANIMATED = ['opacity', 'transform', 'transform-origin', 'transform-box', 'stroke-dashoffset'];

export class BattleRecorder {
  private encoder: GifEncoder | null = null;
  private timer: number | null = null;
  private busy = false;
  private last = 0;
  private started = 0;
  private width = 0;
  private height = 0;
  private readonly field: () => SVGSVGElement | null;
  private readonly onLimit: () => void;

  constructor(field: () => SVGSVGElement | null, onLimit: () => void) {
    this.field = field;
    this.onLimit = onLimit;
  }

  start() {
    const svg = this.field();
    if (!svg) return;
    const box = svg.viewBox.baseVal;
    this.width = RECORD_WIDTH;
    this.height = Math.round((RECORD_WIDTH * box.height) / Math.max(1, box.width));
    this.encoder = new GifEncoder(this.width, this.height);
    this.started = performance.now();
    this.last = this.started;
    this.timer = window.setInterval(() => void this.capture(), 1000 / RECORD_FPS);
  }

  /** Сколько уже записано, в миллисекундах. */
  elapsed() {
    return this.encoder ? performance.now() - this.started : 0;
  }

  /** Остановить и отдать файл; пустая запись — пусто. */
  async stop(): Promise<Blob | null> {
    if (this.timer !== null) window.clearInterval(this.timer);
    this.timer = null;
    while (this.busy) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    const encoder = this.encoder;
    this.encoder = null;
    return encoder && this.frames > 0 ? encoder.finish() : null;
  }

  private frames = 0;

  private async capture() {
    const svg = this.field();
    if (!svg || !this.encoder || this.busy) return;
    if (performance.now() - this.started > RECORD_LIMIT_MS) {
      this.onLimit();
      return;
    }
    this.busy = true;
    try {
      const image = await rasterize(svg, this.width, this.height);
      const canvas = document.createElement('canvas');
      canvas.width = this.width;
      canvas.height = this.height;
      const context = canvas.getContext('2d');
      if (!context || !this.encoder) return;
      context.fillStyle = '#05070f';
      context.fillRect(0, 0, this.width, this.height);
      context.drawImage(image, 0, 0, this.width, this.height);
      const now = performance.now();
      // Задержка кадра — настоящее время между снимками: медленная машина снимает реже, но
      // бой в записи идёт с той же скоростью, что и на экране.
      this.encoder.addFrame(context.getImageData(0, 0, this.width, this.height).data, now - this.last);
      this.last = now;
      this.frames++;
    } catch {
      // Кадр не собрался — пропускаем: следующий снимок через десятую долю секунды.
    } finally {
      this.busy = false;
    }
  }
}

/** Копия поля с переписанными анимациями — картинкой нужного размера. */
function rasterize(svg: SVGSVGElement, width: number, height: number): Promise<HTMLImageElement> {
  const copy = svg.cloneNode(true) as SVGSVGElement;
  const originals = [svg, ...Array.from(svg.querySelectorAll('*'))];
  const copies = [copy, ...Array.from(copy.querySelectorAll('*'))];
  originals.forEach((original, index) => {
    const target = copies[index] as SVGElement | undefined;
    if (!target || !(original instanceof SVGElement) || !original.style.animation) return;
    const computed = getComputedStyle(original);
    target.style.animation = 'none';
    for (const property of ANIMATED) {
      target.style.setProperty(property, computed.getPropertyValue(property));
    }
  });
  copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  copy.setAttribute('width', String(width));
  copy.setAttribute('height', String(height));
  copy.removeAttribute('class');
  const markup = new XMLSerializer().serializeToString(copy);
  const url = URL.createObjectURL(new Blob([markup], { type: 'image/svg+xml' }));
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('кадр боя не собрался'));
    };
    image.src = url;
  });
}

/** Отдать файл игроку — сохранить его он решает сам, браузер спросит, куда. */
export function saveGif(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
