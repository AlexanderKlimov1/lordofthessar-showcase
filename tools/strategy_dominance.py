"""
Доминирующие стратегии игры, а не только сборки рас — field-backlog, пункт 82.

Оракул ищет сильнейшую РАСУ. Но если одно устремление правителя (AiObjective) или один характер
(AiPersonality) выигрывает почти всегда, пространство стратегий схлопнулось: выбор игрока
превращается в один правильный ответ (пункт 21). Если же партии кончаются почти всегда одним путём
к победе, остальные пути — для красоты.

Замер: партии ИИ обычного уровня со ВСЕМИ путями к победе, как у игрока (совет, удержание Тессара,
итог по могуществу на 300-м ходу, покорение), до конца. По устремлению и по характеру — сколько
империй, доля побед и средняя доля мощи к последнему ходу; по пути к победе — сколько партий им
кончилось.

Приговор — правилу, а не расе: группа, чья доля побед больше справедливой (1/империй) вдвое и на
две ошибки, — перекос, и чинится он в механике (цены зданий, пороги побед), с записью в балансовый
журнал. Сам замер правил не трогает (решение хозяина проекта: замер и отчёт).

Честное чтение: ИИ играет списками, и устремление у него — это разделы науки, а характер — пороги
войны и доверия. Замер говорит, какую СКЛОННОСТЬ вознаграждает игра, а не какую стратегию найдёт
человек.

    python tools/strategy_dominance.py [--games 24] [--turns 300] [--empires 4] [--workers 4] [--save F | --file F]

Отчёт — tools/balance/strategy-dominance.txt.
"""
import argparse
import io
import math
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_games  # noqa: E402
import balance_run  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPORT = os.path.join(balance_run.OUT_DIR, 'strategy-dominance.txt')
# Меньше империй в группе — не вывод: три победы из пяти ничего не значат.
MIN_GROUP = 8


def table(say, title, groups, fair):
    say()
    say(title)
    say('  %-16s %8s %8s %12s %14s  %s' % ('группа', 'империй', 'побед', 'доля побед', 'доля мощи', 'приговор'))
    for name in sorted(groups, key=lambda key: str(key)):
        rows = groups[name]
        wins = sum(1 for _, won in rows if won)
        rate = wins / len(rows)
        mean_share = sum(share for share, _ in rows) / len(rows)
        noise = math.sqrt(fair * (1 - fair) / len(rows))
        if len(rows) < MIN_GROUP:
            verdict = 'мало партий'
        elif rate >= 2 * fair and rate - fair > 2 * noise:
            verdict = 'ПЕРЕКОС: выигрывает слишком часто'
        elif rate == 0 and len(rows) >= 2 * MIN_GROUP:
            verdict = 'МЁРТВЫЙ ВЫБОР: не выигрывает никогда'
        else:
            verdict = 'в пределах случая'
        say('  %-16s %8d %8d %11.0f%% %14.3f  %s' % (name, len(rows), wins, 100 * rate, mean_share, verdict))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--games', type=int, default=24)
    parser.add_argument('--turns', type=int, default=500)
    parser.add_argument('--empires', type=int, default=4)
    parser.add_argument('--size', default='SMALL')
    parser.add_argument('--seed', type=int, default=8200)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--file', help='разобрать сохранённую телеметрию, не играя')
    parser.add_argument('--save', help='сохранить телеметрию в файл (её же читает tools/choice_interest.py)')
    options = parser.parse_args()

    lines = []

    def say(line=''):
        print(line, flush=True)
        lines.append(line)

    if options.file:
        games = ai_games.load(options.file)
        say('Доминирующие стратегии (пункт 82): разбор %s, партий %d' % (options.file, len(games)))
    else:
        account = balance_run.admin_token()
        say('Доминирующие стратегии (пункт 82): %d партий до %d хода, %s, империй %d, все пути к победе'
            % (options.games, options.turns, options.size, options.empires))
        victories = {'council': True, 'wardenholdVictory': True, 'mightVictory': True}
        races = ai_games.ready_races()

        def one(seed):
            created, start = ai_games.rotated(seed - options.seed, options.empires, races)
            return ai_games.play(account, seed, options.turns, options.size, options.empires, 'Прогон стратегий',
                                 request_extra=dict(victories, **created), start_extra=start)
        games = ai_games.batch(account, [options.seed + i for i in range(options.games)], options.workers, one, say)
        if options.save:
            ai_games.save(options.save, games)

    by_objective = defaultdict(list)
    by_personality = defaultdict(list)
    kinds = Counter()
    decided = 0
    empires_total = 0
    for game in games:
        empires = game.get('empires') or []
        empires_total += len(empires)
        winner = game.get('winnerSlot')
        if winner is not None:
            decided += 1
            kinds[game.get('victoryKind') or '—'] += 1
        else:
            kinds['не кончилась'] += 1
        places = ai_games.shares(game)
        for empire in empires:
            share = places.get(empire['slot'], (0, False))[0]
            won = winner is not None and empire['slot'] == winner
            by_objective[empire.get('objective') or '—'].append((share, won))
            by_personality[empire.get('personality') or '—'].append((share, won))

    fair = len(games) / empires_total if empires_total else 0.25
    say('Партий %d, кончились победой %d; справедливая доля побед — %.0f%%' % (len(games), decided, 100 * fair))
    table(say, 'По устремлению правителя (AiObjective):', by_objective, fair)
    table(say, 'По характеру правителя (AiPersonality):', by_personality, fair)
    say()
    say('По пути к победе:')
    for kind, count in kinds.most_common():
        say('  %-16s %4d  (%.0f%%)' % (kind, count, 100 * count / max(1, len(games))))
    if decided and kinds.most_common(1)[0][1] * 4 >= 3 * len(games):
        say('  ПЕРЕКОС: три четверти партий кончаются одним путём — остальные пути не работают.')

    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    with io.open(REPORT, 'w', encoding='utf-8') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
