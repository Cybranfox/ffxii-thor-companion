"""Synthetic checks for the atlas-2 pixel hash in builder/mapatlas_pack.py (no game files needed)."""
import io
import hashlib
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mapatlas_pack  # noqa: E402

# The same vectors are in app/tests/AtlasTest.java: both builders must agree on them.
L8_2X1 = 'ff6104d9f6277429db12e85861f759425d3e954e8fcfb40d79c6b476fda37f52'
RGBA_1X2 = '0dd3a1419891c20d56adda387ea74bb2690ecdfbf1ac0f84b11fcc8daaaa1849'


def decoded(png):
    im = Image.open(io.BytesIO(png))
    im.load()
    return im


def main():
    ok = 0
    # 1. fixed vectors, including RGB kept under alpha 0
    assert mapatlas_pack.pixel_sha256(1, 2, 1, bytes([0, 255])) == L8_2X1
    rgba = bytes([10, 20, 30, 0, 255, 0, 128, 255])
    assert mapatlas_pack.pixel_sha256(4, 1, 2, rgba) == RGBA_1X2
    assert mapatlas_pack.pixel_sha256(4, 1, 2, bytes([0, 0, 0, 0]) + rgba[4:]) != RGBA_1X2
    ok += 1
    # 2. size and channels are part of the hash; wrong buffer sizes are refused
    assert mapatlas_pack.pixel_sha256(1, 1, 2, bytes([0, 255])) != L8_2X1
    for bad in [(1, 2, 2, bytes(3)), (3, 1, 1, bytes(3)), (4, 1, 1, bytes(3))]:
        try:
            mapatlas_pack.pixel_sha256(*bad)
            raise AssertionError('accepted a bad buffer')
        except ValueError:
            pass
    ok += 1
    # 3. a mask: the PNG decodes to the hashed samples, and the same pixels encoded differently keep the pixel hash
    rng = np.random.default_rng(7)
    mask = (rng.integers(0, 128, (128, 192)) * (rng.random((128, 192)) < 0.3)).astype(np.uint8)
    samples = mapatlas_pack.mask_samples(mask)
    assert samples.max() <= 254 and (samples == np.minimum(255, mask.astype(np.uint16) * 2)).all()
    want = mapatlas_pack.pixel_sha256(1, 192, 128, samples.tobytes())
    pngs = [mapatlas_pack.png_bytes(samples)]
    for level in (0, 1, 9):
        buf = io.BytesIO()
        Image.fromarray(samples, 'L').save(buf, 'PNG', compress_level=level)
        pngs.append(buf.getvalue())
    assert len(set(pngs)) > 1, 'test needs different encodings'
    for png in pngs:
        im = decoded(png)
        assert im.mode == 'L' and im.size == (192, 128)
        assert mapatlas_pack.pixel_sha256(1, 192, 128, im.tobytes()) == want
    ok += 1
    # 4. one changed pixel changes the hash
    changed = samples.copy()
    changed[5, 7] ^= 2
    assert mapatlas_pack.pixel_sha256(1, 192, 128, changed.tobytes()) != want
    ok += 1
    # 5. RGBA symbols: Pillow keeps RGB under alpha 0 through a PNG round trip, so the hash survives decoding
    px = bytes(b for i in range(16 * 8) for b in ((i * 7) & 255, (i * 13) & 255, (i * 29) & 255, 0 if i % 3 == 0 else 200))
    buf = io.BytesIO()
    Image.frombytes('RGBA', (16, 8), px).save(buf, 'PNG', optimize=True)
    im = decoded(buf.getvalue())
    assert im.mode == 'RGBA' and im.tobytes() == px
    assert mapatlas_pack.pixel_sha256(4, 16, 8, im.tobytes()) == mapatlas_pack.pixel_sha256(4, 16, 8, px)
    ok += 1
    # 6. receipts hash the file bytes, not the pixels
    r = mapatlas_pack.receipt('x.png', pngs[0])
    assert r['bytes'] == len(pngs[0]) and r['sha256'] == hashlib.sha256(pngs[0]).hexdigest()
    ok += 1
    print(f'{ok}/6 PASS. Atlas-2 pixel hash: fixed vectors, sizes in the hash, re-encoded PNGs keep it, '
          'one pixel changes it, RGBA keeps hidden RGB, file receipts')


if __name__ == '__main__':
    main()
