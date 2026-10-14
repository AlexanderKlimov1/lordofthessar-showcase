"""
Сколько окон и строк итогов приходится на ход к 50-му, 150-му и 250-му ходу — field-backlog,
пункт 162.

Без этого числа игру проверяют на первых тридцати ходах, а партию играют до трёхсотого. Замер
идёт на партии НАБЛЮДАТЕЛЯ (`observer`, как у балансовых прогонов): его империю ведёт ИИ —
заместитель человека на авторазведке и автострое, — а итоги хода сервер пишет ему так же, как
человеку. Ход за ходом читаются эти итоги (`GET …/turn/report`) и раскладываются так же, как их
раскладывает клиент (`App.tsx`):

* итоги хода — окно открывается, если в них есть хоть что-то кроме дохода (`worthShowing`);
* разведанная система — событие `EXPLORATION` не на приказе «разведывать самим»;
* наука — прорыв (`RESEARCH`): человеку окно выбора открывается само;
* сцены редких событий — знакомство, война, колония взята или потеряна, клад Стражей;
* совет — `COUNCIL` (в замере выключен: партия обязана дойти до 250-го хода); кража — ключи
  кражи технологии;
* встреча и бой — `ENCOUNTER` и `BATTLE`: у человека это окно выбора или поле боя.

Окна, которые у человека открылись бы сами, у ИИ не открываются вовсе, — поэтому считается
не то, что видел ИИ, а то, что УВИДЕЛ БЫ на его месте человек.

Запуск при поднятом сервере: `python tools\\turn_load.py [--turns 250] [--seed 9100]`. Две
партии: малая галактика на четверых (гостевая) и огромная на восьмерых. Отчёт —
`tools\\balance\\turn-load.txt`.
"""
import argparse
import collections
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance_run  # noqa: E402

REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'balance', 'turn-load.txt')
CHECKPOINTS = (50, 150, 250)
# Предел окон на ход у 150-го хода — field-backlog, пункт 167: окно, которое открывается само,
# проверяется не на первом ходу, а там, где партию бросают от рутины. Первый замер (07.10.2026)
# дал 1,3 на малой галактике и 1,8 на огромной; порог — с запасом над ним, и новое автоокно,
# которое его переступит, обязано прийти со способом свернуть (пункты 163, 164).
LIMIT_AT_150 = 2.5
# Окно меряется не одним ходом, а десятком вокруг отметки: за один ход событие то есть, то нет.
SPAN = 10

STORY_KEYS = {
    'turn.diplomacy.met', 'turn.diplomacy.metYou', 'turn.diplomacy.warDeclared',
    'turn.invasion.colonyLost', 'turn.invasion.colonyLostSubjects', 'turn.invasion.colonyLostBy',
    'turn.invasion.colonyLostSubjectsBy', 'turn.invasion.repelled', 'turn.invasion.repelledBy',
    'turn.specialStar.claimed',
}
THEFT_KEYS = {'turn.espionage.stole', 'turn.espionage.stolenFromYou'}
WINDOWS = ('results', 'explored', 'research', 'stories', 'council', 'theft', 'battle')


def windows(report):
    """Какие окна этот отчёт открыл бы человеку — тем же правилом, что у клиента."""
    events = report.get('events') or []
    opened = collections.Counter()
    if any(event.get('code') != 'INCOME' for event in events):
        opened['results'] += 1
    if any(event.get('code') == 'EXPLORATION' and event.get('key') != 'turn.fleet.exploredAuto'
           for event in events):
        opened['explored'] += 1
    if any(event.get('code') == 'RESEARCH' for event in events):
        opened['research'] += 1
    opened['stories'] += sum(1 for event in events if event.get('key') in STORY_KEYS)
    if any(event.get('code') == 'COUNCIL' for event in events):
        opened['council'] += 1
    if any(event.get('key') in THEFT_KEYS for event in events):
        opened['theft'] += 1
    opened['battle'] += sum(1 for event in events if event.get('code') in ('ENCOUNTER', 'BATTLE'))
    return opened, len(events)


