/**
 * Кодировщик GIF — field-backlog, пункт 59: игрок записывает свой бой и делится им.
 * <p>
 * Свой, а не библиотекой: зависимости клиента ставит `setup.cmd`, а GIF — это LZW и таблица
 * цветов, сотня строк. Кадр кодируется СРАЗУ, как снят: держать в памяти двести кадров RGBA по
 * мегабайту значило бы уронить вкладку на длинном бое, а сжатый кадр весит десятки килобайт.
 * <p>
 * Цвета — своей таблицей на кадр (медианное сечение по выборке точек): у поля боя тёмный фон и
 * яркие вспышки, и общая таблица на всю запись отдала бы вспышкам первого кадра цвета, которых
 * у последнего нет. Без растра (dithering): на тёмном поле зерно растра читается шумом.
 */
export class GifEncoder {
  private readonly parts: Uint8Array[] = [];
  private readonly width: number;
  private readonly height: number;

  constructor(width: number, height: number) {
    this.width = width;
    this.height = height;
    const head: number[] = [];
    for (const char of 'GIF89a') head.push(char.charCodeAt(0));
    word(head, width);
    word(head, height);
    head.push(0x00, 0, 0); // без общей таблицы цветов: у каждого кадра своя
    // NETSCAPE2.0 — повтор без конца: запись в чате крутится, как и положено GIF.
    head.push(0x21, 0xff, 0x0b);
    for (const char of 'NETSCAPE2.0') head.push(char.charCodeAt(0));
    head.push(0x03, 0x01, 0x00, 0x00, 0x00);
    this.parts.push(Uint8Array.from(head));
  }

  /** Добавить кадр: точки RGBA размером с запись и задержка до следующего в миллисекундах. */
  addFrame(rgba: Uint8ClampedArray, delayMs: number) {
    const { palette, indices } = quantize(rgba, this.width * this.height);
    const out: number[] = [];
    // Управление кадром: задержка в сотых долях секунды, без прозрачности.
    out.push(0x21, 0xf9, 0x04, 0x00);
    word(out, Math.max(2, Math.round(delayMs / 10)));
    out.push(0x00, 0x00);
    // Кадр во всю запись со своей таблицей на 256 цветов.
    out.push(0x2c);
    word(out, 0);
    word(out, 0);
    word(out, this.width);
    word(out, this.height);
    out.push(0x80 | 0x07);
    for (let i = 0; i < 256; i++) {
      out.push(palette[i * 3] ?? 0, palette[i * 3 + 1] ?? 0, palette[i * 3 + 2] ?? 0);
    }
    out.push(8);
    const data = lzw(indices);
    for (let offset = 0; offset < data.length; offset += 255) {
      const size = Math.min(255, data.length - offset);
      out.push(size);
      for (let i = 0; i < size; i++) out.push(data[offset + i]);
    }
    out.push(0x00);
    this.parts.push(Uint8Array.from(out));
  }

  /** Готовый файл. */
  finish(): Blob {
    return new Blob([...this.parts, Uint8Array.of(0x3b)] as BlobPart[], { type: 'image/gif' });
  }
}

function word(out: number[], value: number) {
  out.push(value & 0xff, (value >> 8) & 0xff);
}

/**
 * Таблица из 256 цветов медианным сечением по выборке точек кадра и номер цвета каждой точки.
 * Поиск ближайшего цвета кэшируется по 15-битному цвету: точек сотни тысяч, а разных цветов на
 * поле боя — несколько тысяч.
 */
function quantize(rgba: Uint8ClampedArray, pixels: number) {
  const step = Math.max(1, Math.floor(pixels / 12000));
  const sample: number[][] = [];
  for (let i = 0; i < pixels; i += step) {
    sample.push([rgba[i * 4], rgba[i * 4 + 1], rgba[i * 4 + 2]]);
  }
  let boxes: number[][][] = [sample];
  while (boxes.length < 256) {
    // Режется самый вытянутый ящик по его самой длинной оси.
    let best = -1;
    let bestRange = 0;
    let bestAxis = 0;
    boxes.forEach((box, index) => {
      if (box.length < 2) return;
      for (let axis = 0; axis < 3; axis++) {
        let min = 255;
        let max = 0;
        for (const color of box) {
          if (color[axis] < min) min = color[axis];
          if (color[axis] > max) max = color[axis];
        }
        if (max - min > bestRange) {
          bestRange = max - min;
          best = index;
          bestAxis = axis;
        }
      }
    });
    if (best < 0) break;
    const box = boxes[best].sort((a, b) => a[bestAxis] - b[bestAxis]);
    const half = box.length >> 1;
    boxes = [...boxes.slice(0, best), box.slice(0, half), box.slice(half), ...boxes.slice(best + 1)];
  }
  const palette: number[] = [];
  for (const box of boxes) {
    const sum = [0, 0, 0];
    for (const color of box) {
      sum[0] += color[0];
      sum[1] += color[1];
      sum[2] += color[2];
    }
    palette.push(...sum.map((value) => Math.round(value / Math.max(1, box.length))));
  }
  const count = palette.length / 3;
  const cache = new Int16Array(32768).fill(-1);
  const indices = new Uint8Array(pixels);
  for (let i = 0; i < pixels; i++) {
    const r = rgba[i * 4];
    const g = rgba[i * 4 + 1];
    const b = rgba[i * 4 + 2];
    const key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
    let index = cache[key];
    if (index < 0) {
      let bestDistance = Infinity;
      index = 0;
      for (let c = 0; c < count; c++) {
        const dr = palette[c * 3] - r;
        const dg = palette[c * 3 + 1] - g;
        const db = palette[c * 3 + 2] - b;
        const distance = dr * dr * 2 + dg * dg * 4 + db * db * 3;
        if (distance < bestDistance) {
          bestDistance = distance;
          index = c;
        }
      }
      cache[key] = index;
    }
    indices[i] = index;
  }
  return { palette, indices };
}

/** Сжатие LZW по правилам GIF: начальный размер кода 8 бит, словарь до 4096 кодов. */
function lzw(indices: Uint8Array): number[] {
  const clear = 256;
  const end = 257;
  const out: number[] = [];
  let bits = 0;
  let buffer = 0;
  let codeSize = 9;
  let next = 258;
  let dictionary = new Map<number, number>();
  const emit = (code: number) => {
    buffer |= code << bits;
    bits += codeSize;
    while (bits >= 8) {
      out.push(buffer & 0xff);
      buffer >>>= 8;
      bits -= 8;
    }
  };
  emit(clear);
  let prefix = indices[0];
  for (let i = 1; i < indices.length; i++) {
    const symbol = indices[i];
    const key = (prefix << 8) | symbol;
    const found = dictionary.get(key);
    if (found !== undefined) {
      prefix = found;
      continue;
    }
    emit(prefix);
    if (next < 4096) {
      // Код растёт ДО того, как выдан новый номер: читатель заводит его в словарь следом.
      if (next >= 1 << codeSize) codeSize++;
      dictionary.set(key, next);
      next++;
    } else {
      emit(clear);
      dictionary = new Map();
      next = 258;
      codeSize = 9;
    }
    prefix = symbol;
  }
  emit(prefix);
  emit(end);
  if (bits > 0) out.push(buffer & 0xff);
  return out;
}
