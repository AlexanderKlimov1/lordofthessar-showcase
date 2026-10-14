"""Мерило сети — field-backlog, пункт 68.

Сеть видна не числом игроков, а тем, играют ли они друг с другом снова. По неделям (ISO, по
времени конца партии):

* партий с двумя и более людьми — начатых (по столам партий, которые ещё лежат в базе) и
  доигранных (по истории партий, `game_record`: у такой партии строки нескольких записей);
* доля доигранных — доигранные с людьми против начатых с людьми той же недели;
* повторные пары — сколько людей сыграли снова с тем же соперником (признак живой сети);
* растёт ли это от недели к неделе.

Считаются только живые учётные записи: строки удалённых (а это в основном временные записи
сквозного прогона) в мерило не идут. Партии, удалённые из базы, в «начатых» не видны — поэтому
доля доигранных выходит верхней оценкой, и это сказано в отчёте.

Читает Postgres напрямую, как `turn_actions.py`: `python tools\\network_metric.py [--weeks 12]`.
Отчёт — `tools\\funnel\\network-metric.txt` (вне git), рядом с разбором воронки (пункт 2).
"""
import argparse
import collections
import datetime
import io
import itertools
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PSQL = os.environ.get('SDDNW_PSQL', r'C:\PostgreSQL15\bin\psql.exe')
DATABASE = os.environ.get('SDDNW_DB', 'sddnw')
REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'funnel', 'network-metric.txt')


def query(sql):
    env = dict(os.environ, PGPASSWORD=os.environ.get('SDDNW_DB_PASSWORD', 'sddnw'))
    out = subprocess.run([PSQL, '-h', 'localhost', '-p', '5433', '-U', 'sddnw', '-d', DATABASE, '-A', '-t',
                          '-F', '\t', '-c', sql], capture_output=True, text=True, encoding='utf-8', env=env)
    if out.returncode != 0:
        raise SystemExit('psql не ответил: ' + out.stderr.strip())
    return [line.split('\t') for line in out.stdout.splitlines() if line.strip()]


def week(stamp):
    """Неделя ISO по отметке времени Postgres: «2026-W41»."""
    day = datetime.date.fromisoformat(stamp[:10])
    year, number, _ = day.isocalendar()
    return '%d-W%02d' % (year, number)


def finished_games():
    """Доигранные партии с людьми: партия → (неделя конца, записи), только живые записи."""
    games = collections.defaultdict(lambda: [None, set()])
    for game_id, account_id, finished_at in query(
            'select r.game_id, r.account_id, r.finished_at from game_record r '
            'join account a on a.id = r.account_id'):
        entry = games[game_id]
        entry[1].add(account_id)
        stamp = week(finished_at)
        entry[0] = stamp if entry[0] is None or stamp > entry[0] else entry[0]
    return {game: (w, people) for game, (w, people) in games.items() if len(people) >= 2}


def started_games():
    """Начатые партии с людьми, которые ещё лежат в базе: партия → неделя начала."""
    rows = query("select g.id, g.created_at, count(distinct p.account_id) from game g "
                 "join player p on p.game_id = g.id join account a on a.id = p.account_id "
                 "where g.status <> 'LOBBY' group by g.id, g.created_at having count(distinct p.account_id) >= 2")
    return {game_id: week(created) for game_id, created, _ in rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--weeks', type=int, default=12)
    options = parser.parse_args()

    finished = finished_games()
    started = started_games()
    weeks = sorted(set(w for w, _ in finished.values()) | set(started.values()))[-options.weeks:]

    # Повторные пары — с начала истории до конца каждой недели: человек «вернулся к сопернику»,
    # если хоть с одним из соперников у него две и более доигранных партии.
    out = ['МЕРИЛО СЕТИ (field-backlog, пункт 68)', '',
           'Только живые учётные записи; «начатые» — партии, которые ещё лежат в базе, поэтому доля',
           'доигранных — верхняя оценка.', '',
           '%-9s %8s %10s %8s %10s %12s' % ('неделя', 'начато', 'доиграно', 'доля', 'людей', 'с повтором')]
    for current in weeks:
        upto = [people for w, people in finished.values() if w <= current]
        pairs = collections.Counter()
        for people in upto:
            for a, b in itertools.combinations(sorted(people), 2):
                pairs[(a, b)] += 1
        everyone = set(itertools.chain.from_iterable(upto))
        repeaters = {person for pair, times in pairs.items() if times >= 2 for person in pair}
        began = sum(1 for w in started.values() if w == current)
        done = sum(1 for w, _ in finished.values() if w == current)
        share = '%d %%' % round(100 * done / began) if began else '—'
        out.append('%-9s %8d %10d %8s %10d %12d' % (current, began, done, share, len(everyone), len(repeaters)))
    if not weeks:
        out.append('  Партий с двумя и более людьми ещё не было — мерить нечего.')
    elif len(weeks) >= 2:
        out.append('')
        out.append('Рост сети — число людей с повторным соперником от недели к неделе (последний столбец).')

    text = '\n'.join(out)
    print(text)
    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write(text + '\n')


if __name__ == '__main__':
    main()
