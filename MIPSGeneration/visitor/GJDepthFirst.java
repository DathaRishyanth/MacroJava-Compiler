package visitor;

import syntaxtree.*;
import java.util.*;

public class GJDepthFirst<R, A> implements GJVisitor<R, A> {

    private StringBuilder mipsCode = new StringBuilder();

    private int currentFrameSize = 0;
    private int currentSpillCount = 0;
    private int currentNumArgs = 0;
    private int currentMaxCallArgs = 0;

    private Map<String, Integer> procFrameSize = new HashMap<>();
    private int globalOutgoingArea = 0;

    private void emit(String code) {
        mipsCode.append(code).append("\n");
    }

    private void emit_t(String code) {
        mipsCode.append("\t").append(code).append("\n");
    }

    public String getMIPSCode() {
        return mipsCode.toString();
    }

    public R visit(NodeList n, A argu) {
        R _ret = null;
        for (Enumeration<Node> e = n.elements(); e.hasMoreElements();)
            e.nextElement().accept(this, argu);
        return _ret;
    }

    public R visit(NodeListOptional n, A argu) {
        if (n.present()) {
            R _ret = null;
            for (Enumeration<Node> e = n.elements(); e.hasMoreElements();)
                e.nextElement().accept(this, argu);
            return _ret;
        } else
            return null;
    }

    public R visit(NodeOptional n, A argu) {
        if (n.present())
            return n.node.accept(this, argu);
        else
            return null;
    }

    public R visit(NodeSequence n, A argu) {
        R _ret = null;
        for (Enumeration<Node> e = n.elements(); e.hasMoreElements();)
            e.nextElement().accept(this, argu);
        return _ret;
    }

    public R visit(NodeToken n, A argu) {
        return (R) n.tokenImage.trim();
    }

    private String stripColon(String s) {
        if (s == null)
            return null;
        s = s.trim();
        if (s.endsWith(":"))
            return s.substring(0, s.length() - 1);
        return s;
    }

