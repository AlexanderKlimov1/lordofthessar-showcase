"""
Разнообразие дебютов — field-backlog, пункт 92.

Если у сильных позиций одно и то же начало, после трёх партий игра выучена. Замер: партии ИИ, у
каждой империи — ДЕБЮТ (первые 30 ходов: что построено, что изучено, сколько колоний к 30-му
ходу) и её доля мощи к 100-му ходу (EmpireMightRules, первая линия графика «Инфо»). Дальше —
сколько разных дебютов приводит к сильным позициям.

    python tools/opening_variety.py [--games 12] [--turns 100] [--empires 4] [--size SMALL]

Дебют собирается из телеметрии (`GET …/telemetry`): постройки с ходом (`built`), изученное с
ходом (`researched` у империи) и число колоний из летописи. Кораблей в телеметрии с ходом нет,
поэтому «куда расселяются» выражено числом колоний к 30-му ходу, а не направлением.

Ключ дебюта нарочно грубый — первые две постройки, раздел науки, в который за 30 ходов ушло
больше всего прорывов, и колонии к 30-му ходу (1, 2, 3+): точный порядок у каждой империи свой,
и по нему все дебюты разные, хотя играют они одно и то же.

Сильная позиция — ПЕРВОЕ МЕСТО по мощи в своей партии к последнему ходу. Порог по доле («1,25
справедливой») не годится: первый полный замер (24 партии по 100 ходов, 08.10.2026) нашёл таких
три империи из 96 — партии ИИ к сотому ходу ровные, и порог вырождался в «сильных нет».
Вывод: если один-два ключа занимают три четверти сильных позиций — узкое место; чинится в
правилах (цены зданий и технологий начала, стартовые условия) и записывается в балансовый
журнал — сам замер правил не трогает.

Важно читать честно: ИИ играет по спискам (`AiEmpireService`), и разнообразие его дебютов — это
разнообразие характеров правителей, а не человеческих решений. Замер показывает, какие НАЧАЛА
игра вознаграждает, а не сколько начал придумает игрок.

Отчёт — tools/balance/opening-variety.txt. Нужен сервер обычного режима или прогонный — партии
удаляются за собой.
"""
import argparse
import io
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance_run  # noqa: E402  — помощники вызова сервера и пропуск администратора

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPORT = os.path.join(balance_run.OUT_DIR, 'opening-variety.txt')
OPENING_TURNS = 30
NARROW_COVER = 0.75
# Меньше сильных позиций — не вывод, а случай: два попадания одного дебюта из двух ничего не значат.
MIN_STRONG = 8


def play(account, seed, turns, size, empires):
    request = {'name': 'Прогон дебютов %d' % seed, 'playerName': 'Наблюдатель', 'galaxySize': size,
               'observer': True, 'seed': seed, 'council': False, 'galacticEvents': True,
               'totalPlayers': empires}
    created = balance_run.call('POST', '/api/games', request, {'X-Account-Token': account})
    if 'ERROR' in created:
        return {'error': created.get('body')}
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    try:
        started = balance_run.call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
        if isinstance(started, dict) and 'ERROR' in started:
            return {'error': started.get('body')}
        advanced = balance_run.call('POST', '/api/games/%s/turn/advance' % game_id,
                                    {'accessToken': token, 'turns': turns})
        if 'ERROR' in advanced:
            return {'error': advanced.get('body')}
        return balance_run.call('GET', '/api/games/%s/telemetry?accessToken=%s' % (game_id, token))
    finally:
        balance_run.call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))


def colonies_at(history, turn):
    """Колоний на ход `turn` — последний замер летописи не позже него."""
    count = 1
    for row in history:
        if row['turn'] <= turn:
            count = row.get('colonies') or count
    return count


