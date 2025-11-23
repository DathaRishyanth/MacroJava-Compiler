package visitor;

import syntaxtree.*;
import java.util.*;

/**
 * A visitor that translates microIR to miniRA using Linear Scan register
 * allocation.
 *
 * This visitor performs the following steps for each procedure:
 * 1. Builds live intervals for all TEMPs using the liveness data.
 * 2. Runs the Linear Scan register allocation algorithm to assign physical
 * registers or
 * stack slots (spills) to each TEMP.
 * 3. Translates the microIR AST, rewriting statements to use the assigned
 * registers,
 * and inserting ALOAD/ASTORE operations for spills.
 * 4. Generates the miniRA code, including procedure prologues (saving
 * callee-saved
 * registers) and epilogues (restoring them and handling return values).
 */
public class Linear extends GJDepthFirst<Void, String> {

    // --- Liveness and CFG Data ---
    private final Map<String, BuildCFGAndLiveness.ProcedureInfo> livenessData;
    private BuildCFGAndLiveness.ProcedureInfo currentProc;

    // --- Linear Scan Data Structures ---
    private static class LiveInterval implements Comparable<LiveInterval> {
        int tempId;
        int start;
        int end;
        String assignedReg = null; // e.g., "s0", "t1"
        Integer spillSlot = null; // e.g., 0, 1, 2...

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

    private static class ActiveInterval implements Comparable<ActiveInterval> {
        LiveInterval interval;

        ActiveInterval(LiveInterval i) {
            interval = i;
        }

        @Override
        public int compareTo(ActiveInterval other) {
            return Integer.compare(this.interval.end, other.interval.end);
        }
    }

    // --- Register Pools ---
    private static final String[] S_REGS = { "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7" }; // 8 Callee-saved
    private static final String[] T_REGS = { "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9" }; // 10
                                                                                                           // Caller-saved
    private static final String[] A_REGS = { "a0", "a1", "a2", "a3" }; // 4 Argument
    private static final String[] V_REGS = { "v0", "v1" }; // 2 Return/Scratch

    private List<String> freeRegisters;
    private PriorityQueue<ActiveInterval> active;
    private Map<Integer, LiveInterval> intervalMap;
    private int spillCount;
    private int sRegUsageCount;
    private int stackSlotOffset; // sRegs saved + incoming args
    private int maxCallArgs;

    // --- Code Generation ---
    private final StringBuilder output;
    private boolean inProcedure; // false for MAIN
    private String currentLabel = null; // For attaching labels to stmts

    public Linear(BuildCFGAndLiveness liveness) {
        this.livenessData = liveness.procedures;
        this.output = new StringBuilder();
    }

    public String getMiniRACode() {
        return output.toString();
    }

    // --- Utility Functions ---

    /** Emits a line of miniRA code, handling labels. */
    private void emit(String... parts) {
        if (currentLabel != null) {
            output.append(currentLabel).append(" "); // Print label on same line
            currentLabel = null;
        } else {
            output.append("    "); // Indent
        }
        for (String p : parts) {
            output.append(p).append(" ");
        }
        output.append("\n");
    }

    /** Gets the TEMP ID from a Temp node. */
    private int getTempId(Temp n) {
        return Integer.parseInt(n.f1.f0.tokenImage);
    }

    /** Gets the string representation of a SimpleExp. */
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

    /**
     * Gets a register for a TEMP that is being read. Loads from spill if needed.
     */
    private String getRegForRead(Temp t, String scratchReg) {
        LiveInterval i = intervalMap.get(getTempId(t));
        if (i.assignedReg != null) {
            return i.assignedReg;
        }
        // Is spilled, must load
        emit("ALOAD", scratchReg, "SPILLEDARG", String.valueOf(i.spillSlot + stackSlotOffset));
        return scratchReg;
    }

    /** Gets a register for a SimpleExp, using scratch if it's not a register. */
    private String getRegForSimpleExp(SimpleExp se, String scratchReg) {
        if (se.f0.choice instanceof Temp) {
            return getRegForRead((Temp) se.f0.choice, scratchReg);
        }

        // It's an Integer or Label, load it into the scratch reg
        String literal = (se.f0.choice instanceof IntegerLiteral)
                ? ((IntegerLiteral) se.f0.choice).f0.tokenImage
                : ((Label) se.f0.choice).f0.tokenImage;
        emit("MOVE", scratchReg, literal);
        return scratchReg;
    }

    /** Gets a register for a TEMP that is being written to. */
    private String getRegForWrite(Temp t, String scratchReg) {
        LiveInterval i = intervalMap.get(getTempId(t));
        if (i.assignedReg != null) {
            return i.assignedReg;
        }
        // Is spilled, return scratch reg. Caller must store it.
        return scratchReg;
    }

