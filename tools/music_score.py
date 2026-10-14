"""
Ноты заставки для фортепиано — из того, что движок музыки действительно сыграл.

Музыку игры сочиняет код (`sddnw-client/src/audio/`), поэтому партитуры у неё нет: её
приходится СНИМАТЬ с движка. Звуковая мастерская (или скрипт в браузере) отрисовывает трек
и пишет рядом с WAV журнал событий `menu-theme-events.json` — каждую ноту темы, линии,
арпеджио, баса и бас-гитары с её временем. Этот скрипт раскладывает журнал на две руки и
пишет MusicXML — его открывают MuseScore, Sibelius, Finale и любой нотный редактор.

Переложение, а не стенограмма: у ансамбля десяток голосов, у пианиста две руки.
* Правая рука — мелодия: тема, где она звучит; иначе высокая линия; во вступлении, где
  мелодии ещё нет, — гитарное арпеджио.
* Левая рука — гармония: аккорд пэда, сжатый в одну позицию от основного тона, чтобы его
  можно было взять одной рукой; в соло баса — сама партия бас-гитары с тейпингом.
* Тональность меняется вместе с музыкой и берётся из сценария: у каждого такта записаны
  сдвиг тоники и лад (`menuForm.ts`) — до-диез минор, ре минор после подъёма опасности на
  полтона, до-диез мажор в победе. Знаки пишутся тем, что короче: диезами или бемолями.

Запуск: python tools/music_score.py  (читает и пишет в docs/music)
"""
import json
import os
import sys
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
MUSIC = os.path.join(HERE, '..', 'docs', 'music')

SIXTEENTHS_PER_BAR = 16
DIVISIONS = 4  # шестнадцатая — единица длительности
# Длительности, которые пишутся одной нотой: шестнадцатых → (тип, точка).
NOTE_TYPES = {16: ('whole', False), 12: ('half', True), 8: ('half', False), 6: ('quarter', True),
              4: ('quarter', False), 3: ('eighth', True), 2: ('eighth', False), 1: ('16th', False)}
SHARPS = [('C', 0), ('C', 1), ('D', 0), ('D', 1), ('E', 0), ('F', 0), ('F', 1), ('G', 0), ('G', 1), ('A', 0), ('A', 1), ('B', 0)]
FLATS = [('C', 0), ('D', -1), ('D', 0), ('E', -1), ('E', 0), ('F', 0), ('G', -1), ('G', 0), ('A', -1), ('A', 0), ('B', -1), ('B', 0)]
# Мажорная тональность от тоники (класс высоты) → квинты в ключе: из двух записей та, где
# знаков меньше (ре-бемоль мажор, а не до-диез мажор).
MAJOR_FIFTHS = {0: 0, 1: -5, 2: 2, 3: -3, 4: 4, 5: -1, 6: -6, 7: 1, 8: -4, 9: 3, 10: -2, 11: 5}
SECTION_TITLES = {
    'theme': 'Тема', 'bridge:bassSolo': 'Проигрыш: соло баса (тейпинг)', 'bridge:march': 'Марш',
    'bridge:rise': 'Подъём', 'bridge:climax': 'Кульминация', 'bridge:resolve': 'Развязка',
    'unknown': 'Неизвестное', 'curiosity': 'Интерес', 'danger': 'Опасность',
    'bassSolo': 'Соло баса (тейпинг)', 'march': 'Марш', 'rise': 'Подъём', 'victory': 'Победа', 'final': 'Финал',
}


def key_of(tonic, mode):
    """Тональность такта: квинты, лад и чем писать знаки. Минор — по параллельному мажору."""
    fifths = MAJOR_FIFTHS[(tonic + (3 if mode == 'minor' else 0)) % 12]
    return fifths, mode, FLATS if fifths < 0 else SHARPS


def split_duration(length):
    """Длительность в шестнадцатых → части, которые пишутся одной нотой (связываются лигой)."""
    parts = []
    for value in sorted(NOTE_TYPES, reverse=True):
        while length >= value:
            parts.append(value)
            length -= value
    return parts


def pitch_xml(midi, spelling):
    step, alter = spelling[midi % 12]
    octave = midi // 12 - 1
    alter_xml = f'<alter>{alter}</alter>' if alter else ''
    return f'<pitch><step>{step}</step>{alter_xml}<octave>{octave}</octave></pitch>'


