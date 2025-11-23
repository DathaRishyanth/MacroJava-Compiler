import syntaxtree.*;
import visitor.*;

public class P2 {
    public static void main(String [] args) {
        try {
            
            
            Node root = new MiniJavaParser(System.in).Goal();

            // System.out.println("Program parsed successfully");

            GJDepthFirst<String, String> visitor = new GJDepthFirst<>();
            root.accept(visitor, null);

            
            // visitor.printSymbolTable();
            // System.out.println("Symbol table constructed successfully");
            visitor.fill_var_types();
            // visitor.printSymbolTable();

            TypeCheckerVisitor<String, String> typeChecker = new TypeCheckerVisitor<>(visitor.symbolTable);
            root.accept(typeChecker, null);
            System.out.println("Program type checked successfully");
            
        
        } catch (ParseException e) {
            System.out.println(e.toString());
        }
        
        
    }
}