    /** After writing to a scratch reg, stores it back to spill if needed. */
    private void releaseRegForWrite(Temp t, String scratchReg) {
        LiveInterval i = intervalMap.get(getTempId(t));
        if (i.spillSlot != null) {
            emit("ASTORE", "SPILLEDARG", String.valueOf(i.spillSlot + stackSlotOffset), scratchReg);
        }
    }

    // --- Linear Scan Implementation ---

    private void buildLiveIntervals() {
        intervalMap = new HashMap<>();
        int tempCount = currentProc.tempCount;
        for (int i = 0; i < tempCount; i++) {
            intervalMap.put(i, new LiveInterval(i));
        }

        for (int i = 0; i < currentProc.nodes.size(); i++) {
            BuildCFGAndLiveness.StmtInfo stmt = currentProc.nodes.get(i);
            BitSet live = (BitSet) stmt.in.clone();
            live.or(stmt.def); // Live-in OR def @ stmt i

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
        // 0. Initialize
        spillCount = 0;
        sRegUsageCount = 0;
        freeRegisters = new LinkedList<>();
        // Add registers in preferred order (caller-saved first)
        Collections.addAll(freeRegisters, T_REGS);
        Collections.addAll(freeRegisters, S_REGS);

        active = new PriorityQueue<>(); // Sorted by increasing end point

        // 1. Get intervals and sort by start point
        List<LiveInterval> intervals = new ArrayList<>();
        for (LiveInterval i : intervalMap.values()) {
            if (i.start != -1) { // Only process intervals that are actually used
                intervals.add(i);
            }
        }
        Collections.sort(intervals);

        // 2. Main loop
        for (LiveInterval i : intervals) {
            expireOldIntervals(i);
            if (active.size() == (S_REGS.length + T_REGS.length)) {
                spillAtInterval(i);
            } else {
                String reg = freeRegisters.remove(0);
                i.assignedReg = reg;
                if (reg.startsWith("s"))
                    sRegUsageCount++;
                active.add(new ActiveInterval(i));
            }
        }
    }

    private void expireOldIntervals(LiveInterval i) {
        while (!active.isEmpty() && active.peek().interval.end < i.start) {
            LiveInterval j = active.poll().interval;
            freeRegisters.add(j.assignedReg); // Free the register
        }
    }

    private void spillAtInterval(LiveInterval i) {
        LiveInterval spill = active.peek().interval;
        if (spill.end > i.end) {
            // Spill 'spill'
            i.assignedReg = spill.assignedReg; // 'i' takes 'spill's register
            spill.assignedReg = null;
            spill.spillSlot = spillCount++;

            active.poll(); // Remove 'spill'
            active.add(new ActiveInterval(i)); // Add 'i'
        } else {
            // Spill 'i'
            i.spillSlot = spillCount++;
        }
    }

    // --- Main Visitor Methods ---

    /**
     * f0 -> "MAIN"
     * f1 -> StmtList()
     * f2 -> "END"
     * f3 -> ( Procedure() )*
     * f4 -> <EOF>
     */
    @Override
    public Void visit(Goal n, String argu) {
        // --- Process MAIN ---
        inProcedure = false;
        currentProc = livenessData.get("MAIN");
        buildLiveIntervals();
        linearScanAllocate();

        // Scan for calls and max args
        CallFinderVisitor callFinder = new CallFinderVisitor();
        n.f1.accept(callFinder, null);
        maxCallArgs = callFinder.maxArgs;
        int tRegSaveSpace = callFinder.hasCall ? 10 : 0;

        // Properly find which s-regs are used
        Set<String> sRegsUsed = new HashSet<>();
        for (LiveInterval i : intervalMap.values()) {
            if (i.assignedReg != null && i.assignedReg.startsWith("s")) {
                sRegsUsed.add(i.assignedReg);
            }
        }
        sRegUsageCount = sRegsUsed.size();

        // MAIN has 0 incoming args.
        int incomingStackSlots = 0;
        // --- THIS IS THE FIRST FIX ---
        // Removed the last term (+ Math.max(0, maxCallArgs - 4))
        int stackSize = incomingStackSlots + sRegUsageCount + spillCount + tRegSaveSpace;
        stackSlotOffset = incomingStackSlots + sRegUsageCount; // Spills start after saved s-regs

        output.append("MAIN [0] [").append(stackSize).append("] [").append(maxCallArgs).append("]\n");

        // Prologue: Save used s-regs in CANONICAL order
        int sRegSlot = incomingStackSlots; // Starts at 0
        for (String sReg : S_REGS) {
            if (sRegsUsed.contains(sReg)) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(sRegSlot++), sReg);
            }
        }

