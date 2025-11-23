import syntaxtree.*;
import visitor.*;
import java.util.Map;


public class P3 {
    public static void main(String [] args) {
        try {
            Node root = new MiniJavaParser(System.in).Goal();
            
            SymbolTableVisitor stVisitor = new SymbolTableVisitor();
            root.accept(stVisitor, null);
            Map<String, ClassInfo> symbolTable = stVisitor.getSymbolTable();
            Map<Node, String> lambdaMap = stVisitor.getLambdaAstToClassName();
            
            MiniIRVisitor irVisitor = new MiniIRVisitor(symbolTable, lambdaMap);
            
            MiniIRExp result = root.accept(irVisitor, null);
            
            System.out.println(result.code);

        } catch (Exception e) {
            System.exit(1);
        }
    }
}