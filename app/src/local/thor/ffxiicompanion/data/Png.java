package local.thor.ffxiicompanion.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal deterministic PNG writer for the atlas: 8-bit grayscale (L8) or 8-bit RGBA (straight alpha), filter None on
 * every row, one IDAT, zlib level 9. Its bytes differ from Pillow's (that's why atlas 2 hashes pixels, not files);
 * the pixels decode identically. Bounded: width/height 1..4096.
 */
final class Png {
    private Png() {}

    static byte[] encode(int channels, int w, int h, byte[] samples) throws IOException {
        if ((channels != 1 && channels != 4) || w < 1 || h < 1 || w > 4096 || h > 4096
                || samples.length != channels * w * h) throw new IOException("png: bad image size");
        byte[] raw = new byte[h * (1 + channels * w)];
        int o = 0;
        for (int y = 0; y < h; y++) {
            raw[o++] = 0;                                   // filter type None
            System.arraycopy(samples, y * channels * w, raw, o, channels * w);
            o += channels * w;
        }
        Deflater d = new Deflater(9);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        byte[] ihdr = new byte[13];
        be32(ihdr, 0, w);
        be32(ihdr, 4, h);
        ihdr[8] = 8;                                        // bit depth
        ihdr[9] = (byte) (channels == 1 ? 0 : 6);           // colour type: grayscale / RGBA
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4], t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII), crc = new byte[4];
        be32(len, 0, data.length);
        CRC32 c = new CRC32();
        c.update(t);
        c.update(data);
        be32(crc, 0, (int) c.getValue());
        out.write(len, 0, 4);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        out.write(crc, 0, 4);
    }

    private static void be32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16); b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v;
    }
}