        n.f1.accept(this, argu); // Visit StmtList

        // Epilogue: Restore used s-regs in CANONICAL order
        sRegSlot = incomingStackSlots; // Starts at 0
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

        // --- Process Procedures ---
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

        // Scan for calls and max args
        CallFinderVisitor callFinder = new CallFinderVisitor();
        n.f4.accept(callFinder, null);
        maxCallArgs = callFinder.maxArgs;
        int tRegSaveSpace = callFinder.hasCall ? 10 : 0;

        // Count unique s-registers used
        Set<String> sRegsUsed = new HashSet<>();
        for (LiveInterval i : intervalMap.values()) {
            if (i.assignedReg != null && i.assignedReg.startsWith("s")) {
                sRegsUsed.add(i.assignedReg);
            }
        }
        sRegUsageCount = sRegsUsed.size();

        // Calculate space for INCOMING stack arguments
        int incomingStackSlots = Math.max(0, argCount - 4);

        // --- THIS IS THE SECOND FIX ---
        // Removed the last term (+ Math.max(0, maxCallArgs - 4))
        int stackSize = incomingStackSlots + sRegUsageCount + spillCount + tRegSaveSpace;

        // Offset spills and s-reg saves by incomingStackSlots
        stackSlotOffset = incomingStackSlots + sRegUsageCount; // Spills start after incoming args AND saved s-regs

        output.append("\n").append(procName);
        output.append(" [").append(argCount).append("]");
        output.append(" [").append(stackSize).append("]");
        output.append(" [").append(maxCallArgs).append("]\n");

        // --- Prologue ---

