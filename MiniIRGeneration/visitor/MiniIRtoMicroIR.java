package visitor;

import syntaxtree.*;
import java.util.*;


public class MiniIRtoMicroIR extends GJDepthFirst<Object, Void> {

    private int tempCounter = 100; 

    private String getNewTemp() {
        return "TEMP " + (tempCounter++);
    }

    public Object visit(NodeToken n, Void argu) { return n.tokenImage; }

    /**
     * f0 -> "MAIN"
     * f1 -> StmtList()
     * f2 -> "END"
     * f3 -> ( Procedure() )*
     * f4 -> <EOF>
     */
    public Object visit(Goal n, Void argu) {
        String mainStmts = (String) n.f1.accept(this, argu);
        String procedures = (String) n.f3.accept(this, argu);
        return "MAIN\n" + mainStmts + "END\n" + procedures;
    }

    /**
     * f0 -> ( ( Label() )? Stmt() )*
     */
    public Object visit(StmtList n, Void argu) {
        if (!n.f0.present()) return "";
        StringBuilder sb = new StringBuilder();
        for (Node node : n.f0.nodes) {
            sb.append(node.accept(this, argu));
        }
        return sb.toString();
    }

    /**
     * f0 -> Label()
     * f1 -> "["
     * f2 -> IntegerLiteral()
     * f3 -> "]"
     * f4 -> StmtExp()
     */
    public Object visit(Procedure n, Void argu) {
        TranslationResult labelRes = (TranslationResult) n.f0.accept(this, argu);
        TranslationResult intLitRes = (TranslationResult) n.f2.accept(this, argu);
        String stmtExp = (String) n.f4.accept(this, argu);
        
        return labelRes.resultIdentifier + " [" + intLitRes.resultIdentifier + "]\n" + stmtExp + "\n";
    }

    /**
     * f0 -> NoOpStmt() | ErrorStmt() | CJumpStmt() | JumpStmt() |
     * HStoreStmt() | HLoadStmt() | MoveStmt() | PrintStmt()
     */
    public Object visit(Stmt n, Void argu) {
        return n.f0.accept(this, argu);
    }

    public Object visit(NoOpStmt n, Void argu) { return "NOOP\n"; }
    public Object visit(ErrorStmt n, Void argu) { return "ERROR\n"; }

    /**
     * f0 -> "CJUMP"
     * f1 -> Exp()
     * f2 -> Label()
     */
    public Object visit(CJumpStmt n, Void argu) {
        TranslationResult cond = (TranslationResult) n.f1.accept(this, argu);
        TranslationResult label = (TranslationResult) n.f2.accept(this, argu);
        return cond.code + "CJUMP " + cond.resultIdentifier + " " + label.resultIdentifier + "\n";
    }

    /**
     * f0 -> "JUMP"
     * f1 -> Label()
     */
    public Object visit(JumpStmt n, Void argu) {
        TranslationResult label = (TranslationResult) n.f1.accept(this, argu);
        return "JUMP " + label.resultIdentifier + "\n";
    }

    /**
     * f0 -> "HSTORE"
     * f1 -> Exp()
     * f2 -> IntegerLiteral()
     * f3 -> Exp()
     */
    public Object visit(HStoreStmt n, Void argu) {
        TranslationResult addr = (TranslationResult) n.f1.accept(this, argu);
        TranslationResult offset = (TranslationResult) n.f2.accept(this, argu);
        TranslationResult val = (TranslationResult) n.f3.accept(this, argu);
        return addr.code + val.code + "HSTORE " + addr.resultIdentifier + " " + offset.resultIdentifier + " " + val.resultIdentifier + "\n";
    }

    /**
     * f0 -> "HLOAD"
     * f1 -> Temp()
     * f2 -> Exp()
     * f3 -> IntegerLiteral()
     */
    public Object visit(HLoadStmt n, Void argu) {
        TranslationResult destTemp = (TranslationResult) n.f1.accept(this, argu);
        TranslationResult srcAddr = (TranslationResult) n.f2.accept(this, argu);
        TranslationResult offset = (TranslationResult) n.f3.accept(this, argu);
        return srcAddr.code + "HLOAD " + destTemp.resultIdentifier + " " + srcAddr.resultIdentifier + " " + offset.resultIdentifier + "\n";
    }

