package visitor;

import syntaxtree.*;
import java.util.*;

public class MicroIRtoMiniRAVisitor extends GJDepthFirst<Void, String> {

    private final Map<String, BuildCFGAndLiveness.ProcedureInfo> livenessData;
    private BuildCFGAndLiveness.ProcedureInfo currentProc;

    private static class LiveInterval implements Comparable<LiveInterval> {
        int tempId;
        int start;
        int end;
        String assignedReg = null;
        Integer spillSlot = null;

        LiveInterval(int t) {
            tempId = t;
            start = -1;
            end = -1;
        }

        @Override
        public int compareTo(LiveInterval other) {
            return Integer.compare(this.start, other.start);
        }

        @Override
        public String toString() {
            String alloc = assignedReg != null ? assignedReg
                    : (spillSlot != null ? "SPILLED[" + spillSlot + "]" : "NONE");
            return "T" + tempId + " [" + start + ", " + end + "] -> " + alloc;
        }
    }

    private static final String[] S_REGS = { "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7" };
    private static final String[] T_REGS = { "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9" };

    private static final String[] A_REGS = { "a0", "a1", "a2", "a3" };
    private static final String[] V_REGS = { "v0", "v1" };

    private List<String> freeRegisters;
    private List<LiveInterval> active;
    private Map<Integer, LiveInterval> intervalMap;
    private int spillCount;
    private int sRegUsageCount;
    private int stackSlotOffset;
    private int maxCallArgs;

    private final StringBuilder output;
    private boolean inProcedure;
    private String currentLabel = null;

    public MicroIRtoMiniRAVisitor(BuildCFGAndLiveness liveness) {
        this.livenessData = liveness.procedures;
        this.output = new StringBuilder();
    }

    public String getMiniRACode() {
        return output.toString();
    }

    private void emit(String... parts) {
        if (currentLabel != null) {
            output.append(currentLabel).append(" ");
            currentLabel = null;
        } else {
            output.append("    ");
        }
        for (String p : parts) {
            output.append(p).append(" ");
        }
        output.append("\n");
    }

