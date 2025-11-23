package visitor;

import syntaxtree.*;
import java.util.*;
import java.io.*;

public class BuildCFGAndLiveness extends GJDepthFirst<Void, Void> {

    public static class StmtInfo {
        public Node astNode;
        public int index;
        public String kind;
        public BitSet use = new BitSet();
        public BitSet def = new BitSet();
        public BitSet in = new BitSet();
        public BitSet out = new BitSet();
        public Set<Integer> succ = new LinkedHashSet<>();
        public Set<Integer> pred = new LinkedHashSet<>();

        public StmtInfo(Node ast, int idx, String k) {
            astNode = ast;
            index = idx;
            kind = k;
        }
    }

    public static class ProcedureInfo {
        public String name;
        public List<StmtInfo> nodes = new ArrayList<>();
        public Map<String, Integer> labelToIndex = new HashMap<>();
        public int argCount = 0;
        public int tempCount = 0;

        public ProcedureInfo(String n) {
            name = n;
        }
    }

    public Map<String, ProcedureInfo> procedures = new LinkedHashMap<>();

    private ProcedureInfo currentProc = null;
    private int currentIndex = 0;

    private int tempIdFromTemp(Temp t) {
        if (t == null)
            return -1;
        String v = t.f1.f0.tokenImage;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String labelNameFromLabel(Label l) {
        if (l == null)
            return null;
        return l.f0.tokenImage;
    }

    private void addStmt(Node stmtNode, String kind) {
        StmtInfo si = new StmtInfo(stmtNode, currentIndex, kind);
        currentProc.nodes.add(si);
        currentIndex++;
    }

    /**
     * f0 -> "MAIN"
     * f1 -> StmtList()
     * f2 -> "END"
     * f3 -> ( Procedure() )*
     * f4 -> <EOF>
     */
    @Override
    public Void visit(Goal n, Void argu) {
        currentProc = new ProcedureInfo("MAIN");
        currentProc.argCount = 0;
        procedures.put(currentProc.name, currentProc);
        currentIndex = 0;

        n.f1.accept(this, null);

        StmtInfo returnInfo = new StmtInfo(null, currentIndex, "RETURN");
        currentProc.nodes.add(returnInfo);
        currentIndex++;

        finalizeProcedure(currentProc);

        n.f3.accept(this, null);
        return null;
    }

    /**
     * f0 -> Label()
     * f1 -> "["
     * f2 -> IntegerLiteral()
     * f3 -> "]"
     * f4 -> StmtExp()
     */
    @Override
    public Void visit(Procedure n, Void argu) {
        String nm = labelNameFromLabel(n.f0);
        ProcedureInfo prev = currentProc;
        currentProc = new ProcedureInfo(nm);
        int argCount = Integer.parseInt(n.f2.f0.tokenImage);
        currentProc.argCount = argCount;
        procedures.put(currentProc.name, currentProc);
        currentIndex = 0;

        n.f4.f1.accept(this, null);

        StmtInfo returnInfo = new StmtInfo(null, currentIndex, "RETURN");
        computeUseDefForSimpleExp(n.f4.f3, returnInfo.use);
        currentProc.nodes.add(returnInfo);
        currentIndex++;

        finalizeProcedure(currentProc);
        currentProc = prev;
        return null;
    }

    /**
     * f0 -> ( ( Label() )? Stmt() )*
     */
    @Override
    public Void visit(StmtList n, Void argu) {
        n.f0.accept(this, null);
        return null;
    }

    /**
     * f0 -> <IDENTIFIER>
     */
    @Override
    public Void visit(Label n, Void argu) {
        if (currentProc != null) {
            String lab = labelNameFromLabel(n);
            currentProc.labelToIndex.put(lab, currentIndex);
        }
        return null;
    }

    /**
     * f0 -> "NOOP"
     */
    @Override
    public Void visit(NoOpStmt n, Void argu) {
        addStmt(n, "NOOP");
        return null;
    }

    /**
     * f0 -> "ERROR"
     */
    @Override
    public Void visit(ErrorStmt n, Void argu) {
        addStmt(n, "ERROR");
        return null;
    }

    /**
     * f0 -> "CJUMP"
     * f1 -> Temp()
     * f2 -> Label()
     */
    @Override
    public Void visit(CJumpStmt n, Void argu) {
        addStmt(n, "CJUMP");
        return null;
    }

    /**
     * f0 -> "JUMP"
     * f1 -> Label()
     */
    @Override
    public Void visit(JumpStmt n, Void argu) {
        addStmt(n, "JUMP");
        return null;
    }

    /**
     * f0 -> "HSTORE"
     * f1 -> Temp()
     * f2 -> IntegerLiteral()
     * f3 -> Temp()
     */
    @Override
    public Void visit(HStoreStmt n, Void argu) {
        addStmt(n, "HSTORE");
        return null;
    }

    /**
     * f0 -> "HLOAD"
     * f1 -> Temp()
     * f2 -> Temp()
     * f3 -> IntegerLiteral()
     */
    @Override
    public Void visit(HLoadStmt n, Void argu) {
        addStmt(n, "HLOAD");
        return null;
    }

    /**
     * f0 -> "MOVE"
     * f1 -> Temp()
     * f2 -> Exp()
     */
    @Override
    public Void visit(MoveStmt n, Void argu) {
        addStmt(n, "MOVE");
        return null;
    }

    /**
     * f0 -> "PRINT"
     * f1 -> SimpleExp()
     */
    @Override
    public Void visit(PrintStmt n, Void argu) {
        addStmt(n, "PRINT");
        return null;
    }

    /**
     * f0 -> "BEGIN"
     * f1 -> StmtList()
     * f2 -> "RETURN"
     * f3 -> SimpleExp()
     * f4 -> "END"
     */
    @Override
    public Void visit(StmtExp n, Void argu) {

        return null;
    }

    private void finalizeProcedure(ProcedureInfo proc) {
        int n = proc.nodes.size();
        if (n == 0)
            return;

        for (int i = 0; i < n; ++i) {
            StmtInfo si = proc.nodes.get(i);
            Node ast = si.astNode;

            if (ast == null && si.kind.equals("RETURN")) {
                si.succ.clear();
                continue;
            }

            boolean hasFallThrough = true;

            if (ast instanceof JumpStmt) {
                JumpStmt js = (JumpStmt) ast;
                String tgt = labelNameFromLabel(js.f1);
                Integer j = proc.labelToIndex.get(tgt);
                if (j != null && j < n)
                    si.succ.add(j);
                hasFallThrough = false;

            } else if (ast instanceof CJumpStmt) {
                CJumpStmt cs = (CJumpStmt) ast;
                String tgt = labelNameFromLabel(cs.f2);
                Integer j = proc.labelToIndex.get(tgt);
                if (j != null && j < n)
                    si.succ.add(j);
                hasFallThrough = true;

            } else if (ast instanceof ErrorStmt) {
                hasFallThrough = false;

            } else {
                hasFallThrough = true;
            }

            int fallThroughIndex = i + 1;
            if (hasFallThrough && fallThroughIndex < n) {
                si.succ.add(fallThroughIndex);
            }

        }

        for (int i = 0; i < n; ++i) {
            for (int sIdx : proc.nodes.get(i).succ) {
                if (sIdx < n) {
                    StmtInfo succNode = proc.nodes.get(sIdx);
                    if (!succNode.pred.contains(i)) {
                        succNode.pred.add(i);
                    }
                }
            }
        }

        for (int i = 0; i < n; ++i) {
            StmtInfo si = proc.nodes.get(i);
            computeUseDefForStmt(si, proc);
        }

        int maxTemp = -1;
        for (StmtInfo si : proc.nodes) {
            maxTemp = Math.max(maxTemp, si.use.length() == 0 ? -1 : si.use.length() - 1);
            maxTemp = Math.max(maxTemp, si.def.length() == 0 ? -1 : si.def.length() - 1);
        }
        maxTemp = Math.max(maxTemp, proc.argCount - 1);
        if (proc.name.equals("MAIN") && proc.nodes.size() > 0) {
            StmtInfo first = proc.nodes.get(0);
            if (first.astNode instanceof MoveStmt) {
                MoveStmt m = (MoveStmt) first.astNode;
                if (m.f2.f0.choice instanceof SimpleExp) {
                    SimpleExp se = (SimpleExp) m.f2.f0.choice;
                    if (se.f0.choice instanceof Temp) {
                        maxTemp = Math.max(maxTemp, tempIdFromTemp((Temp) se.f0.choice));
                    }
                }
            }
        }
        proc.tempCount = Math.max(0, maxTemp + 1);

        for (StmtInfo si : proc.nodes) {
            BitSet u = (BitSet) si.use.clone();
            BitSet d = (BitSet) si.def.clone();
            si.use = new BitSet(proc.tempCount);
            si.def = new BitSet(proc.tempCount);
            si.in = new BitSet(proc.tempCount);
            si.out = new BitSet(proc.tempCount);
            si.use.or(u);
            si.def.or(d);
        }

        boolean changed = true;
        while (changed) {
            changed = false;

            for (int i = n - 1; i >= 0; --i) {
                StmtInfo si = proc.nodes.get(i);
                BitSet oldIn = (BitSet) si.in.clone();
                BitSet oldOut = (BitSet) si.out.clone();

                si.out.clear();
                for (int s : si.succ) {
                    if (s < n) {
                        si.out.or(proc.nodes.get(s).in);
                    }
                }

                BitSet newIn = (BitSet) si.out.clone();
                newIn.andNot(si.def);
                newIn.or(si.use);

                if (!si.in.equals(newIn)) {
                    si.in = newIn;
                    changed = true;
                }

                if (!si.out.equals(oldOut)) {
                    changed = true;
                }
            }
        }

        int highest = -1;
        for (StmtInfo si : proc.nodes) {
            highest = Math.max(highest, si.in.length() - 1);
            highest = Math.max(highest, si.out.length() - 1);
            highest = Math.max(highest, si.def.length() - 1);
            highest = Math.max(highest, si.use.length() - 1);
        }
        proc.tempCount = Math.max(proc.tempCount, highest + 1);
    }

    private void computeUseDefForStmt(StmtInfo si, ProcedureInfo proc) {
        Node ast = si.astNode;
        if (ast == null && si.kind.equals("RETURN")) {
            return;
        }

        if (ast instanceof MoveStmt) {
            MoveStmt m = (MoveStmt) ast;
            int dst = tempIdFromTemp(m.f1);
            if (dst >= 0)
                si.def.set(dst);
            computeUseDefForExp(m.f2, si.use);
        } else if (ast instanceof PrintStmt) {
            PrintStmt p = (PrintStmt) ast;
            computeUseDefForSimpleExp(p.f1, si.use);
        } else if (ast instanceof HStoreStmt) {
            HStoreStmt h = (HStoreStmt) ast;
            int t1 = tempIdFromTemp(h.f1);
            int t2 = tempIdFromTemp(h.f3);
            if (t1 >= 0)
                si.use.set(t1);
            if (t2 >= 0)
                si.use.set(t2);
        } else if (ast instanceof HLoadStmt) {
            HLoadStmt h = (HLoadStmt) ast;
            int dst = tempIdFromTemp(h.f1);
            int base = tempIdFromTemp(h.f2);
            if (dst >= 0)
                si.def.set(dst);
            if (base >= 0)
                si.use.set(base);
        } else if (ast instanceof CJumpStmt) {
            CJumpStmt c = (CJumpStmt) ast;
            int t = tempIdFromTemp(c.f1);
            if (t >= 0)
                si.use.set(t);
        }

    }

    private void computeUseDefForExp(Exp exp, BitSet useSet) {
        if (exp == null || exp.f0 == null)
            return;
        Node choice = exp.f0.choice;

        if (choice instanceof Call) {
            Call c = (Call) choice;
            if (c.f1 != null)
                computeUseDefForSimpleExp(c.f1, useSet);

            if (c.f3 != null && c.f3.present()) {
                for (Node argNode : c.f3.nodes) {
                    if (argNode instanceof Exp) {
                        computeUseDefForExp((Exp) argNode, useSet);
                    } else if (argNode instanceof SimpleExp) {
                        computeUseDefForSimpleExp((SimpleExp) argNode, useSet);
                    } else if (argNode instanceof Temp) {
                        int argTemp = tempIdFromTemp((Temp) argNode);
                        if (argTemp >= 0)
                            useSet.set(argTemp);
                    }

                }
            }
        }

        else if (choice instanceof HAllocate) {
            HAllocate h = (HAllocate) choice;
            computeUseDefForSimpleExp(h.f1, useSet);
        } else if (choice instanceof BinOp) {
            BinOp b = (BinOp) choice;
            int t1 = tempIdFromTemp(b.f1);
            if (t1 >= 0)
                useSet.set(t1);
            computeUseDefForSimpleExp(b.f2, useSet);
        } else if (choice instanceof SimpleExp) {
            computeUseDefForSimpleExp((SimpleExp) choice, useSet);
        } else if (choice instanceof StmtExp) {
            StmtExp se = (StmtExp) choice;
            computeUseDefForSimpleExp(se.f3, useSet);
        }
    }

    private void computeUseDefForSimpleExp(SimpleExp simpleExp, BitSet useSet) {
        if (simpleExp == null || simpleExp.f0 == null)
            return;
        Node choice = simpleExp.f0.choice;
        if (choice instanceof Temp) {
            int tempId = tempIdFromTemp((Temp) choice);
            if (tempId >= 0)
                useSet.set(tempId);
        }

    }

    public void prettyPrintAll(PrintStream out) {
        for (Map.Entry<String, ProcedureInfo> e : procedures.entrySet()) {
            prettyPrintProcedure(e.getValue(), out);
        }
    }

    public void prettyPrintProcedure(ProcedureInfo p, PrintStream out) {
        out.println("Procedure: " + p.name);
        out.println("  label->index: " + p.labelToIndex);
        out.println("  tempCount: " + p.tempCount);
        out.println("  StmtIdx | kind   | use         | def         | in          | out         | succ");
        out.println("  ----------------------------------------------------------------------------------");
        for (StmtInfo si : p.nodes) {
            out.printf("  %5d | %-6s | %-11s | %-11s | %-11s | %-11s | %s\n",
                    si.index,
                    si.kind,
                    bitsetToString(si.use),
                    bitsetToString(si.def),
                    bitsetToString(si.in),
                    bitsetToString(si.out),
                    si.succ.toString());
        }
        out.println();
    }

    private String bitsetToString(BitSet b) {
        if (b == null)
            return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) {
            if (!first)
                sb.append(",");
            sb.append(i);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}