def notes_xml(events, staff, voice, spelling):
    """
    Голос такта: события (начало, длина, высоты) → ноты и паузы MusicXML. Длительности, не
    пишущиеся одной нотой, разбиваются и связываются лигой; аккорд — ноты с <chord/>.
    """
    out = []
    position = 0
    for start, length, pitches, tie_in, tie_out in events:
        if start > position:
            out += [rest_xml(part, staff, voice) for part in split_duration(start - position)]
        parts = split_duration(length)
        for index, part in enumerate(parts):
            note_type, dotted = NOTE_TYPES[part]
            starts_tie = index < len(parts) - 1 or tie_out
            stops_tie = index > 0 or tie_in
            for order, midi in enumerate(pitches):
                ties = ''.join([
                    '<tie type="stop"/>' if stops_tie else '',
                    '<tie type="start"/>' if starts_tie else '',
                ])
                tied = ''.join([
                    '<tied type="stop"/>' if stops_tie else '',
                    '<tied type="start"/>' if starts_tie else '',
                ])
                out.append(
                    '<note>' + ('<chord/>' if order else '') + pitch_xml(midi, spelling)
                    + f'<duration>{part}</duration>{ties}<voice>{voice}</voice><type>{note_type}</type>'
                    + ('<dot/>' if dotted else '') + f'<staff>{staff}</staff>'
                    + (f'<notations>{tied}</notations>' if tied else '') + '</note>')
        position = start + length
    if position < SIXTEENTHS_PER_BAR:
        out += [rest_xml(part, staff, voice) for part in split_duration(SIXTEENTHS_PER_BAR - position)]
    return ''.join(out)


def rest_xml(length, staff, voice):
    note_type, dotted = NOTE_TYPES[length]
    whole = ' measure="yes"' if length == SIXTEENTHS_PER_BAR else ''
    return (f'<note><rest{whole}/><duration>{length}</duration><voice>{voice}</voice>'
            f'<type>{note_type}</type>' + ('<dot/>' if dotted else '') + f'<staff>{staff}</staff></note>')


def monophonic(items, bar_start, bar_end):
    """
    Одноголосие такта: ноты по началу; каждая длится до своей длины, до следующей или до
    конца такта — что раньше. Ноты, начавшиеся в одну шестнадцатую, — одна (выше).
    """
    items = sorted(items, key=lambda item: (item[0], -item[2]))
    unique = []
    for item in items:
        if unique and unique[-1][0] == item[0]:
            continue
        unique.append(item)
    result = []
    for index, (start, length, pitch) in enumerate(unique):
        stop = min(start + max(1, length), bar_end, unique[index + 1][0] if index + 1 < len(unique) else bar_end)
        if stop > start:
            result.append((start - bar_start, stop - start, [pitch], False, False))
    return result


def closed_chord(notes):
    """Аккорд пэда, сжатый в одну позицию: основной тон между фа большой и ми малой, остальное над ним в октаве."""
    root = notes[0]
    while root < 41:
        root += 12
    while root > 52:
        root -= 12
    classes = []
    for note in notes:
        pitch_class = (note - notes[0]) % 12
        if pitch_class not in classes:
            classes.append(pitch_class)
    return sorted(root + pitch_class for pitch_class in classes)[:4]