    private int getTempId(Temp n) {
        if (n == null || n.f1 == null || n.f1.f0 == null)
            return -1;
        try {
            return Integer.parseInt(n.f1.f0.tokenImage);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String getSimpleExpStr(SimpleExp n) {
        if (n.f0.choice instanceof Temp) {
            return getRegForRead((Temp) n.f0.choice, V_REGS[1]);
        }
        if (n.f0.choice instanceof IntegerLiteral) {
            return ((IntegerLiteral) n.f0.choice).f0.tokenImage;
        }
        if (n.f0.choice instanceof Label) {
            return ((Label) n.f0.choice).f0.tokenImage;
        }
        return "ERROR_SIMPLE_EXP";
    }

    private String getRegForRead(Temp t, String scratchReg) {
        LiveInterval i = intervalMap.get(getTempId(t));
        if (i.assignedReg != null) {
            return i.assignedReg;
        }
        emit("ALOAD", scratchReg, "SPILLEDARG", String.valueOf(i.spillSlot + stackSlotOffset));
        return scratchReg;
    }

    private String getRegForSimpleExp(SimpleExp se, String scratchReg) {
        if (se.f0.choice instanceof Temp) {
            return getRegForRead((Temp) se.f0.choice, scratchReg);
        }
        String literal = (se.f0.choice instanceof IntegerLiteral)
                ? ((IntegerLiteral) se.f0.choice).f0.tokenImage
                : ((Label) se.f0.choice).f0.tokenImage;
        emit("MOVE", scratchReg, literal);
        return scratchReg;
    }

    private String getRegForWrite(Temp t, String scratchReg) {
        LiveInterval i = intervalMap.get(getTempId(t));
        if (i.assignedReg != null) {
            return i.assignedReg;
        }
        return scratchReg;
    }

    private void releaseRegForWrite(Temp t, String scratchReg) {
        LiveInterval i = intervalMap.get(getTempId(t));
        if (i.spillSlot != null) {
            emit("ASTORE", "SPILLEDARG", String.valueOf(i.spillSlot + stackSlotOffset), scratchReg);
        }
    }

    private void buildLiveIntervals() {
        intervalMap = new HashMap<>();
        int tempCount = currentProc.tempCount;
        for (int i = 0; i < tempCount; i++) {
            intervalMap.put(i, new LiveInterval(i));
        }

        for (int i = 0; i < currentProc.nodes.size(); i++) {
            BuildCFGAndLiveness.StmtInfo stmt = currentProc.nodes.get(i);
            BitSet live = (BitSet) stmt.in.clone();
            live.or(stmt.def);

            for (int t = live.nextSetBit(0); t >= 0; t = live.nextSetBit(t + 1)) {
                if (t < tempCount) {
                    LiveInterval interval = intervalMap.get(t);
                    if (interval.start == -1) {
                        interval.start = i;
                    }
                    interval.end = i;
                }
            }
        }
    }

    private void linearScanAllocate() {
        spillCount = 0;
        freeRegisters = new LinkedList<>();
        Collections.addAll(freeRegisters, T_REGS);
        Collections.addAll(freeRegisters, S_REGS);
        active = new ArrayList<>();
        List<LiveInterval> intervals = new ArrayList<>();
        for (LiveInterval i : intervalMap.values()) {
            if (i.start != -1) {
                intervals.add(i);
            }
        }
        Collections.sort(intervals);
        final int totalRegs = T_REGS.length + S_REGS.length;
        for (LiveInterval cur : intervals) {
            expireOldIntervals(cur);
            if (freeRegisters.isEmpty()) {
                spillAtInterval(cur);
            } else {
                String reg = freeRegisters.remove(0);
                cur.assignedReg = reg;
                active.add(cur);
            }
        }
    }

    private void expireOldIntervals(LiveInterval i) {
        Iterator<LiveInterval> it = active.iterator();
        while (it.hasNext()) {
            LiveInterval j = it.next();
            if (j.end < i.start) {
                it.remove();
                freeRegisters.add(j.assignedReg);
            }
        }
    }

    private void spillAtInterval(LiveInterval i) {
        LiveInterval farthest = null;
        int farIdx = -1;
        for (int k = 0; k < active.size(); ++k) {
            LiveInterval cand = active.get(k);
            if (farthest == null || cand.end > farthest.end) {
                farthest = cand;
                farIdx = k;
            }
        }

        if (farthest == null) {
            i.spillSlot = spillCount++;
            return;
        }

        if (farthest.end > i.end && farthest.assignedReg != null) {
            i.assignedReg = farthest.assignedReg;
            farthest.assignedReg = null;
            farthest.spillSlot = spillCount++;
            active.remove(farIdx);
            active.add(i);
        } else {
            i.spillSlot = spillCount++;
        }
    }

    private String[] pickUnusedScratches() {
        Set<String> assignedRegs = new HashSet<>();
        for (LiveInterval li : intervalMap.values()) {
            if (li.assignedReg != null)
                assignedRegs.add(li.assignedReg);
        }
        String first = null, second = null;
        for (String r : T_REGS) {
            if (!assignedRegs.contains(r)) {
                if (first == null)
                    first = r;
                else if (second == null) {
                    second = r;
                    break;
                }
            }
        }
        if (first == null)
            first = V_REGS[1];
        if (second == null)
            second = V_REGS[0];
        return new String[] { first, second };
    }

    /**
     * f0 -> "MAIN"
     * f1 -> StmtList()
     * f2 -> "END"
     * f3 -> ( Procedure() )*
     * f4 -> <EOF>
     */
    @Override
    public Void visit(Goal n, String argu) {
        inProcedure = false;
        currentProc = livenessData.get("MAIN");
        buildLiveIntervals();
        linearScanAllocate();
        CallFinderVisitor callFinder = new CallFinderVisitor();
        n.f1.accept(callFinder, null);
        maxCallArgs = callFinder.maxArgs;
        int tRegSaveSpace = callFinder.hasCall ? 10 : 0;
        Set<String> sRegsUsed = new HashSet<>();
        for (LiveInterval i : intervalMap.values()) {
            if (i.assignedReg != null && i.assignedReg.startsWith("s")) {
                sRegsUsed.add(i.assignedReg);
            }
        }
        sRegUsageCount = sRegsUsed.size();
        int incomingStackSlots = 0;
        int outgoingStackSlots = Math.max(0, maxCallArgs - 4);
        int stackSize = incomingStackSlots + sRegUsageCount + spillCount + tRegSaveSpace + outgoingStackSlots;
        stackSlotOffset = incomingStackSlots + sRegUsageCount;
        output.append("MAIN [0] [").append(stackSize).append("] [").append(maxCallArgs).append("]\n");
        int sRegSlot = incomingStackSlots;
        for (String sReg : S_REGS) {
            if (sRegsUsed.contains(sReg)) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(sRegSlot++), sReg);
            }
        }

        n.f1.accept(this, argu);

        sRegSlot = incomingStackSlots;
        for (String sReg : S_REGS) {
            if (sRegsUsed.contains(sReg)) {
                emit("ALOAD", sReg, "SPILLEDARG", String.valueOf(sRegSlot++));
            }
        }

        output.append("END\n");
        if (spillCount > 0)
            emit("// SPILLED");
        else
            emit("// NOTSPILLED");

        n.f3.accept(this, argu);

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
    public Void visit(Procedure n, String argu) {
        inProcedure = true;
        String procName = n.f0.f0.tokenImage;
        int argCount = Integer.parseInt(n.f2.f0.tokenImage);
        currentProc = livenessData.get(procName);
        buildLiveIntervals();
        linearScanAllocate();
        CallFinderVisitor callFinder = new CallFinderVisitor();
        n.f4.accept(callFinder, null);
        maxCallArgs = callFinder.maxArgs;
        int tRegSaveSpace = callFinder.hasCall ? 10 : 0;
        Set<String> sRegsUsed = new HashSet<>();
        for (LiveInterval i : intervalMap.values()) {
            if (i.assignedReg != null && i.assignedReg.startsWith("s")) {
                sRegsUsed.add(i.assignedReg);
            }
        }
        sRegUsageCount = sRegsUsed.size();
        int incomingStackSlots = Math.max(0, argCount - 4);
        int outgoingStackSlots = Math.max(0, maxCallArgs - 4);
        int stackSize = incomingStackSlots + sRegUsageCount + spillCount + tRegSaveSpace + outgoingStackSlots;
        stackSlotOffset = incomingStackSlots + sRegUsageCount;
        output.append("\n").append(procName);
        output.append(" [").append(argCount).append("]");
        output.append(" [").append(stackSize).append("]");
        output.append(" [").append(maxCallArgs).append("]\n");
        int sRegSlot = incomingStackSlots;
        for (String sReg : S_REGS) {
            if (sRegsUsed.contains(sReg)) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(sRegSlot++), sReg);
            }
        }

