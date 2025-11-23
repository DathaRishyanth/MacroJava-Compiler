package visitor;

import syntaxtree.*; // Import the MiniJava syntax tree nodes
import java.util.*;
/**
 * This class visits nodes from the MiniJava AST (from the 'syntaxtree' package)
 * and its purpose is to print out corresponding MiniIR code as a String.
 */
public class TranslatorVisitor extends GJDepthFirst<String, Void> {

    /**
     * Example for visiting a PrintStatement in MiniJava.
     * f0 -> "System.out.println"
     * f1 -> "("
     * f2 -> Expression()
     * f3 -> ")"
     * f4 -> ";"
     */
    @Override
    public String visit(PrintStatement n, Void argu) {
        // First, visit the expression inside the print statement to get the MiniIR
        // code for what needs to be printed.
        String expressionCode = n.f2.accept(this, argu);
        
        // Now, return the MiniIR "PRINT" statement.
        return "PRINT " + expressionCode;
    }

    // You will override many other visit methods here...
    // For example, visit(MethodDeclaration n, ...)
    // and visit(AddExpression n, ...)
}