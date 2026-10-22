package local.thor.ffxiicompanion;

/**
 * Gambit action names come from the data pack's gambitActions (the game's menu wording) when present, and the editor
 * never writes the reserved records 0x6F..0x7B that sit inside the item range (Action_IDs_and_Set_Switch_2026-10-22).
 */
public final class ActionNamesTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        Reference withActions = Reference.parse("{\"format\":\"ffxii-companion-reference-1\",\"kind\":\"REFERENCE\",\"gear\":["
                + "{\"id\":25,\"name\":\"Item 25 (long description name)\",\"category\":\"items\",\"tags\":[],\"lines\":[]}],"
                + "\"bestiary\":[],\"tips\":[],\"gambitActions\":{\"006b\":\"Item 25\",\"0000\":\"Spell 0\"}}");
        check(withActions != null && withActions.hasActions(), "parsed gambitActions");
        check(Gambits.action(0x6B, withActions).equals("Item 25"), "menu wording wins over the inventory text");
        check(Gambits.action(0x0000, withActions).equals("Spell 0"), "magick 0 by its menu name");
        Reference without = Reference.parse("{\"format\":\"ffxii-companion-reference-1\",\"kind\":\"REFERENCE\",\"gear\":["
                + "{\"id\":25,\"name\":\"Item 25\",\"category\":\"items\",\"tags\":[],\"lines\":[]}],\"bestiary\":[],\"tips\":[]}");
        check(without != null && !without.hasActions() && Gambits.action(0x6B, without).equals("Item 25"), "older packs: inventory mapping");
        for (int a = 0x6F; a <= 0x7B; a++) check(!GambitEdit.validAction(a), "reserved " + Integer.toHexString(a));
        check(GambitEdit.validAction(0x6E) && GambitEdit.validAction(0x7C) && GambitEdit.validAction(0x52) && GambitEdit.validAction(0x91), "real items stay valid");
        check(GambitEdit.validAction(0x0000) && GambitEdit.validAction(0x96) && !GambitEdit.validAction(0x51), "magick, attack, gap");
        System.out.println("PASS ActionNamesTest 7 checks (menu-exact action names from the pack; reserved 0x6F-0x7B never written)");
    }
}
