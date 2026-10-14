"""
Восхищающее — показать рано: на каком ходу гостевой партии случается первое запоминающееся
событие — field-backlog, пункт 190.

Бросивший на 20-м ходу не встретил ни Стража, ни дракона на поле. Цель пункта — одно такое
событие не позже 30-го хода. Замер идёт на партиях НАБЛЮДАТЕЛЯ по гостевым правилам
(`guestRules` — тесный и богатый старт, первые уровни по оплате, наука вдвое; заводить такую
партию вправе только администратор): империю наблюдателя ведёт ИИ — заместитель новичка, —
а итоги хода сервер пишет ему так же, как человеку. Что считается событием:

* чудище — флот встретил сторожа (`turn.monster.*`); у человека это бой на поле;
* посол — знакомство или объявление войны (сцена посла);
* бой — встреча флотов или бой (`ENCOUNTER`, `BATTLE`), кроме боёв с чудищами.

Запуск при поднятом сервере: `python tools\\guest_delight.py [--games 8] [--turns 60]`.
Отчёт — `tools\\balance\\guest-delight.txt`.
"""
import argparse
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance_run  # noqa: E402

REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'balance', 'guest-delight.txt')
GOAL = 30
ENVOY = {'turn.diplomacy.met', 'turn.diplomacy.metYou', 'turn.diplomacy.warDeclared'}
KINDS = ('monster', 'envoy', 'battle')


def kinds(report):
    """Какие восхищающие события есть в итогах хода."""
    found = set()
    for event in report.get('events') or []:
        key = event.get('key') or ''
        if key.startswith('turn.monster.'):
            found.add('monster')
        elif key in ENVOY:
            found.add('envoy')
        elif event.get('code') in ('ENCOUNTER', 'BATTLE'):
            found.add('battle')
    return found


def play(account, seed, turns):
    request = {'name': 'Замер восхищения %d' % seed, 'playerName': 'Наблюдатель',
               'galaxySize': 'SMALL', 'totalPlayers': 4, 'observer': True, 'seed': seed,
               'guestRules': True, 'galacticEvents': True, 'council': True,
               'wardenholdVictory': True, 'mightVictory': True}
    created = balance_run.call('POST', '/api/games', request, {'X-Account-Token': account})
    if 'ERROR' in created:
        raise SystemExit('партия не заведена: %s' % created)
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    balance_run.remember(game_id, token)
    first = {}
    try:
        balance_run.call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
        for _ in range(turns):
            step = balance_run.call('POST', '/api/games/%s/turn/end' % game_id, {'accessToken': token})
            if not isinstance(step, dict) or step.get('ERROR') or not step.get('advanced'):
                break
            report = balance_run.call('GET', '/api/games/%s/turn/report?accessToken=%s' % (game_id, token))
            if isinstance(report, dict) and not report.get('ERROR'):
                for kind in kinds(report):
                    first.setdefault(kind, report.get('turn'))
            if len(first) == len(KINDS) or step.get('status') == 'FINISHED':
                break
    finally:
        balance_run.call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))
    return first


def main():
    parser = argparse.ArgumentParser(description='Первое восхищающее событие гостевой партии — пункт 190')
    parser.add_argument('--games', type=int, default=8)
    parser.add_argument('--turns', type=int, default=60)
    parser.add_argument('--seed', type=int, default=19000)
    options = parser.parse_args()
    account = balance_run.admin_token()
    out = ['ПЕРВОЕ ВОСХИЩАЮЩЕЕ СОБЫТИЕ ГОСТЕВОЙ ПАРТИИ (field-backlog, пункт 190)',
           'Малая галактика, четыре империи, гостевые правила; цель — не позже %d-го хода.' % GOAL,
           'Прочерк — за %d ходов не случилось.' % options.turns, '',
           '%-8s %8s %8s %8s %10s' % ('зерно', 'чудище', 'посол', 'бой', 'первое')]
    earliest = []
    for index in range(options.games):
        seed = options.seed + index
        first = play(account, seed, options.turns)
        soonest = min(first.values()) if first else None
        earliest.append(soonest)
        line = '%-8d %8s %8s %8s %10s' % (seed, *(first.get(kind, '—') for kind in KINDS),
                                          soonest if soonest is not None else '—')
        print(line, flush=True)
        out.append(line)
    met = sum(1 for turn in earliest if turn is not None and turn <= GOAL)
    known = sorted(turn for turn in earliest if turn is not None)
    out += ['', 'Цель достигнута в %d партиях из %d; медиана первого события — %s.' % (
        met, len(earliest), known[len(known) // 2] if known else '—')]
    print(out[-1])
    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('отчёт — %s' % REPORT)


if __name__ == '__main__':
    main()
