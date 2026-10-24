package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.16.0: a reference page's lines as blocks for the detail view: "## " headings, chance rows ("[40%]   Earth Crystal")
 * and everything else as prose, in the original order. Text is kept verbatim; nothing is classified by its English
 * wording, so modded or other-language pages fall back to prose instead of breaking.
 */
final class PageTable {
    enum Kind { HEADING, ROW, PROSE, BLANK }

    static final class Block {
        final Kind kind;
        final String text;      // heading / item / prose line
        final String chance;    // ROW only: "40%" as written

        Block(Kind kind, String text, String chance) {
            this.kind = kind;
            this.text = text;
            this.chance = chance;
        }
    }

    private static final Pattern ROW = Pattern.compile("^\\[([0-9]{1,3}(?:\\.[0-9]+)?)%\\]\\s+(\\S.*)$");

    private PageTable() {}

    static List<Block> parse(List<String> lines) {
        List<Block> out = new ArrayList<>();
        if (lines == null) return out;
        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            if (line.trim().isEmpty()) {
                if (!out.isEmpty() && out.get(out.size() - 1).kind != Kind.BLANK) out.add(new Block(Kind.BLANK, "", null));
                continue;
            }
            if (line.startsWith("## ") && line.length() > 3) {
                out.add(new Block(Kind.HEADING, line.substring(3).trim(), null));
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (m.matches() && Double.parseDouble(m.group(1)) <= 100) {
                out.add(new Block(Kind.ROW, m.group(2).trim(), m.group(1) + "%"));
                continue;
            }
            out.add(new Block(Kind.PROSE, line, null));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).kind == Kind.BLANK) out.remove(out.size() - 1);
        return out;
    }
}