def opening(empire, built):
    """Дебют империи: постройки, наука и колонии первых OPENING_TURNS ходов."""
    # Ход 1 — то, что империя получает с родным миром (столица, казармы, звёздная база): это не
    # выбор, и в дебют оно не идёт.
    buildings = [row['code'] for row in built
                 if row.get('ownerSlot') == empire['slot'] and 1 < row['builtTurn'] <= OPENING_TURNS]
    researched = [row for row in empire.get('researched') or [] if row['turn'] <= OPENING_TURNS]
    sections = Counter(row['category'] for row in researched)
    colonies = colonies_at(empire.get('history') or [], OPENING_TURNS)
    first_two = []
    for code in buildings:
        if code not in first_two:
            first_two.append(code)
        if len(first_two) == 2:
            break
    # Ничья разделов разрешается именем раздела: ключ обязан быть одинаков в двух прогонах.
    section = min(sections, key=lambda name: (-sections[name], name)) if sections else '—'
    return {
        'buildings': first_two,
        'section': section,
        'colonies': '3+' if colonies >= 3 else str(colonies),
        'researched': [row['option'] for row in researched],
        'key': '%s | наука: %s | колоний %s' % (
            ' → '.join(first_two) or 'без построек', section, '3+' if colonies >= 3 else colonies),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--games', type=int, default=12)
    parser.add_argument('--turns', type=int, default=100)
    parser.add_argument('--empires', type=int, default=4)
    parser.add_argument('--size', default='SMALL')
    parser.add_argument('--seed', type=int, default=92000)
    options = parser.parse_args()

    account = balance_run.admin_token()
    lines = []

    def say(line=''):
        print(line)
        lines.append(line)

    say('Разнообразие дебютов (пункт 92): %d партий, %d ходов, %s, империй %d; дебют — первые %d ходов'
        % (options.games, options.turns, options.size, options.empires, OPENING_TURNS))
    rows = []
    for index in range(options.games):
        seed = options.seed + index
        telemetry = play(account, seed, options.turns, options.size, options.empires)
        if not isinstance(telemetry, dict) or 'error' in telemetry or 'ERROR' in telemetry:
            say('  зерно %d: отказ — %s' % (seed, (telemetry or {}).get('error') or (telemetry or {}).get('body')))
            continue
        empires = telemetry.get('empires') or []
        might = {e['slot']: ((e.get('history') or [{}])[-1].get('might') or 0) for e in empires}
        total = sum(might.values()) or 1
        for empire in empires:
            share = might[empire['slot']] / total
            rows.append({'seed': seed, 'share': share, 'fair': 1 / len(empires),
                         'first': empire['slot'] == max(might, key=lambda slot: (might[slot], -slot)),
                         **opening(empire, telemetry.get('built') or [])})
        say('  зерно %d: империй %d' % (seed, len(empires)))

    say()
    if not rows:
        say('Замерить нечего: ни одна партия не доиграна.')
    else:
        strong = [row for row in rows if row['first']]
        by_key = defaultdict(list)
        for row in rows:
            by_key[row['key']].append(row)
        say('Империй %d, разных дебютов %d, сильных позиций (первое место в партии) %d'
            % (len(rows), len(by_key), len(strong)))
        say()
        say('Дебюты по средней доле мощи (доля / справедливая; сколько империй; из них сильных):')
        ranked = sorted(by_key.items(),
                        key=lambda item: (-sum(r['share'] / r['fair'] for r in item[1]) / len(item[1]), item[0]))
        for key, group in ranked:
            mean = sum(r['share'] / r['fair'] for r in group) / len(group)
            strong_here = sum(1 for r in group if r in strong)
            say('  %.2f  ×%-3d сильных %-3d %s' % (mean, len(group), strong_here, key))

        say()
        say('По отдельным выборам начала (средняя доля / справедливая; сколько империй):')
        for title, pick in (('первая постройка', lambda r: r['buildings'][0] if r['buildings'] else 'нет'),
                            ('раздел науки', lambda r: r['section']),
                            ('колоний к %d-му ходу' % OPENING_TURNS, lambda r: r['colonies'])):
            groups = defaultdict(list)
            for row in rows:
                groups[pick(row)].append(row['share'] / row['fair'])
            say('  %s:' % title)
            for value, shares in sorted(groups.items(), key=lambda item: (-sum(item[1]) / len(item[1]), item[0])):
                say('    %.2f  ×%-3d %s' % (sum(shares) / len(shares), len(shares), value))

        say()
        if len(strong) < MIN_STRONG:
            say('Сильных позиций %d — меньше %d, для вывода мало: нужно больше партий (--games).'
                % (len(strong), MIN_STRONG))
        else:
            strong_keys = Counter(row['key'] for row in strong)
            top_two = sum(count for _, count in strong_keys.most_common(2))
            cover = top_two / len(strong)
            say('Сильные позиции дали %d разных дебютов; два самых частых занимают %.0f %%.'
                % (len(strong_keys), cover * 100))
            if len(strong_keys) <= 2 or cover >= NARROW_COVER:
                say('ВЫВОД: узкое место — к силе ведут один-два дебюта. Чинить правилами начала '
                    '(цены зданий и технологий, стартовые условия) и записать в балансовый журнал.')
            else:
                say('ВЫВОД: к силе ведут разные начала — узкого места дебюта не видно.')
    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