    private int parseIntOrFail(String s, String ctx) {
        if (s == null)
            throw new RuntimeException("Null integer token while parsing " + ctx);
        s = s.trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Failed to parse int '" + s + "' for " + ctx);
        }
    }

    private void computeAllFrameSizes(syntaxtree.Goal goal) {
        int maxOutgoingArgSpace = 0;

        int mainNumArgs = parseIntOrFail((String) goal.f2.f0.accept(this, null), "main.numArgs");
        int mainSpillCount = parseIntOrFail((String) goal.f5.f0.accept(this, null), "main.spillCount");
        int mainMaxCallArgs = parseIntOrFail((String) goal.f8.f0.accept(this, null), "main.maxCallArgs");

        procFrameSize.put("main", computeFrameSize(mainNumArgs, mainSpillCount, mainMaxCallArgs));

        int mainOutgoingSpace = Math.max(0, mainMaxCallArgs - 4) * 4;
        if (mainOutgoingSpace > maxOutgoingArgSpace)
            maxOutgoingArgSpace = mainOutgoingSpace;

        for (Enumeration<Node> e = goal.f13.elements(); e.hasMoreElements();) {
            Procedure p = (Procedure) e.nextElement();
            String raw = (String) p.f0.accept(this, null);
            String name = stripColon(raw);
            int na = parseIntOrFail((String) p.f2.f0.accept(this, null), name + ".numArgs");
            int sc = parseIntOrFail((String) p.f5.f0.accept(this, null), name + ".spillCount");
            int ma = parseIntOrFail((String) p.f8.f0.accept(this, null), name + ".maxCallArgs");

            procFrameSize.put(name, computeFrameSize(na, sc, ma));

            int procOutgoingSpace = Math.max(0, ma - 4) * 4;
            if (procOutgoingSpace > maxOutgoingArgSpace)
                maxOutgoingArgSpace = procOutgoingSpace;
        }

        globalOutgoingArea = maxOutgoingArgSpace;

        if (globalOutgoingArea % 8 != 0)
            globalOutgoingArea += 4;

    }

    private int computeFrameSize(int numArgs, int spillCount, int maxCallArgs) {
        int numStackArgs = Math.max(0, numArgs - 4);
        int spillSpace = spillCount * 4;
        int sRegSpace = 8 * 4;
        int raFpSpace = 2 * 4;
        int localRegAndSpillSpace = spillSpace + sRegSpace + raFpSpace;
        int stackArgSpace = Math.max(0, maxCallArgs - 4) * 4;
        int frame = localRegAndSpillSpace + stackArgSpace;
        if (frame % 8 != 0)
            frame += 4;
        return frame;
    }

    public R visit(Goal n, A argu) {
        computeAllFrameSizes((syntaxtree.Goal) n);

        emit(".text");
        emit(".globl main");
        emit("main:");
        String var1 = (String) n.f2.f0.accept(this, argu);
        String var2 = (String) n.f5.f0.accept(this, argu);
        String var3 = (String) n.f8.f0.accept(this, argu);
        int space = Integer.parseInt(var2);
        emit_t("sw $fp, 0($sp)");
        emit_t("move $fp, $sp");
        emit_t("sw $ra, -4($sp)");
        emit_t("subu $sp, $sp, " + (space * 4 + 8));
        n.f10.accept(this, argu);
        emit_t("lw $ra, -4($fp)");
        emit_t("lw $fp, 0($fp)");
        emit_t("addu $sp, $sp, " + (space * 4 + 8));
        emit_t("j $ra");

        n.f13.accept(this, argu);

        emit(".text");
        emit(".globl __halloc__");
        emit("__halloc__:");
        emit_t("li $v0, 9");
        emit_t("syscall");
        emit_t("j $ra");

        emit(".text");
        emit(".globl __print__");
        emit("__print__:");
        emit_t("li $v0, 1");
        emit_t("syscall");
        emit_t("la $a0, newl");
        emit_t("li $v0, 4");
        emit_t("syscall");
        emit_t("j $ra");

        emit("");
        emit("_error:");
        emit_t("la $a0, error_msg");
        emit_t("li $v0, 4");
        emit_t("syscall");
        emit_t("li $v0, 10");
        emit_t("syscall");

        emit(".data");
        emit_t(".align 2");
        emit("error_msg: .asciiz \"ERROR: Abnormal Termination\\n\"");
        emit("newl: .asciiz \"\\n\"");

        return null;
    }

    public R visit(StmtList n, A argu) {
        if (n.f0.present()) {
            for (Enumeration<Node> e = n.f0.elements(); e.hasMoreElements();) {
                NodeSequence seq = (NodeSequence) e.nextElement();
                NodeOptional labelOpt = (NodeOptional) seq.elementAt(0);
                Node stmt = seq.elementAt(1);
                if (labelOpt.present()) {
                    String label = (String) labelOpt.node.accept(this, argu);
                    label = stripColon(label);
                    emit(label + ":");
                }
                stmt.accept(this, argu);
            }
        }
        return null;
    }

    public R visit(Procedure n, A argu) {
        String label = (String) n.f0.accept(this, argu);
        emit(".text");
        emit(".globl " + label);
        emit(label + ":");
        String var1 = (String) n.f2.f0.accept(this, argu);
        String var2 = (String) n.f5.f0.accept(this, argu);
        String var3 = (String) n.f8.f0.accept(this, argu);
        int space = Integer.parseInt(var2);
        emit_t("sw $fp, 0($sp)");
        emit_t("move $fp, $sp");
        emit_t("sw $ra, -4($sp)");
        emit_t("subu $sp, $sp, " + (space * 4 + 8));
        n.f10.accept(this, argu);
        emit_t("lw $ra, -4($fp)");
        emit_t("lw $fp, 0($fp)");
        emit_t("addu $sp, $sp, " + (space * 4 + 8));
        emit_t("j $ra");
        return null;
    }

    public R visit(Stmt n, A argu) {
        return n.f0.accept(this, argu);
    }

    public R visit(NoOpStmt n, A argu) {
        emit_t("nop");
        return null;
    }

    public R visit(ErrorStmt n, A argu) {
        emit_t("j _error");
        return null;
    }

    public R visit(CJumpStmt n, A argu) {
        String reg = (String) n.f1.accept(this, argu);
        String label = stripColon((String) n.f2.accept(this, argu));
        emit_t("beqz " + reg + ", " + label);
        return null;
    }

    public R visit(JumpStmt n, A argu) {
        String label = stripColon((String) n.f1.accept(this, argu));
        emit_t("b " + label);
        return null;
    }

    public R visit(HStoreStmt n, A argu) {
        String baseReg = (String) n.f1.accept(this, argu);
        String offset = (String) n.f2.accept(this, argu);
        String dataReg = (String) n.f3.accept(this, argu);
        emit_t("sw " + dataReg + ", " + offset + "(" + baseReg + ")");
        return null;
    }

    public R visit(HLoadStmt n, A argu) {
        String destReg = (String) n.f1.accept(this, argu);
        String baseReg = (String) n.f2.accept(this, argu);
        String offset = (String) n.f3.accept(this, argu);
        emit_t("lw " + destReg + ", " + offset + "(" + baseReg + ")");
        return null;
    }

    public R visit(MoveStmt n, A argu) {
        String destReg = (String) n.f1.accept(this, argu);
        Node expNode = n.f2.f0.choice;
        if (expNode instanceof SimpleExp) {
            SimpleExp simple = (SimpleExp) expNode;
            String src = (String) simple.f0.accept(this, argu);
            if (simple.f0.choice instanceof Reg)
                emit_t("move " + destReg + ", " + src);
            else if (simple.f0.choice instanceof IntegerLiteral)
                emit_t("li " + destReg + ", " + src);
            else if (simple.f0.choice instanceof Label)
                emit_t("la " + destReg + ", " + stripColon(src));
        } else {
            String srcReg = (String) expNode.accept(this, argu);
            if (!destReg.equals(srcReg))
                emit_t("move " + destReg + ", " + srcReg);
        }
        return null;
    }

    public R visit(PrintStmt n, A argu) {
        SimpleExp se = n.f1;
        String val = (String) se.f0.accept(this, argu);
        if (se.f0.choice instanceof Reg)
            emit_t("move $a0, " + val);
        else if (se.f0.choice instanceof IntegerLiteral)
            emit_t("li $a0, " + val);
        else
            emit_t("la $a0, " + stripColon(val));
        emit_t("jal __print__");
        return null;
    }

    public R visit(ALoadStmt n, A argu) {
        String dest = (String) n.f1.accept(this, argu);
        String idx = (String) n.f2.accept(this, argu);
        int off = Integer.parseInt(idx) * 4 + 8;
        emit_t("lw " + dest + ", -" + off + "($fp)");
        return null;
    }

    public R visit(AStoreStmt n, A argu) {
        String idx = (String) n.f1.accept(this, argu);
        String src = (String) n.f2.accept(this, argu);
        int off = Integer.parseInt(idx) * 4 + 8;
        emit_t("sw " + src + ", -" + off + "($fp)");
        return null;
    }

    public R visit(PassArgStmt n, A argu) {
        String k = (String) n.f1.accept(this, argu);
        String src = (String) n.f2.accept(this, argu);
        int off = Integer.parseInt(k) * 4 + 4;
        emit_t("sw " + src + ", -" + off + "($sp)");
        return null;
    }

    public R visit(CallStmt n, A argu) {
        SimpleExp s = n.f1;
        String v = (String) s.f0.accept(this, argu);
        if (s.f0.choice instanceof Reg) {
            emit_t("move $v0, " + v);
            emit_t("jalr $v0");
        } else {
            emit_t("la $v0, " + stripColon(v));
            emit_t("jalr $v0");
        }
        return null;
    }

    public R visit(Exp n, A argu) {
        return n.f0.accept(this, argu);
    }

    public R visit(HAllocate n, A argu) {
        SimpleExp se = n.f1;
        String sz = (String) se.f0.accept(this, argu);
        if (se.f0.choice instanceof Reg)
            emit_t("move $a0, " + sz);
        else
            emit_t("li $a0, " + sz);
        emit_t("jal __halloc__");
        return (R) "$v0";
    }

    public R visit(BinOp n, A argu) {
        String opTok = (String) n.f0.accept(this, argu);
        String r1 = (String) n.f1.accept(this, argu);
        SimpleExp se = n.f2;
        String r2raw = (String) se.f0.accept(this, argu);

        boolean rhsIsImm = (se.f0.choice instanceof IntegerLiteral);
        String rhsReg = rhsIsImm ? "$v1" : r2raw;

        if (rhsIsImm) {
            emit_t("li $v1, " + r2raw);
        }

        switch (opTok) {
            case "LE":
                emit_t("sle $v0, " + r1 + ", " + rhsReg);
                break;
            case "NE":
                emit_t("sne $v0, " + r1 + ", " + rhsReg);
                break;
            case "PLUS":
                if (rhsIsImm)
                    emit_t("addiu $v0, " + r1 + ", " + r2raw);
                else
                    emit_t("addu $v0, " + r1 + ", " + rhsReg);
                break;
            case "MINUS":
                emit_t("subu $v0, " + r1 + ", " + rhsReg);
                break;
            case "TIMES":
                emit_t("mult " + r1 + ", " + rhsReg);
                emit_t("mflo $v0");
                break;
            case "DIV":
                emit_t("div " + r1 + ", " + rhsReg);
                emit_t("mflo $v0");
                break;
            default:
                throw new RuntimeException("Unknown binop: " + opTok);
        }
        return (R) "$v0";
    }

    public R visit(Operator n, A argu) {
        return (R) n.f0.accept(this, argu);
    }

    public R visit(SpilledArg n, A argu) {
        return (R) n.f1.accept(this, argu);
    }

    public R visit(SimpleExp n, A argu) {
        return n.f0.accept(this, argu);
    }

    public R visit(Reg n, A argu) {
        String regName = (String) n.f0.accept(this, argu);
        regName = regName.trim();
        if (regName.startsWith("$"))
            return (R) regName;
        return (R) ("$" + regName);
    }

    public R visit(IntegerLiteral n, A argu) {
        return (R) n.f0.tokenImage.trim();
    }

    public R visit(Label n, A argu) {
        return (R) n.f0.tokenImage.trim();
    }

    public R visit(SpillInfo n, A argu) {
        return null;
    }

    public R visit(SpillStatus n, A argu) {
        return null;
    }
}
