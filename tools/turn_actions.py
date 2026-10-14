"""
Рутина поздней партии — замером: сколько действий живой игрок делает за ход у 30-го и у 150-го
хода и какие из них самые частые — field-backlog, пункт 13.

Считает сервер ({@code TurnActionService}): каждое изменяющее действие живого игрока — строкой
таблицы `turn_action` (партия, игрок, ход, действие, сколько раз); скрипты проекта не считаются.
Растёт число действий вместе с империей — смотреть, какие именно, и автоматизировать самое
частое: каждое действие из обязанности — утечка из потока в скуку.

«Оптимально, но скучно» — пункт 91: второй раздел отчёта сравнивает частоту каждого действия на
поздних ходах у тех, кто партию выиграл (первое место по мощи или победа — история партий,
`game_record`), и у остальных. Действие, которое повторяют каждый ход, и повторяют тем чаще, чем
лучше играют, — рутина, без которой не выиграть; её лечат автоматизацией, а не запретом (пункт 95).
Рядом с действием сказано, есть ли у него автоматизация (`AUTOMATIONS`): нет — кандидат.

Читает Postgres напрямую, как `ai_audit.py`: `python tools\\turn_actions.py [--around 30,150]
[--span 10] [--late 50]`. Отчёт — `tools\\balance\\turn-actions.txt` (вне git).
"""
import argparse
import collections
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PSQL = os.environ.get('SDDNW_PSQL', r'C:\PostgreSQL15\bin\psql.exe')
DATABASE = os.environ.get('SDDNW_DB', 'sddnw')
REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'balance', 'turn-actions.txt')


def rows():
    env = dict(os.environ, PGPASSWORD=os.environ.get('SDDNW_DB_PASSWORD', 'sddnw'))
    out = subprocess.run([PSQL, '-h', 'localhost', '-p', '5433', '-U', 'sddnw', '-d', DATABASE, '-A', '-t',
                          '-F', '\t', '-c', 'select game_id, player_id, turn, action, times from turn_action'],
                         capture_output=True, text=True, encoding='utf-8', env=env)
    if out.returncode != 0:
        raise SystemExit('psql не ответил: ' + out.stderr.strip())
    for line in out.stdout.splitlines():
        parts = line.split('\t')
        if len(parts) == 5:
            yield parts[0], parts[1], int(parts[2]), parts[3], int(parts[4])


#: Чем уже автоматизировано действие — пункт 91. Нет строки — автоматизации нет, действие кандидат.
AUTOMATIONS = {
    'POST /api/games/{id}/planets/{id}/population': 'жители сами (пункт 91)',
    'POST /api/games/{id}/planets/{id}/project': 'автострой и повтор очереди (п. 10), шаблоны стройки',
    'POST /api/games/{id}/planets/{id}/queue': 'шаблоны стройки, повтор очереди',
    'POST /api/games/{id}/planets/{id}/queue/{index}/move': 'шаблоны стройки',
    'POST /api/games/{id}/fleets/{id}/move': 'разведывать самим (только разведка)',
    'POST /api/games/{id}/research': 'продолжение раздела без выбора (keepSection)',
}
#: С какой частоты на ход действие считается рутиной.
ROUTINE_PER_TURN = 1.0
#: Во сколько раз чаще у победителей — «тем чаще, чем лучше играют».
WINNERS_RATIO = 1.2


def outcomes():
    """Игрок партии → выиграл ли он её (победа или первое место по мощи), по истории партий."""
    env = dict(os.environ, PGPASSWORD=os.environ.get('SDDNW_DB_PASSWORD', 'sddnw'))
    query = ('select p.id, r.outcome, r.place from player p join game_record r '
             'on r.account_id = p.account_id and r.game_id = p.game_id')
    out = subprocess.run([PSQL, '-h', 'localhost', '-p', '5433', '-U', 'sddnw', '-d', DATABASE, '-A', '-t',
                          '-F', '\t', '-c', query], capture_output=True, text=True, encoding='utf-8', env=env)
    won = {}
    for line in out.stdout.splitlines() if out.returncode == 0 else []:
        parts = line.split('\t')
        if len(parts) == 3:
            won[parts[0]] = parts[1] == 'WON' or parts[2] == '1'
    return won


def routine(data, late):
    """Раздел пункта 91: частота действий на поздних ходах у победителей и прочих."""
    won = outcomes()
    lines = ['РУТИНА (field-backlog, пункт 91): поздние ходы с %d-го; игроков с итогом партии: %d' % (late, len(won))]
    turns = {True: set(), False: set()}
    counts = {True: collections.Counter(), False: collections.Counter()}
    for game_id, player_id, turn, action, times in data:
        if turn < late or player_id not in won:
            continue
        turns[won[player_id]].add((player_id, turn))
        counts[won[player_id]][action] += times
    if not turns[True] and not turns[False]:
        lines.append('  Вывода нет: доигранных партий живых игроков в журнале нет.')
        return lines
    actions = set(counts[True]) | set(counts[False])
    rows = []
    for action in actions:
        winners = counts[True][action] / len(turns[True]) if turns[True] else 0
        others = counts[False][action] / len(turns[False]) if turns[False] else 0
        rows.append((max(winners, others), winners, others, action))
    for _, winners, others, action in sorted(rows, reverse=True)[:15]:
        routine_here = max(winners, others) >= ROUTINE_PER_TURN and winners >= others * WINNERS_RATIO
        automation = AUTOMATIONS.get(action)
        verdict = ('РУТИНА — ' + ('есть: ' + automation if automation else 'автоматизации нет, кандидат')
                   if routine_here else '')
        lines.append('  %6.2f / %6.2f на ход  %s  %s' % (winners, others, action, verdict))
    lines.append('  (победители / прочие)')
    return lines


def main():
    parser = argparse.ArgumentParser(description='Действия живых игроков по ходам — пункт 13')
    parser.add_argument('--around', default='30,150')
    parser.add_argument('--span', type=int, default=10)
    parser.add_argument('--late', type=int, default=50)
    options = parser.parse_args()
    marks = [int(mark) for mark in options.around.split(',')]
    data = list(rows())
    out = ['ДЕЙСТВИЯ ЖИВЫХ ИГРОКОВ ПО ХОДАМ (field-backlog, пункт 13)',
           'Строк в журнале: %d; партий: %d; игроков: %d.' % (
               len(data), len({row[0] for row in data}), len({row[1] for row in data})), '']
    for mark in marks:
        low, high = mark - options.span // 2, mark + options.span // 2
        window = [row for row in data if low <= row[2] <= high]
        player_turns = {(row[1], row[2]) for row in window}
        total = sum(row[4] for row in window)
        out.append('Ходы %d–%d: игроко-ходов %d, действий на ход в среднем %.1f' % (
            low, high, len(player_turns), total / len(player_turns) if player_turns else 0))
        common = collections.Counter()
        for row in window:
            common[row[3]] += row[4]
        for action, times in common.most_common(10):
            out.append('    %6.2f на ход  %s' % (times / len(player_turns), action))
        out.append('')
    out += routine(data, options.late)
    print('\n'.join(out))
    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(out) + '\n')


if __name__ == '__main__':
    main()
