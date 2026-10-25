package local.thor.ffxiicompanion;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch-only browser for one Reference list (gear or bestiary): a filter chip row, a list, and a detail page.
 * The companion window is non-focusable so the game keeps the controller; hence chips and taps, no text search.
 * Every page carries the REFERENCE label: this is data from the user's mod files, not live game state.
 */
final class ReferenceView extends LinearLayout {
    interface Source {
        List<String> filters();
        List<Reference.Entry> entries(String filter);   // filter null = all
        String subtitle(Reference.Entry e);
    }

    private final int text, dim, accent, card, bg;
    private final float density;
    private final LinearLayout chips;
    private final ListView list;
    private final ScrollView detail;
    private final TextView detailTitle, detailSub, label;
    private final LinearLayout detailBox;   // 0.16.0: headings, chance tables and prose as separate views
    private Source source;
    private String filter;
    private List<Reference.Entry> shown = new ArrayList<>();

    private final boolean paper;                         // 0.17.0: the game's parchment pages (Bestiary)

    ReferenceView(Context c, int text, int dim, int accent, int card, int bg) {
        this(c, text, dim, accent, card, bg, false);
    }

    ReferenceView(Context c, int text, int dim, int accent, int card, int bg, boolean paper) {
        super(c);
        this.text = text; this.dim = dim; this.accent = accent; this.card = card; this.bg = bg;
        this.paper = paper;
        if (paper) {
            setBackground(Theme.paper(c.getResources().getDisplayMetrics().density * 520));
            setPadding(Math.round(12 * c.getResources().getDisplayMetrics().density), Math.round(8 * c.getResources().getDisplayMetrics().density),
                    Math.round(12 * c.getResources().getDisplayMetrics().density), 0);
        }
        density = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        label = tv("", 11, dim, false);
        label.setPadding(dp(4), dp(2), dp(4), dp(4));
        addView(label);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(c);
        hs.addView(chips);
        addView(hs);

        list = new ListView(c);
        list.setDivider(new android.graphics.drawable.ColorDrawable(paper ? 0x40302F2B : Theme.RULE));   // 0.17.0: hairlines
        list.setDividerHeight(dp(1));
        list.setOnItemClickListener((parent, v, pos, id) -> open(shown.get(pos)));
        LayoutParams lp = new LayoutParams(-1, 0, 1);
        lp.topMargin = dp(6);
        addView(list, lp);

        detail = new ScrollView(c);
        LinearLayout d = new LinearLayout(c);
        d.setOrientation(VERTICAL);
        d.setPadding(dp(10), dp(8), dp(10), dp(10));
        d.setBackground(Theme.section(dp(1)));                // 0.17.0: no box
        TextView back = tv("◂ Back to list", 13, accent, true);
        back.setPadding(0, dp(2), 0, dp(8));
        back.setOnClickListener(v -> showList());
        d.addView(back);
        detailTitle = tv("", 18, text, true);
        d.addView(detailTitle);
        detailSub = tv("", 13, dim, false);
        detailSub.setPadding(0, dp(2), 0, 0);
        d.addView(detailSub);
        detailBox = new LinearLayout(c);
        detailBox.setOrientation(VERTICAL);
        detailBox.setPadding(0, dp(4), 0, 0);
        d.addView(detailBox);
        detail.addView(d);
        detail.setVisibility(GONE);
        addView(detail, new LayoutParams(-1, 0, 1));
    }

    /** (Re)binds the chips. A rebind keeps the selected chip when it still exists (0.19.1); otherwise "All". */
    void bind(Source s, String labelText) {
        String keep = filter;
        source = s;
        label.setText(labelText);
        label.setVisibility(labelText == null || labelText.isEmpty() ? GONE : VISIBLE);
        chips.removeAllViews();
        addChip(null, "All");
        List<String> fs = s.filters();
        for (String f : fs) addChip(f, f);
        select(keep != null && fs.contains(keep) ? keep : null);
    }

