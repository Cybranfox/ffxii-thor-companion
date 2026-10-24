"""The 12 licence board names, from the user's own menu_command.bin (an st2e file, read by menumap_names.parse).
The game's licence screens look board j's title up as text key 1005 + j: group 1, row 5 + j, and those rows carry
ids 10100 + j (Job_Names_2026-10-24.md). Any other layout, a duplicate id, or a missing, empty or undecodable name
is refused: there are no built-in names to fall back to.
"""
import struct

from menumap_names import parse, MAX_BYTES

MENU_COMMAND = 'ps2data/image/ff12/test_battle/{lang}/binaryfile/menu_command.bin'
JOB_ROW = 5
JOB_TEXT_ID = 10100
BOARDS = 12


def job_names(raw):
    if len(raw) > MAX_BYTES or len(raw) < 0x20 or raw[:4] != b'st2e':
        raise ValueError('menu_command.bin: not st2e')
    count, stride, rows = struct.unpack_from('<3I', raw, 4)
    if count > 4096 or count < JOB_ROW + BOARDS or stride != 4 or rows < 0x20 or rows + count * stride > len(raw):
        raise ValueError('menu_command.bin: row table')
    ids = [struct.unpack_from('<H', raw, rows + i * stride)[0] for i in range(count)]
    if len(ids) != len(set(ids)):
        raise ValueError('menu_command.bin: duplicate text id')
    if ids[JOB_ROW:JOB_ROW + BOARDS] != list(range(JOB_TEXT_ID, JOB_TEXT_ID + BOARDS)):
        raise ValueError('menu_command.bin: unexpected layout of the job rows')
    labels = parse(raw)
    names = []
    for j in range(BOARDS):
        name = labels.get(JOB_TEXT_ID + j)
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f'menu_command.bin: no name for licence board {j + 1}')
        names.append(name)
    return names
