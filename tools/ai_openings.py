"""
Книга дебютов для сложного ИИ — field-backlog, пункт 9.

Сложный уровень не получает прибавок: он играет «как сильнейшие». Что такое «как сильнейшие»,
говорит замер, а не догадка: партии ИИ обычного уровня, у каждой империи — начало (постройки по
порядку и разделы науки по порядку прорывов) и место по мощи к последнему ходу.

Дебют — ПОРЯДОК, а не окно ходов: первые здания ИИ ставит после сороковых ходов (первый прорыв в
науке — около 14-го, второй — около 33-го), и окно «первые 40 ходов» оставляло книгу без единой
постройки (первый замер 08.10.2026). Берутся первые BUILDINGS построек и первые SECTIONS прорывов.

Расы за столом назначает инструмент (`raceCode` в `aiEmpires`, раса наблюдателя — `raceCode`
партии), по кругу из всех готовых: раздача по порядку справочника сажала бы за стол всегда одни
и те же четыре расы, и книга знала бы только их. Книга расы — начало империй ЭТОЙ расы, занявших первое место:

* постройки — те, что поставила хотя бы половина победителей, по среднему месту в их порядке;
* разделы науки — по позициям: N-й прорыв идёт туда, куда чаще всего шёл N-й прорыв победителей.

Расе с малым числом побед (меньше MIN_WINNERS) своя запись не пишется — ей достаётся общее начало
всех победителей (`default`). Книга ложится в resources/Ai/ai-openings.json, её читает сервер
(`AiOpeningBook`) на лету. Пока идёт балансовый прогон, файлы в resources/ не трогаются — инструмент
спрашивает пульт и без `--force` не пишет.

    python tools/ai_openings.py [--games 48] [--turns 120] [--empires 4] [--workers 4]
    python tools/ai_openings.py --file tools/balance/openings-telemetry.jsonl   # разбор без игры

Отчёт — tools/balance/ai-openings.txt. Действует ли книга, меряет tools/difficulty_check.py.
"""
import argparse
import io
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_games  # noqa: E402
import balance_run  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOOK = os.path.join(ROOT, 'resources', 'Ai', 'ai-openings.json')
REPORT = os.path.join(balance_run.OUT_DIR, 'ai-openings.txt')
SAVED = os.path.join(balance_run.OUT_DIR, 'openings-telemetry.jsonl')
BUILDINGS = 6
SECTIONS = 8
MIN_WINNERS = 3


def opening(empire, built):
    """Начало империи: постройки (без выданных с родным миром) и разделы прорывов по порядку."""
    buildings = []
    for row in sorted((r for r in built if r.get('ownerSlot') == empire['slot'] and r['builtTurn'] > 1),
                      key=lambda r: (r['builtTurn'], r['code'])):
        if row['code'] not in buildings:
            buildings.append(row['code'])
    # Прорыв — уровень раздела, а не строка изученного: общий уровень (и уровень изобретательной
    # расы) выдаётся целиком, и три его технологии — один прорыв, а не три.
    breakthroughs = sorted({(r['turn'], r['category'], r['level']) for r in empire.get('researched') or []})
    return buildings[:BUILDINGS], [category for _, category, _ in breakthroughs][:SECTIONS]


def book_of(openings):
    """Книга по началам победителей: общие постройки по среднему месту и разделы по позициям."""
    seen = defaultdict(list)
    for buildings, _ in openings:
        for index, code in enumerate(buildings):
            seen[code].append(index)
    common = [code for code, places in seen.items() if len(places) * 2 >= len(openings)]
    common.sort(key=lambda code: (sum(seen[code]) / len(seen[code]), code))
    sections = []
    for position in range(SECTIONS):
        votes = Counter(order[position] for _, order in openings if len(order) > position)
        if not votes:
            break
        sections.append(min(votes, key=lambda name: (-votes[name], name)))
    return {'buildings': common, 'sections': sections}