        // Start saving s-regs *after* the incoming argument slots
        int sRegSlot = incomingStackSlots;
        for (String sReg : S_REGS) {
            if (sRegsUsed.contains(sReg)) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(sRegSlot++), sReg);
            }
        }

        // Move arguments (TEMP 0-3 are a0-a3, rest are on stack)
        for (int i = 0; i < argCount; i++) {
            Temp t = new Temp(new NodeToken("TEMP"), new IntegerLiteral(new NodeToken(String.valueOf(i))));
            String writeReg = getRegForWrite(t, V_REGS[1]);

            if (i < 4) {
                // Arg from register
                emit("MOVE", writeReg, A_REGS[i]);
            } else {
                // Read from INCOMING argument slots (0, 1, 2...)
                emit("ALOAD", writeReg, "SPILLEDARG", String.valueOf(i - 4));
            }
            releaseRegForWrite(t, V_REGS[1]);
        }

        n.f4.accept(this, argu); // Visit StmtExp

        // --- Epilogue (Handled in StmtExp) ---

        // Restore s-regs from *after* the incoming argument slots
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
        // We must iterate manually to catch the label *before* visiting the statement.
        if (n.f0.present()) {
            for (Node node : n.f0.nodes) {
                // Each node is a NodeSequence: ( Label() )? Stmt()
                NodeSequence seq = (NodeSequence) node;

                // f0 -> ( Label() )? which is a NodeOptional
                NodeOptional labelOpt = (NodeOptional) seq.nodes.elementAt(0);
                if (labelOpt.present()) {
                    Label l = (Label) labelOpt.node;
                    currentLabel = l.f0.tokenImage; // No ":"
                }

                // f1 -> Stmt() which is a Node
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
        String reg = getRegForRead(n.f1, V_REGS[1]);
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
        String srcReg = getRegForRead(n.f3, T_REGS[9]); // Use t9 as scratch
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
        String baseReg = getRegForRead(n.f2, T_REGS[9]); // Use t9 as scratch
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
        // --- Special Case: MOVE t (CALL ...) ---
        if (n.f2.f0.choice instanceof Call) {
            Call call = (Call) n.f2.f0.choice;

            // Save ALL 10 t-regs (t0-t9)
            // t-regs are saved *after* incoming args, s-regs, and spills
            int tRegSaveSlot = stackSlotOffset + spillCount;
            for (int i = 0; i < T_REGS.length; i++) {
                emit("ASTORE", "SPILLEDARG", String.valueOf(tRegSaveSlot + i), T_REGS[i]);
            }

            // 2. Pass arguments
            int argNum = 0;
            for (Node argNode : call.f3.nodes) {
                Temp argTemp = (Temp) argNode;
                String argReg = getRegForRead(argTemp, V_REGS[1]);
                if (argNum < 4) {
                    emit("MOVE", A_REGS[argNum], argReg);
                } else {
                    // PASSARG 1 is the 5th arg (index 4)
                    emit("PASSARG", String.valueOf(argNum - 3), argReg);
                }
                argNum++;
            }

            // 3. Make the call
            String funcReg = getRegForSimpleExp(call.f1, V_REGS[1]);
            emit("CALL", funcReg);

            // 4. Restore ALL 10 t-regs
            tRegSaveSlot = stackSlotOffset + spillCount;
            for (int i = 0; i < T_REGS.length; i++) {
                emit("ALOAD", T_REGS[i], "SPILLEDARG", String.valueOf(tRegSaveSlot + i));
            }

            // 5. Get return value
            String dstReg = getRegForWrite(n.f1, V_REGS[1]);
            emit("MOVE", dstReg, V_REGS[0]); // v0
            releaseRegForWrite(n.f1, V_REGS[1]);

            return null;
        }

        // --- All other MOVEs ---
        String dstReg = getRegForWrite(n.f1, V_REGS[1]);

        if (n.f2.f0.choice instanceof HAllocate) {
            HAllocate halloc = (HAllocate) n.f2.f0.choice;
            String sizeReg = getRegForSimpleExp(halloc.f1, T_REGS[9]);
            emit("MOVE", dstReg, "HALLOCATE", sizeReg);
        } else if (n.f2.f0.choice instanceof BinOp) {
            BinOp binOp = (BinOp) n.f2.f0.choice;
            String op = ((NodeToken) binOp.f0.f0.choice).tokenImage;
            String src1Reg = getRegForRead(binOp.f1, T_REGS[8]);
            String src2Reg = getRegForSimpleExp(binOp.f2, T_REGS[9]);
            emit("MOVE", dstReg, op, src1Reg, src2Reg);
        } else if (n.f2.f0.choice instanceof SimpleExp) {
            SimpleExp simple = (SimpleExp) n.f2.f0.choice;

            // Handle MOVE t1, t2 (the only tricky case)
            if (simple.f0.choice instanceof Temp) {
                Temp srcTemp = (Temp) simple.f0.choice;
                LiveInterval srcInterval = intervalMap.get(getTempId(srcTemp));
                LiveInterval dstInterval = intervalMap.get(getTempId(n.f1));

                // Reg -> Reg
                if (dstInterval.assignedReg != null && srcInterval.assignedReg != null) {
                    emit("MOVE", dstInterval.assignedReg, srcInterval.assignedReg);
                }
                // Spill -> Reg
                else if (dstInterval.spillSlot != null && srcInterval.assignedReg != null) {
                    emit("ASTORE", "SPILLEDARG", String.valueOf(dstInterval.spillSlot + stackSlotOffset),
                            srcInterval.assignedReg);
                }
                // Reg -> Spill
                else if (dstInterval.assignedReg != null && srcInterval.spillSlot != null) {
                    emit("ALOAD", dstInterval.assignedReg, "SPILLEDARG",
                            String.valueOf(srcInterval.spillSlot + stackSlotOffset));
                }
                // Spill -> Spill
                else {
                    emit("ALOAD", V_REGS[1], "SPILLEDARG", String.valueOf(srcInterval.spillSlot + stackSlotOffset));
                    emit("ASTORE", "SPILLEDARG", String.valueOf(dstInterval.spillSlot + stackSlotOffset), V_REGS[1]);
                }
                return null; // Already emitted
            }

            // Handle MOVE t, (INT | LABEL)
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
        String reg = getRegForSimpleExp(n.f1, V_REGS[1]);
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
        n.f1.accept(this, argu); // Visit StmtList

        // Handle RETURN
        String retReg = getRegForSimpleExp(n.f3, V_REGS[1]);
        emit("MOVE", V_REGS[0], retReg);

        // Epilogue is handled in Procedure visitor after this returns
        return null;
    }

    // --- Unused standard visitors ---
    @Override
    public Void visit(Exp n, String argu) {
        n.f0.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(Call n, String argu) {
        /* Handled in MoveStmt */ return null;
    }

    @Override
    public Void visit(HAllocate n, String argu) {
        /* Handled in MoveStmt */ return null;
    }

    @Override
    public Void visit(BinOp n, String argu) {
        /* Handled in MoveStmt */ return null;
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

/**
 * A simple visitor to find any CALL statements and the maximum argument count.
 */
class CallFinderVisitor extends GJDepthFirst<Void, Void> {
    public boolean hasCall = false;
    public int maxArgs = 0;

    @Override
    public Void visit(Call n, Void argu) {
        hasCall = true; // Mark that we found a call
        int numArgs = n.f3.size();
        if (numArgs > maxArgs) {
            maxArgs = numArgs;
        }
        return null;
    }
}