        for (int i = 0; i < argCount; i++) {
            Temp t = new Temp(new NodeToken("TEMP"), new IntegerLiteral(new NodeToken(String.valueOf(i))));
            String writeReg = getRegForWrite(t, V_REGS[1]);

            if (i < 4) {
                emit("MOVE", writeReg, A_REGS[i]);
            } else {
                emit("ALOAD", writeReg, "SPILLEDARG", String.valueOf(i - 4));
            }
            releaseRegForWrite(t, V_REGS[1]);
        }

        n.f4.accept(this, argu);

        sRegSlot = incomingStackSlots;
        for (String sReg : S_REGS) {
            if (sRegsUsed.contains(sReg)) {
                emit("ALOAD", sReg, "SPILLEDARG", String.valueOf(sRegSlot++));
            }
        }

        output.append("END\n");
        if (spillCount > 0)
            emit("// SPILLED");
        else
            emit("// NOTSPILLED");

        return null;
    }

    /**
     * f0 -> ( ( Label() )? Stmt() )*
     */
    @Override
    public Void visit(StmtList n, String argu) {
        if (n.f0.present()) {
            for (Node node : n.f0.nodes) {
                NodeSequence seq = (NodeSequence) node;

                NodeOptional labelOpt = (NodeOptional) seq.nodes.elementAt(0);
                if (labelOpt.present()) {
                    Label l = (Label) labelOpt.node;
                    currentLabel = l.f0.tokenImage;
                }

                Node stmtNode = seq.nodes.elementAt(1);
                stmtNode.accept(this, argu);
            }
        }
        return null;
    }

    /**
     * f0 -> "NOOP"
     */
    @Override
    public Void visit(NoOpStmt n, String argu) {
        emit("NOOP");
        return null;
    }

    /**
     * f0 -> "ERROR"
     */
    @Override
    public Void visit(ErrorStmt n, String argu) {
        emit("ERROR");
        return null;
    }

    /**
     * f0 -> "CJUMP"
     * f1 -> Temp()
     * f2 -> Label()
     */
    @Override
    public Void visit(CJumpStmt n, String argu) {
        String reg = getRegForRead(n.f1, T_REGS[9]);
        String label = n.f2.f0.tokenImage;
        emit("CJUMP", reg, label);
        return null;
    }

    /**
     * f0 -> "JUMP"
     * f1 -> Label()
     */
    @Override
    public Void visit(JumpStmt n, String argu) {
        emit("JUMP", n.f1.f0.tokenImage);
        return null;
    }

    /**
     * f0 -> "HSTORE"
     * f1 -> Temp()
     * f2 -> IntegerLiteral()
     * f3 -> Temp()
     */
    @Override
    public Void visit(HStoreStmt n, String argu) {
        String baseReg = getRegForRead(n.f1, V_REGS[1]);
        String offset = n.f2.f0.tokenImage;
        String srcReg = getRegForRead(n.f3, T_REGS[9]);
        emit("HSTORE", baseReg, offset, srcReg);
        return null;
    }

    /**
     * f0 -> "HLOAD"
     * f1 -> Temp()
     * f2 -> Temp()
     * f3 -> IntegerLiteral()
     */
    @Override
    public Void visit(HLoadStmt n, String argu) {
        String dstReg = getRegForWrite(n.f1, V_REGS[1]);
        String baseReg = getRegForRead(n.f2, T_REGS[9]);
        String offset = n.f3.f0.tokenImage;
        emit("HLOAD", dstReg, baseReg, offset);
        releaseRegForWrite(n.f1, V_REGS[1]);
        return null;
    }

    /**
     * f0 -> "MOVE"
     * f1 -> Temp()
     * f2 -> Exp()
     */
    @Override
    public Void visit(MoveStmt n, String argu) {
        if (n.f2.f0.choice instanceof Call) {
            Call call = (Call) n.f2.f0.choice;

            int tRegSaveSlot = stackSlotOffset + spillCount;
            for (int i = 0; i < T_REGS.length; i++) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(tRegSaveSlot + i), T_REGS[i]);
            }

            int argNum = 0;
            if (call.f3 != null && call.f3.present()) {
                for (Node argNode : call.f3.nodes) {
                    if (argNode instanceof Temp) {
                        Temp argTemp = (Temp) argNode;
                        String argReg = getRegForRead(argTemp, V_REGS[1]);
                        if (argNum < 4)
                            emit("MOVE", A_REGS[argNum], argReg);
                        else
                            emit("PASSARG", String.valueOf(argNum - 3), argReg);
                    } else if (argNode instanceof SimpleExp) {
                        String argReg = getRegForSimpleExp((SimpleExp) argNode, V_REGS[1]);
                        if (argNum < 4)
                            emit("MOVE", A_REGS[argNum], argReg);
                        else
                            emit("PASSARG", String.valueOf(argNum - 3), argReg);
                    } else if (argNode instanceof Exp) {
                        Exp argExp = (Exp) argNode;
                        if (argExp.f0.choice instanceof SimpleExp) {
                            SimpleExp se = (SimpleExp) argExp.f0.choice;
                            String argReg = getRegForSimpleExp(se, V_REGS[1]);
                            if (argNum < 4) {
                                emit("MOVE", A_REGS[argNum], argReg);
                            } else {
                                emit("PASSARG", String.valueOf(argNum - 3), argReg);
                            }
                        } else {
                            String argReg = V_REGS[1];

                            if (argExp.f0.choice instanceof BinOp) {
                                BinOp b = (BinOp) argExp.f0.choice;

                                String src2 = getRegForSimpleExp(b.f2, T_REGS[9]);
                                String src1 = getRegForRead(b.f1, V_REGS[1]);

                                String compute = T_REGS[9];

                                emit("MOVE", compute, ((NodeToken) b.f0.f0.choice).tokenImage, src1, src2);
                                emit("MOVE", argReg, compute);

                            } else if (argExp.f0.choice instanceof HAllocate) {
                                HAllocate h = (HAllocate) argExp.f0.choice;
                                String sizeReg = getRegForSimpleExp(h.f1, T_REGS[9]);
                                String compute = T_REGS[9];
                                if (!sizeReg.equals(compute))
                                    emit("MOVE", compute, sizeReg);
                                emit("MOVE", argReg, "HALLOCATE", compute);
                            } else {
                                emit("MOVE", argReg, "0");
                            }

                            if (argNum < 4) {
                                emit("MOVE", A_REGS[argNum], argReg);
                            } else {
                                emit("PASSARG", String.valueOf(argNum - 3), argReg);
                            }
                        }
                    }

                    argNum++;
                }
            }

            String funcReg = getRegForSimpleExp(call.f1, V_REGS[1]);
            emit("CALL", funcReg);

            tRegSaveSlot = stackSlotOffset + spillCount;
            for (int i = 0; i < T_REGS.length; i++) {
                emit("ALOAD", T_REGS[i], "SPILLEDARG", String.valueOf(tRegSaveSlot + i));
            }

            String dstReg = getRegForWrite(n.f1, V_REGS[1]);
            emit("MOVE", dstReg, V_REGS[0]);
            releaseRegForWrite(n.f1, V_REGS[1]);

            return null;
        }

        String dstReg = getRegForWrite(n.f1, V_REGS[1]);

        if (n.f2.f0.choice instanceof HAllocate) {

            HAllocate halloc = (HAllocate) n.f2.f0.choice;
            String sizeReg = getRegForSimpleExp(halloc.f1, T_REGS[9]);

            emit("MOVE", dstReg, "HALLOCATE", sizeReg);

        } else if (n.f2.f0.choice instanceof BinOp) {

            BinOp binOp = (BinOp) n.f2.f0.choice;
            String op = ((NodeToken) binOp.f0.f0.choice).tokenImage;

            String[] scratches = pickUnusedScratches();
            final String SCR1 = scratches[0];
            final String SCR2 = scratches[1];

            String src2Reg = getRegForSimpleExp(binOp.f2, SCR2);

            String src1Scratch = SCR1;
            if (src2Reg.equals(src1Scratch))
                src1Scratch = SCR2;
            String src1Reg = getRegForRead(binOp.f1, src1Scratch);

            LiveInterval dstInterval = intervalMap.get(getTempId(n.f1));
            String dstAssignedReg = dstInterval.assignedReg;
            String computeReg = null;

            if (dstAssignedReg == null) {
                computeReg = SCR1;
                if (computeReg.equals(src1Reg) || computeReg.equals(src2Reg))
                    computeReg = SCR2;
                if (computeReg.equals(src1Reg) || computeReg.equals(src2Reg)) {
                    computeReg = V_REGS[1];
                    if (computeReg.equals(src1Reg) || computeReg.equals(src2Reg))
                        computeReg = V_REGS[0];
                }
            } else {
                if (dstAssignedReg.equals(src1Reg) || dstAssignedReg.equals(src2Reg)) {
                    computeReg = SCR1;
                    if (computeReg.equals(src1Reg) || computeReg.equals(src2Reg))
                        computeReg = SCR2;
                    if (computeReg.equals(src1Reg) || computeReg.equals(src2Reg)) {
                        computeReg = V_REGS[1];
                        if (computeReg.equals(src1Reg) || computeReg.equals(src2Reg))
                            computeReg = V_REGS[0];
                    }
                } else {
                    computeReg = dstAssignedReg;
                }
            }

            emit("MOVE", computeReg, op, src1Reg, src2Reg);

            if (dstInterval.spillSlot != null) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(dstInterval.spillSlot + stackSlotOffset), computeReg);
            } else if (dstAssignedReg != null && !dstAssignedReg.equals(computeReg)) {
                emit("MOVE", dstAssignedReg, computeReg);
            }

            return null;
        } else if (n.f2.f0.choice instanceof SimpleExp) {
            SimpleExp simple = (SimpleExp) n.f2.f0.choice;

            if (simple.f0.choice instanceof Temp) {
                Temp srcTemp = (Temp) simple.f0.choice;
                LiveInterval srcInterval = intervalMap.get(getTempId(srcTemp));
                LiveInterval dstInterval = intervalMap.get(getTempId(n.f1));

                if (dstInterval.assignedReg != null && srcInterval.assignedReg != null) {
                    emit("MOVE", dstInterval.assignedReg, srcInterval.assignedReg);
                } else if (dstInterval.spillSlot != null && srcInterval.assignedReg != null) {
                    emit("ASTORE", "SPILLEDARG", String.valueOf(dstInterval.spillSlot + stackSlotOffset),
                            srcInterval.assignedReg);
                } else if (dstInterval.assignedReg != null && srcInterval.spillSlot != null) {
                    emit("ALOAD", dstInterval.assignedReg, "SPILLEDARG",
                            String.valueOf(srcInterval.spillSlot + stackSlotOffset));
                } else {
                    emit("ALOAD", V_REGS[1], "SPILLEDARG", String.valueOf(srcInterval.spillSlot + stackSlotOffset));
                    emit("ASTORE", "SPILLEDARG", String.valueOf(dstInterval.spillSlot + stackSlotOffset), V_REGS[1]);
                }
                return null;
            }

            String simpleStr = getSimpleExpStr(simple);
            emit("MOVE", dstReg, simpleStr);
        }

        releaseRegForWrite(n.f1, V_REGS[1]);
        return null;
    }

    /**
     * f0 -> "PRINT"
     * f1 -> SimpleExp()
     */
    @Override
    public Void visit(PrintStmt n, String argu) {
        String reg = getRegForSimpleExp(n.f1, T_REGS[9]);
        emit("PRINT", reg);
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
    public Void visit(StmtExp n, String argu) {
        n.f1.accept(this, argu);

        String retReg = getRegForSimpleExp(n.f3, V_REGS[1]);
        emit("MOVE", V_REGS[0], retReg);

        return null;
    }

    @Override
    public Void visit(Exp n, String argu) {
        n.f0.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(Call n, String argu) {
        return null;
    }

    @Override
    public Void visit(HAllocate n, String argu) {
        return null;
    }

    @Override
    public Void visit(BinOp n, String argu) {
        return null;
    }

    @Override
    public Void visit(Operator n, String argu) {
        return null;
    }

    @Override
    public Void visit(SimpleExp n, String argu) {
        return null;
    }

    @Override
    public Void visit(Temp n, String argu) {
        return null;
    }

    @Override
    public Void visit(IntegerLiteral n, String argu) {
        return null;
    }

    @Override
    public Void visit(Label n, String argu) {
        return null;
    }

}

class CallFinderVisitor extends GJDepthFirst<Void, Void> {
    public boolean hasCall = false;
    public int maxArgs = 0;

    @Override
    public Void visit(Call n, Void argu) {
        hasCall = true;
        int numArgs = n.f3.size();
        if (numArgs > maxArgs) {
            maxArgs = numArgs;
        }

        n.f1.accept(this, argu);
        n.f3.accept(this, argu);

        return null;
    }
}