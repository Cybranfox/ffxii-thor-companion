package local.thor.ffxiicompanion;

/** Synthetic strings only. Unknown optional metadata stays unknown. */
public final class StatusDataTest {
    static int n;
    static void check(boolean ok){if(!ok)throw new AssertionError("status model check "+n);n++;}
    public static void main(String[] args){
        String base="{\"format\":\"ffxii-companion-reference-1\",\"kind\":\"REFERENCE\",\"gear\":[{\"id\":1,\"name\":\"Synthetic\"}]";
        Reference old=Reference.parse(base+"}");
        check(old.statusName(0)==null);check(old.adverse(0)==null);check(old.elementName(0)==null);
        Reference r=Reference.parse(base+",\"statusNames\":{\"0\":\"S0\",\"31\":\"S31\",\"32\":\"bad\",\"-1\":\"bad\",\"x\":1,\"1\":\"\"},\"statusDisplay\":{\"0\":{\"adverse\":true},\"31\":{\"adverse\":false},\"1\":{\"adverse\":\"true\"},\"32\":{\"adverse\":true}},\"elementNames\":{\"0\":\"E0\",\"7\":\"E7\",\"8\":\"bad\"}}");
        check("S0".equals(r.statusName(0)));check("S31".equals(r.statusName(31)));check(Boolean.TRUE.equals(r.adverse(0)));check(Boolean.FALSE.equals(r.adverse(31)));
        check(r.statusName(32)==null&&r.statusName(-1)==null&&r.statusName(1)==null);check(r.adverse(1)==null&&r.adverse(32)==null);
        check("E0".equals(r.elementName(0))&&"E7".equals(r.elementName(7)));check(r.elementName(8)==null);
        System.out.println("PASS status model "+n+" checks");
    }
}