def play(account, seed, turns, size, empires):
    request = {'name': 'Замер окон %s' % size, 'playerName': 'Наблюдатель', 'galaxySize': size,
               # Совет и прочие пути к победе выключены: партия обязана дойти до 250-го хода,
               # иначе поздних отметок не снять (первый прогон кончился советом на 149-м и 199-м).
               'observer': True, 'seed': seed, 'council': False, 'totalPlayers': empires}
    created = balance_run.call('POST', '/api/games', request, {'X-Account-Token': account})
    if 'ERROR' in created:
        raise SystemExit('партия не заведена: %s' % created)
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    balance_run.remember(game_id, token)
    per_turn = {}
    try:
        balance_run.call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
        for _ in range(turns):
            step = balance_run.call('POST', '/api/games/%s/turn/end' % game_id, {'accessToken': token})
            if not isinstance(step, dict) or step.get('ERROR') or not step.get('advanced'):
                break
            report = balance_run.call('GET', '/api/games/%s/turn/report?accessToken=%s' % (game_id, token))
            if isinstance(report, dict) and not report.get('ERROR'):
                per_turn[report.get('turn')] = windows(report)
            if step.get('status') == 'FINISHED':
                break
    finally:
        balance_run.call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))
    return per_turn


def windows_at(per_turn, mark):
    span = [turn for turn in per_turn if mark - SPAN // 2 < turn <= mark + SPAN // 2]
    if not span:
        return None
    return sum(sum(per_turn[turn][0].values()) for turn in span) / len(span)


def summary(per_turn, size, empires):
    lines = ['%s, империй %d: сыграно ходов %d' % (size, empires, max(per_turn, default=0))]
    lines.append('  %-10s %8s %8s %6s %8s %8s %6s %6s %6s %10s' % (
        'ходы', 'итоги', 'разведка', 'наука', 'сцены', 'совет', 'кража', 'бои', 'окон', 'строк'))
    for mark in CHECKPOINTS:
        span = [turn for turn in per_turn if mark - SPAN // 2 < turn <= mark + SPAN // 2]
        if not span:
            lines.append('  %-10s партия до этого хода не дошла' % ('%d±%d' % (mark, SPAN // 2)))
            continue
        total = collections.Counter()
        rows = 0
        for turn in span:
            opened, count = per_turn[turn]
            total.update(opened)
            rows += count
        n = len(span)
        lines.append('  %-10s %8.1f %8.1f %6.1f %8.1f %8.1f %6.1f %6.1f %6.1f %10.1f' % (
            '%d±%d' % (mark, SPAN // 2),
            *(total[name] / n for name in WINDOWS),
            sum(total.values()) / n, rows / n))
    return lines


def main():
    parser = argparse.ArgumentParser(description='Окна и строки итогов на ход — пункт 162')
    parser.add_argument('--turns', type=int, default=250)
    parser.add_argument('--seed', type=int, default=9100)
    options = parser.parse_args()
    account = balance_run.admin_token()
    out = ['ОКНА И СТРОКИ ИТОГОВ НА ХОД (field-backlog, пункт 162)',
           'В среднем на ход за десяток ходов вокруг отметки; «окон» — сумма всех, что открылись бы сами.',
           '']
    over = []
    for size, empires in (('SMALL', 4), ('HUGE', 8)):
        print('играю %s на %d…' % (size, empires), flush=True)
        per_turn = play(account, options.seed, options.turns, size, empires)
        part = summary(per_turn, size, empires)
        print('\n'.join(part), flush=True)
        out += part + ['']
        at150 = windows_at(per_turn, 150)
        if at150 is not None and at150 > LIMIT_AT_150:
            over.append('%s: %.1f окна на ход у 150-го хода при пределе %.1f' % (size, at150, LIMIT_AT_150))
    out += ['Предел у 150-го хода (пункт 167): ' + ('превышен — ' + '; '.join(over) if over else 'соблюдён') + '.']
    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('отчёт — %s' % REPORT)
    if over:
        sys.exit(1)


if __name__ == '__main__':
    main()
