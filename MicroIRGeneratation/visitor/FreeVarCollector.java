package visitor;

import syntaxtree.*;
import java.util.*;

public class FreeVarCollector extends GJDepthFirst<Void, Void> {

    public final LinkedHashSet<String> identifierUses = new LinkedHashSet<>();
    public final LinkedHashSet<String> paramNames = new LinkedHashSet<>();
    public final LinkedHashSet<String> localDecls = new LinkedHashSet<>();

    @Override
    public Void visit(LambdaExpression n, Void argu) {
        Identifier paramNode = n.f1;
        String paramName = paramNode.f0.tokenImage;
        paramNames.add(paramName);
        n.f0.accept(this, argu);
        n.f1.accept(this, argu);
        n.f2.accept(this, argu);
        n.f3.accept(this, argu);
        n.f4.accept(this, argu);
        return null;
    }

    @Override
    public Void visit(MessageSend n, Void argu) {
        n.f0.accept(this, argu);
        if (n.f4.present()) {
            n.f4.accept(this, argu);
        }
        return null;
    }
    
    @Override
    public Void visit(ThisExpression n, Void argu) {
        identifierUses.add("this");
        return null;
    }

    @Override
    public Void visit(Identifier n, Void argu) {
        identifierUses.add(n.f0.tokenImage);
        return null;
    }

    @Override
public Void visit(AllocationExpression n, Void argu) {
    return null;
}
    
    
    @Override public Void visit(NodeList n, Void argu) { for (Enumeration<Node> e = n.elements(); e.hasMoreElements();) e.nextElement().accept(this, argu); return null; }
    @Override public Void visit(NodeListOptional n, Void argu) { if (n.present()) { for (Enumeration<Node> e = n.elements(); e.hasMoreElements();) e.nextElement().accept(this, argu); } return null; }
    @Override public Void visit(NodeOptional n, Void argu) { if (n.present()) n.node.accept(this, argu); return null; }
    @Override public Void visit(NodeSequence n, Void argu) { for (Enumeration<Node> e = n.elements(); e.hasMoreElements();) e.nextElement().accept(this, argu); return null; }
    @Override public Void visit(NodeToken n, Void argu) { return null; }
    @Override public Void visit(FormalParameter n, Void argu) { paramNames.add(n.f1.f0.tokenImage); return null; }
    @Override public Void visit(FormalParameterRest n, Void argu) { n.f1.accept(this, argu); return null; }
    @Override public Void visit(VarDeclaration n, Void argu) { localDecls.add(n.f1.f0.tokenImage); n.f0.accept(this, argu); return null; }
}