    private void addChip(String value, String title) {
        TextView t = tv(title, 12, text, true);
        t.setSingleLine(true);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setTag(value);
        t.setOnClickListener(v -> select(value));
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        chips.addView(t, lp);
    }

    private void select(String f) {
        filter = f;
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView t = (TextView) chips.getChildAt(i);
            boolean on = f == null ? t.getTag() == null : f.equals(t.getTag());
            t.setBackground(on ? round(Theme.MARK, 0) : (paper ? null : round(card, 12)));
            t.setTextColor(text);
            t.setPadding(on ? dp(14) : dp(10), dp(4), dp(10), dp(4));
        }
        shown = source.entries(f);
        list.setAdapter(new Adapter());
        showList();
    }

    private void showList() {
        detail.setVisibility(GONE);
        list.setVisibility(VISIBLE);
    }

    /** Shows one entry's detail page (also used by the Targeting card, 0.13.0). */
    void open(Reference.Entry e) {
        detailTitle.setText(e.name);
        String sub = source.subtitle(e);
        detailSub.setText(sub == null ? "" : sub);
        detailSub.setVisibility(sub == null || sub.isEmpty() ? GONE : VISIBLE);
        detailBox.removeAllViews();
        // 0.16.0: "[40%]   Earth Crystal" rows as a table (item left, chance right), "## " lines as headings; any
        // other line stays as written, so pages that don't follow the pattern still read as before.
        for (PageTable.Block bl : PageTable.parse(e.lines)) {
            switch (bl.kind) {
                case HEADING: {
                    TextView h = tv(bl.text.toUpperCase(), 12, paper ? Theme.INK_ACCENT : Theme.HEAD, true);
                    h.setLetterSpacing(0.08f);
                    h.setPadding(0, dp(10), 0, dp(2));
                    detailBox.addView(h);
                    break;
                }
                case ROW: {
                    LinearLayout row = new LinearLayout(getContext());
                    row.setPadding(dp(6), dp(3), dp(6), dp(3));
                    row.setBackground(Theme.section(dp(1)));          // a ruled table, as the game's
                    TextView item = tv(bl.text, 14, text, false);
                    row.addView(item, new LayoutParams(0, -2, 1));
                    TextView pct = tv(bl.chance, 14, paper ? Theme.INK_ACCENT : Theme.GOLD, true);
                    pct.setGravity(Gravity.END);
                    row.addView(pct, new LayoutParams(dp(64), -2));
                    LayoutParams lp = new LayoutParams(-1, -2);
                    lp.topMargin = dp(2);
                    detailBox.addView(row, lp);
                    break;
                }
                case BLANK: {
                    detailBox.addView(new View(getContext()), new LayoutParams(-1, dp(8)));
                    break;
                }
                default: {
                    TextView t = tv(bl.text, 14, text, false);
                    detailBox.addView(t);
                }
            }
        }
        list.setVisibility(GONE);
        detail.setVisibility(VISIBLE);
        detail.scrollTo(0, 0);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : newRow();
            Reference.Entry e = shown.get(i);
            ((TextView) row.getChildAt(0)).setText(e.name);
            ((TextView) row.getChildAt(1)).setText(source.subtitle(e));
            return row;
        }
    }

    private LinearLayout newRow() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(VERTICAL);
        r.setPadding(dp(8), dp(6), dp(8), dp(6));
        TextView n = tv("", 15, text, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(n);
        TextView s = tv("", 12, dim, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(s);
        return r;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = Theme.label(getContext(), s, sp, color, bold);
        t.setGravity(Gravity.START);
        if (paper) t.setShadowLayer(0, 0, 0, 0);               // ink on paper: no dark edge
        return t;
    }

    private android.graphics.drawable.Drawable round(int color, int radiusDp) {
        return Theme.fill(color, dp(radiusDp), dp(1));
    }

    private int dp(int v) { return Math.round(v * density); }
}
