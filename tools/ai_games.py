"""
Партии ИИ пачкой — общий помощник замеров уровней сложности и выборов (field-backlog, пункты 9, 82, 85).

Каждая партия: завести (наблюдатель — его империю ведёт тот же ИИ), стартовать, прогнать ходы одним
запросом, снять телеметрию, удалить. Партии идут в несколько потоков (`--workers`): сервер считает
их независимо, а ждать двадцать партий по очереди незачем.

Телеметрию можно сохранить файлом (`--save`) и разобрать заново без игры (`--file`): разбор
правят чаще, чем меняется игра.
"""
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance_run  # noqa: E402  — вызов сервера и пропуск администратора


def play(account, seed, turns, size, empires, name, request_extra=None, start_extra=None):
    """Одна партия; телеметрия или {'error': …}."""
    request = {'name': '%s %d' % (name, seed), 'playerName': 'Наблюдатель', 'galaxySize': size,
               'observer': True, 'seed': seed, 'council': False, 'galacticEvents': True,
               'totalPlayers': empires}
    request.update(request_extra or {})
    created = balance_run.call('POST', '/api/games', request, {'X-Account-Token': account})
    if not isinstance(created, dict) or 'ERROR' in created:
        return {'error': (created or {}).get('body')}
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    try:
        start = {'accessToken': token}
        start.update(start_extra or {})
        started = balance_run.call('POST', '/api/games/%s/start' % game_id, start)
        if isinstance(started, dict) and 'ERROR' in started:
            return {'error': started.get('body')}
        advanced = balance_run.call('POST', '/api/games/%s/turn/advance' % game_id,
                                    {'accessToken': token, 'turns': turns})
        if not isinstance(advanced, dict) or 'ERROR' in advanced:
            return {'error': (advanced or {}).get('body')}
        telemetry = balance_run.call('GET', '/api/games/%s/telemetry?accessToken=%s' % (game_id, token))
        if isinstance(telemetry, dict):
            telemetry['seed'] = seed
        return telemetry
    finally:
        balance_run.call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))


def ready_races():
    """Коды готовых рас справочника по порядку."""
    return [one['code'] for one in balance_run.call('GET', '/api/reference/races') or []
            if isinstance(one, dict) and one.get('code')]


def rotated(index, empires, races):
    """
    Расы стола партии `index` — по кругу из всех готовых, и поля запроса под них.

    Без этого за стол садятся всегда первые расы справочника (раздача — первая свободная), и замер
    смешивает устремление или выбор правителя с силой одной и той же четвёрки рас. Возвращает пару
    (поля создания партии, поля старта).
    """
    seats = [races[(index * empires + k) % len(races)] for k in range(empires)]
    return {'raceCode': seats[0]}, {'aiEmpires': [{'raceCode': code} for code in seats[1:]]}


def batch(account, seeds, workers, one, say):
    """Партии по зёрнам в `workers` потоков; `one(seed)` играет партию. Порядок результата — порядок зёрен."""
    lock = threading.Lock()
    done = [0]

    def run(seed):
        result = one(seed)
        with lock:
            done[0] += 1
            if not isinstance(result, dict) or 'error' in result or 'ERROR' in result:
                say('  зерно %d: отказ — %s' % (seed, (result or {}).get('error') or (result or {}).get('body')))
            else:
                say('  партия %d из %d (зерно %d)' % (done[0], len(seeds), seed))
        return result

    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        results = list(pool.map(run, seeds))
    return [one for one in results if isinstance(one, dict) and 'error' not in one and 'ERROR' not in one]


def save(path, games):
    with open(path, 'w', encoding='utf-8') as out:
        for game in games:
            out.write(json.dumps(game, ensure_ascii=False) + '\n')


def load(path):
    with open(path, encoding='utf-8') as source:
        return [json.loads(line) for line in source if line.strip()]


def final_might(empire):
    """Мощь империи на последнем замере летописи."""
    return ((empire.get('history') or [{}])[-1].get('might') or 0)


def shares(game):
    """Доля мощи каждой империи к концу партии и первое место — по месту (slot)."""
    empires = game.get('empires') or []
    might = {e['slot']: final_might(e) for e in empires}
    total = sum(might.values()) or 1
    leader = max(might, key=lambda slot: (might[slot], -slot)) if might else None
    return {slot: (value / total, slot == leader) for slot, value in might.items()}
