package visitor;

public class Result {
    StringBuilder code;
    String place;
    Result() { code = new StringBuilder(); place = null; }
    Result(String p) { code = new StringBuilder(); place = p; }
    public void append(Result r) {
        if (r == null) return;
        if (r.code.length() > 0) code.append(r.code);
        if (r.place != null) place = r.place;
    }
    public void addLine(String s) { code.append(s).append('\n'); }
    public String toStringOutput() { return code.toString(); }
} 