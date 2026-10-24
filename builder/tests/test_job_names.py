"""Synthetic checks for builder/job_names.py (no game files): the licence board names come from rows 5..16 of an st2e
menu file with ids 10100..10111; every other layout, and every missing, empty or undecodable name, is refused."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import job_names  # noqa: E402
from test_menumap_names import enc, st2e  # noqa: E402

LETTERS = 'ABCDEFGHIJKL'


def menu(jobs=None, filler=5):
    jobs = jobs if jobs is not None else [(10100 + j, enc('Board ' + LETTERS[j])) for j in range(12)]
    return st2e([(i + 1, enc('Item')) for i in range(filler)] + jobs + [(10400, enc('Equip'))])


def refused(raw, what):
    try:
        job_names.job_names(raw)
    except ValueError:
        return
    raise AssertionError('accepted: ' + what)


def main():
    ok = 0
    assert job_names.job_names(menu()) == ['Board ' + c for c in LETTERS]
    ok += 1
    rows = [(10100 + j, enc('Board ' + LETTERS[j])) for j in range(12)]
    refused(menu(rows[1:] + rows[:1]), 'ids at the wrong rows')
    refused(menu(filler=4), 'job rows shifted by one')
    refused(menu(rows[:11]), 'a board name missing')
    refused(menu(rows[:3] + [(10103, b'')] + rows[4:]), 'an empty name')
    refused(menu(rows[:3] + [(10103, enc('Bad?'))] + rows[4:]), 'an undecodable name')
    refused(menu(rows[:11] + [(10100, enc('Again'))]), 'a duplicate id')
    refused(b'xxxx' + menu()[4:], 'not st2e')
    refused(menu()[:0x30], 'a truncated row table')
    ok += 1
    print(f'{ok}/2 PASS. licence board names: rows 5..16 / ids 10100..10111 read; reordered, shifted, missing, empty, '
          'undecodable, duplicate and malformed menu files refused')


if __name__ == '__main__':
    main()