def build(meta, first_bar=0, last_bar=None, title='', with_sections=True):
    step = 60 / meta['bpm'] / 2
    sixteenth = step / 2
    to16 = lambda seconds: int(round(seconds / sixteenth))
    events = meta['events']
    # До ближайшей тактовой черты: запись кончается на доли позже последнего такта, и
    # округление вверх давало пустой такт в конце.
    total_bars = int(round(to16(meta['end']) / SIXTEENTHS_PER_BAR))
    last_bar = total_bars if last_bar is None else last_bar
    sections_at = {}
    for section in meta['sections']:
        sections_at.setdefault(to16(section['time']) // SIXTEENTHS_PER_BAR, section)
    section_at = {bar: section['kind'] for bar, section in sections_at.items()}

    def section_of(bar):
        for probe in range(bar, -1, -1):
            if probe in sections_at:
                return sections_at[probe]
        return {'kind': 'scene'}

    def kind_of(bar):
        return section_of(bar)['kind']

    by_type = {}
    for event in events:
        by_type.setdefault(event['t'], []).append(event)
    chords = sorted(((to16(e['time']), to16(e['time'] + e['length']), e['notes']) for e in by_type.get('chord', [])))

    measures = []
    current_key = None
    for bar in range(first_bar, last_bar):
        bar_start, bar_end = bar * SIXTEENTHS_PER_BAR, (bar + 1) * SIXTEENTHS_PER_BAR
        inside = lambda kind: [e for e in by_type.get(kind, []) if bar_start <= to16(e['time']) < bar_end]
        kind = kind_of(bar)
        section = section_of(bar)
        if 'mode' in section:
            fifths, mode, spelling = key_of(meta['root'] + section.get('shift', 0), section['mode'])
        else:
            fifths, mode, spelling = key_of(meta['root'], 'major' if kind == 'scene' else 'minor')
        key = (fifths, mode)

        # Правая рука: тема, иначе линия, иначе арпеджио.
        melody = inside('theme') or inside('lead')
        if melody:
            right = monophonic([(to16(e['time']), to16(e.get('length', step)), e['note']) for e in melody], bar_start, bar_end)
        elif kind not in ('bridge:bassSolo', 'bassSolo', 'unknown'):
            right = monophonic([(to16(e['time']), 2, e['note']) for e in inside('arp')], bar_start, bar_end)
        else:
            right = []

        # Левая рука: бас-гитара в соло, иначе аккорды пэда.
        bass_line = inside('bassGuitar')
        if bass_line:
            left = monophonic([(to16(e['time']), max(1, to16(e['length'])), e['note']) for e in bass_line], bar_start, bar_end)
        else:
            left = []
            sounding = [c for c in chords if c[0] < bar_end and c[1] > bar_start]
            for index, (start, end, notes) in enumerate(sounding):
                begin = max(start, bar_start)
                stop = min(end, bar_end, sounding[index + 1][0] if index + 1 < len(sounding) else bar_end)
                if stop > begin:
                    left.append((begin - bar_start, stop - begin, closed_chord(notes), start < bar_start, end > bar_end and stop == bar_end))

        attributes = ''
        if bar == first_bar:
            attributes = (f'<attributes><divisions>{DIVISIONS}</divisions><key><fifths>{fifths}</fifths><mode>{mode}</mode></key>'
                          '<time><beats>4</beats><beat-type>4</beat-type></time><staves>2</staves>'
                          '<clef number="1"><sign>G</sign><line>2</line></clef><clef number="2"><sign>F</sign><line>4</line></clef></attributes>')
        elif key != current_key:
            attributes = f'<attributes><key><fifths>{fifths}</fifths><mode>{mode}</mode></key></attributes>'
        current_key = key
        directions = ''
        if bar == first_bar:
            directions += (f'<direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit>'
                           f'<per-minute>{meta["bpm"]}</per-minute></metronome></direction-type><staff>1</staff>'
                           f'<sound tempo="{meta["bpm"]}"/></direction>')
        if with_sections and bar in section_at and section_at[bar] in SECTION_TITLES and section_at.get(bar - 1) != section_at[bar]:
            directions += (f'<direction placement="above"><direction-type><rehearsal>{escape(SECTION_TITLES[section_at[bar]])}'
                           f'</rehearsal></direction-type><staff>1</staff></direction>')
        if with_sections and bar == 0 and kind == 'scene':
            directions += '<direction placement="above"><direction-type><rehearsal>Вступление</rehearsal></direction-type><staff>1</staff></direction>'
        measures.append(
            f'<measure number="{bar - first_bar + 1}">{attributes}{directions}'
            + notes_xml(right, 1, 1, spelling)
            + f'<backup><duration>{SIXTEENTHS_PER_BAR}</duration></backup>'
            + notes_xml(left, 2, 5, spelling)
            + '</measure>')

    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 3.1 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">\n'
            '<score-partwise version="3.1">'
            f'<work><work-title>{escape(title)}</work-title></work>'
            '<identification><creator type="composer">Stellar Dominion</creator>'
            '<encoding><software>tools/music_score.py</software></encoding></identification>'
            '<part-list><score-part id="P1"><part-name>Фортепиано</part-name></score-part></part-list>'
            '<part id="P1">' + ''.join(measures) + '</part></score-partwise>\n')


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    with open(os.path.join(MUSIC, 'menu-theme-events.json'), encoding='utf-8') as source:
        meta = json.load(source)
    bar_seconds = 60 / meta['bpm'] * 4
    # Лейтмотив — первое полное проведение темы: «интерес» в сценарии, «тема» в старом цикле.
    theme_start = next(s['time'] for s in meta['sections'] if s['kind'] in ('curiosity', 'theme'))
    theme_bar = int(round(theme_start / bar_seconds))
    outputs = {
        'leitmotif-piano.musicxml': build(meta, theme_bar, theme_bar + 4, 'Stellar Dominion — лейтмотив', with_sections=False),
        'menu-theme-piano.musicxml': build(meta, title='Stellar Dominion — заставка'),
    }
    for name, xml in outputs.items():
        with open(os.path.join(MUSIC, name), 'w', encoding='utf-8', newline='\n') as target:
            target.write(xml)
        print(f'{name}: {xml.count("<measure ")} тактов, {xml.count("<pitch>")} нот')


if __name__ == '__main__':
    main()