def busy(account):
    """Идёт ли балансовый прогон; не узнать (нет ответа) — считается, что идёт: файл не трогаем."""
    runs = balance_run.call('GET', '/api/balance/runs', None, {'X-Account-Token': account})
    if not isinstance(runs, list):
        return True
    return any(run.get('status') == 'RUNNING' for run in runs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--games', type=int, default=48)
    parser.add_argument('--turns', type=int, default=120)
    parser.add_argument('--empires', type=int, default=4)
    parser.add_argument('--size', default='SMALL')
    parser.add_argument('--seed', type=int, default=9100)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--file', help='разобрать сохранённую телеметрию, не играя')
    parser.add_argument('--dry', action='store_true', help='не писать книгу, только отчёт')
    parser.add_argument('--force', action='store_true', help='писать книгу и при идущем прогоне')
    options = parser.parse_args()

    lines = []

    def say(line=''):
        print(line, flush=True)
        lines.append(line)

    if options.file:
        games = ai_games.load(options.file)
        say('Книга дебютов (пункт 9): разбор %s, партий %d' % (options.file, len(games)))
    else:
        account = balance_run.admin_token()
        say('Книга дебютов (пункт 9): %d партий, %d ходов, %s, империй %d, обычный уровень'
            % (options.games, options.turns, options.size, options.empires))
        races = ai_games.ready_races()

        def one(seed):
            # Расы по кругу (ai_games.rotated): каждая раса играет одинаково часто и с разными соседями.
            created, start = ai_games.rotated(seed - options.seed, options.empires, races)
            return ai_games.play(account, seed, options.turns, options.size, options.empires, 'Прогон дебютов',
                                 request_extra=created, start_extra=start)
        games = ai_games.batch(account, [options.seed + i for i in range(options.games)], options.workers,
                               one, say)
        ai_games.save(SAVED, games)

    by_race = defaultdict(list)
    everyone = []
    for game in games:
        places = ai_games.shares(game)
        for empire in game.get('empires') or []:
            if not places.get(empire['slot'], (0, False))[1]:
                continue
            start = opening(empire, game.get('built') or [])
            by_race[empire.get('raceCode')].append(start)
            everyone.append(start)

    say('Победителей: %d' % len(everyone))
    if not everyone:
        say('Вывода нет: ни одной партии.')
        return
    book = {'races': {}, 'default': book_of(everyone)}
    for race in sorted(by_race, key=str):
        starts = by_race[race]
        if len(starts) < MIN_WINNERS:
            say('  %s: побед %d — меньше %d, берёт общее начало' % (race, len(starts), MIN_WINNERS))
            continue
        book['races'][race] = book_of(starts)
        say('  %s: побед %d — %s | %s' % (race, len(starts), ' → '.join(book['races'][race]['buildings']) or '—',
                                          ' → '.join(book['races'][race]['sections'])))
    say('  общее: %s | %s' % (' → '.join(book['default']['buildings']) or '—', ' → '.join(book['default']['sections'])))

    if options.dry:
        say('Книга не записана (--dry).')
    elif not options.force and busy(balance_run.admin_token()):
        say('Книга не записана: идёт балансовый прогон, resources/ трогать нельзя (--force — всё равно).')
    else:
        book['note'] = ('Книга дебютов сложного ИИ (field-backlog, пункт 9): собрана tools/ai_openings.py из %d '
                        'партий ИИ — начала империй, занявших первое место по мощи. Руками не правится.' % len(games))
        tmp = BOOK + '.tmp'
        os.makedirs(os.path.dirname(BOOK), exist_ok=True)
        with io.open(tmp, 'w', encoding='utf-8', newline='\n') as out:
            json.dump(book, out, ensure_ascii=False, indent=2)
            out.write('\n')
        os.replace(tmp, BOOK)
        say('Книга записана: %s' % BOOK)

    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    with io.open(REPORT, 'w', encoding='utf-8') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
