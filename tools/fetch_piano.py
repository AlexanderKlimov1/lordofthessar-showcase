"""
Скачивает сэмплы рояля для музыки заставки — Salamander Grand Piano.

Рояль заставки сперва синтезировался (модель струны, затем сумма обертонов), и до звука
настоящего инструмента синтез не дотянул: хозяин проекта слышал «не совсем пианино» при
любой настройке. Поэтому вступление играет записанный рояль.

Набор: Salamander Grand Piano V3, Александр Холм (Alexander Holm), рояль Yamaha C5,
лицензия CC-BY 3.0 — использовать можно, автора указываем (`CREDITS.md` рядом с
сэмплами). Берётся облегчённая выборка, которую раздаёт Tone.js: 30 нот через малую терцию
в MP3, около 2 МБ; промежуточные ноты движок получает сдвигом высоты (`pianoSamples.ts`).

В git сэмплы не кладутся — как и запись боя на обложке: двоичным файлам в истории пока не
место. Этот скрипт достаёт их заново на любой машине:

    python tools/fetch_piano.py
"""
import os
import sys
import urllib.request

SOURCE = 'https://tonejs.github.io/audio/salamander/'
TARGET = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sddnw-client', 'public', 'audio', 'piano')
# Ноты выборки: от ля субконтроктавы до до пятой октавы через малую терцию.
NAMES = ['A0'] + [f'{name}{octave}' for octave in range(1, 8) for name in ('C', 'Ds', 'Fs', 'A')] + ['C8']

CREDITS = """# Рояль заставки

Сэмплы: **Salamander Grand Piano V3** — Alexander Holm (Александр Холм), Yamaha C5.
Лицензия: [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).
Источник выборки в MP3: <https://tonejs.github.io/audio/salamander/> (проект Tone.js).

Файлы не хранятся в git; их скачивает `python tools/fetch_piano.py`.
"""


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    os.makedirs(TARGET, exist_ok=True)
    total = 0
    for name in NAMES:
        path = os.path.join(TARGET, f'{name}.mp3')
        if os.path.exists(path) and os.path.getsize(path) > 0:
            total += os.path.getsize(path)
            continue
        with urllib.request.urlopen(SOURCE + f'{name}.mp3', timeout=30) as response:
            data = response.read()
        with open(path, 'wb') as out:
            out.write(data)
        total += len(data)
        print(f'{name}.mp3 — {len(data) // 1024} КБ')
    with open(os.path.join(TARGET, 'CREDITS.md'), 'w', encoding='utf-8', newline='\n') as out:
        out.write(CREDITS)
    print(f'готово: {len(NAMES)} нот, {total // 1024} КБ в {os.path.normpath(TARGET)}')


if __name__ == '__main__':
    main()