    /**
     * f0 -> "MOVE"
     * f1 -> Temp()
     * f2 -> Exp()
     */
    public Object visit(MoveStmt n, Void argu) {
        TranslationResult destTemp = (TranslationResult) n.f1.accept(this, argu);
        TranslationResult exp = (TranslationResult) n.f2.accept(this, argu);
        return exp.code + "MOVE " + destTemp.resultIdentifier + " " + exp.resultIdentifier + "\n";
    }

    /**
     * f0 -> "PRINT"
     * f1 -> Exp()
     */
    public Object visit(PrintStmt n, Void argu) {
        TranslationResult exp = (TranslationResult) n.f1.accept(this, argu);
        return exp.code + "PRINT " + exp.resultIdentifier + "\n";
    }

    /**
     * f0 -> StmtExp() | Call() | HAllocate() | BinOp() | Temp() | IntegerLiteral() | Label()
     */
    public Object visit(Exp n, Void argu) {
        return n.f0.accept(this, argu);
    }

    /**
     * f0 -> "BEGIN"
     * f1 -> StmtList()
     * f2 -> "RETURN"
     * f3 -> Exp()
     * f4 -> "END"
     */
    public Object visit(StmtExp n, Void argu) {
        String stmts = (String) n.f1.accept(this, argu);
        TranslationResult retExp = (TranslationResult) n.f3.accept(this, argu);
        return "BEGIN\n" + stmts + retExp.code + "RETURN " + retExp.resultIdentifier + "\nEND";
    }

    /**
     * f0 -> "CALL"
     * f1 -> Exp()
     * f2 -> "("
     * f3 -> ( Exp() )*
     * f4 -> ")"
     */
    public Object visit(Call n, Void argu) {
        TranslationResult func = (TranslationResult) n.f1.accept(this, argu);
        StringBuilder argsCode = new StringBuilder();
        StringBuilder argsList = new StringBuilder();
        
        if (n.f3.present()) {
            for (Node node : n.f3.nodes) {
                TranslationResult arg = (TranslationResult) node.accept(this, argu);
                argsCode.append(arg.code);
                argsList.append(arg.resultIdentifier).append(" ");
            }
        }
        
        String resultTemp = getNewTemp();
        String callStmt = "MOVE " + resultTemp + " CALL " + func.resultIdentifier + " ( " + argsList.toString() + ")\n";
        return new TranslationResult(func.code + argsCode.toString() + callStmt, resultTemp);
    }

    /**
     * f0 -> "HALLOCATE"
     * f1 -> Exp()
     */
    public Object visit(HAllocate n, Void argu) {
        TranslationResult size = (TranslationResult) n.f1.accept(this, argu);
        String resultTemp = getNewTemp();
        String hallocateStmt = "MOVE " + resultTemp + " HALLOCATE " + size.resultIdentifier + "\n";
        return new TranslationResult(size.code + hallocateStmt, resultTemp);
    }

    /**
     * f0 -> Operator()
     * f1 -> Exp()
     * f2 -> Exp()
     */
    public Object visit(BinOp n, Void argu) {
        String op = (String) n.f0.accept(this, argu);
        TranslationResult op1 = (TranslationResult) n.f1.accept(this, argu);
        TranslationResult op2 = (TranslationResult) n.f2.accept(this, argu);
        String resultTemp = getNewTemp();
        String binOpStmt = "MOVE " + resultTemp + " " + op + " " + op1.resultIdentifier + " " + op2.resultIdentifier + "\n";
        return new TranslationResult(op1.code + op2.code + binOpStmt, resultTemp);
    }

    public Object visit(Operator n, Void argu) { return ((NodeToken) n.f0.choice).tokenImage; }

    /**
     * f0 -> "TEMP"
     * f1 -> IntegerLiteral()
     */
    public Object visit(Temp n, Void argu) {
        TranslationResult tempNum = (TranslationResult) n.f1.accept(this, argu);
        String tempStr = "TEMP " + tempNum.resultIdentifier;
        return new TranslationResult("", tempStr);
    }

    /**
     * f0 -> <INTEGER_LITERAL>
     */
    public Object visit(IntegerLiteral n, Void argu) {
        return new TranslationResult("", n.f0.tokenImage);
    }

    /**
     * f0 -> <IDENTIFIER>
     */
    public Object visit(Label n, Void argu) {
        return new TranslationResult("", n.f0.tokenImage);
    }
}