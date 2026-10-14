import { useState } from 'react';
import { locale, t, useT } from '../../i18n';

/**
 * «Вечер партии» — field-backlog, пункт 196 (09.10.2026, решение хозяина проекта): игрок сам
 * назначает себе время игры («по средам в 20:00») и получает файл календаря `.ics` с еженедельным
 * повтором. Обстановку задаёт игрок, а не игра: файл ставит он сам, в свой календарь, и согласие —
 * в самом нажатии. Игра при этом никого не зовёт и ничего не хранит на сервере (правила пункта
 * 178: тишина по умолчанию, без давления), а напоминание в событии не заведено — его, если нужно,
 * игрок включит в своём календаре.
 *
 * Выбор помнится в браузере: это удобство одного зрителя, а не настройка учётной записи.
 */

/** Дни недели кодами календаря (RFC 5545), с понедельника. */
const DAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const;
type Day = typeof DAYS[number];
const STORE_KEY = 'sddnw.evening';
/** Сколько длится вечер в календаре, часы. */
const HOURS = [1, 2, 3, 4];

interface Evening {
  days: Day[];
  time: string;
  hours: number;
}

const read = (): Evening => {
  try {
    const raw = window.localStorage.getItem(STORE_KEY);
    if (raw) {
      return JSON.parse(raw) as Evening;
    }
  } catch {
    // Хранилище закрыто — начнём с умолчания.
  }
  return { days: ['WE'], time: '20:00', hours: 2 };
};

/** Название дня на языке игрока: 2024-01-01 — понедельник. */
const dayName = (index: number): string =>
  new Date(2024, 0, 1 + index).toLocaleDateString(locale(), { weekday: 'short' });

const pad = (n: number) => String(n).padStart(2, '0');

/** Ближайшая дата среди выбранных дней — от неё календарь и повторяет событие. */
const firstDate = (days: Day[], time: string): Date => {
  const [h, m] = time.split(':').map(Number);
  const now = new Date();
  for (let shift = 0; shift < 8; shift++) {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() + shift, h, m);
    const code = DAYS[(date.getDay() + 6) % 7];
    if (days.includes(code) && date > now) {
      return date;
    }
  }
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + 7, h, m);
};

/** Файл календаря: одно событие с еженедельным повтором, время — местное («плавающее»). */
const calendar = (evening: Evening): string => {
  const start = firstDate(evening.days, evening.time);
  const stamp = new Date();
  const local = (date: Date) =>
    `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}T${pad(date.getHours())}${pad(date.getMinutes())}00`;
  const utc = (date: Date) =>
    `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`;
  const escape = (text: string) => text.replace(/[\\;,]/g, (c) => `\\${c}`).replace(/\n/g, '\\n');
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Lord of Thessar//Evening//EN',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:evening-${stamp.getTime()}@lord-of-thessar`,
    `DTSTAMP:${utc(stamp)}`,
    `DTSTART:${local(start)}`,
    `DURATION:PT${evening.hours}H`,
    `RRULE:FREQ=WEEKLY;BYDAY=${evening.days.join(',')}`,
    `SUMMARY:${escape(t('evening.event.title'))}`,
    `DESCRIPTION:${escape(t('evening.event.text'))}`,
    `URL:${window.location.origin}`,
    'END:VEVENT',
    'END:VCALENDAR',
    '',
  ].join('\r\n');
};

export function EveningSettings() {
  useT();
  const [evening, setEvening] = useState<Evening>(read);
  const [saved, setSaved] = useState(false);

  const change = (next: Evening) => {
    setEvening(next);
    setSaved(false);
    try {
      window.localStorage.setItem(STORE_KEY, JSON.stringify(next));
    } catch {
      // Не запомнилось — файл всё равно скачается.
    }
  };

  const toggleDay = (day: Day) =>
    change({
      ...evening,
      days: evening.days.includes(day)
        ? evening.days.filter((one) => one !== day)
        : DAYS.filter((one) => one === day || evening.days.includes(one)),
    });

  const download = () => {
    const blob = new Blob([calendar(evening)], { type: 'text/calendar;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'lord-of-thessar-evening.ics';
    link.click();
    URL.revokeObjectURL(url);
    setSaved(true);
  };

  return (
    <section className="max-w-2xl space-y-4 text-14">
      <p className="text-ink-soft">{t('evening.lead')}</p>

      <div>
        <div className="mb-1 text-12 uppercase tracking-[0.2em] text-ink-dim">{t('evening.days')}</div>
        <div className="flex flex-wrap gap-2">
          {DAYS.map((day, index) => (
            <button
              key={day}
              type="button"
              aria-pressed={evening.days.includes(day)}
              onClick={() => toggleDay(day)}
              className={'min-w-[3.5rem] border px-2 py-1 '
                + (evening.days.includes(day)
                  ? 'border-accent bg-space-700 text-accent'
                  : 'border-space-600 bg-space-900 text-ink-soft hover:text-accent')}
            >
              {dayName(index)}
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-6">
        <label className="flex flex-col gap-1">
          <span className="text-12 uppercase tracking-[0.2em] text-ink-dim">{t('evening.time')}</span>
          <input
            type="time"
            className="field w-32"
            value={evening.time}
            onChange={(event) => change({ ...evening, time: event.target.value || evening.time })}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-12 uppercase tracking-[0.2em] text-ink-dim">{t('evening.hours')}</span>
          <select
            className="field w-32"
            value={evening.hours}
            onChange={(event) => change({ ...evening, hours: Number(event.target.value) })}
          >
            {HOURS.map((hours) => (
              <option key={hours} value={hours}>{t('evening.hours.value', { n: hours })}</option>
            ))}
          </select>
        </label>
      </div>

      <div className="flex flex-wrap items-center gap-4">
        <button
          type="button"
          className="border border-accent bg-space-800 px-4 py-2 uppercase tracking-widest text-accent disabled:border-space-700 disabled:text-ink-off"
          disabled={evening.days.length === 0}
          onClick={download}
        >
          {t('evening.download')}
        </button>
        {evening.days.length === 0 ? <span className="text-12 text-ink-faint">{t('evening.noDays')}</span> : null}
        {saved ? <span className="text-12 text-good">{t('evening.saved')}</span> : null}
      </div>

      <p className="text-12 text-ink-faint">{t('evening.note')}</p>
    </section>
  );
}
