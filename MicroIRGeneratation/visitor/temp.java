
package visitor;

import syntaxtree.*;
import java.util.*;

public class SymbolTableVisitor extends GJDepthFirst<String, Void> {

   private final Map<String, ClassInfo> symbolTable;
   private String currentClassName;
   private String currentMethodName;
   private int lambdaCounter = 0;
   private final Map<Node, String> lambdaAstToClassName = new LinkedHashMap<>();
   private String assignmentTargetType = null;

   public SymbolTableVisitor() {
      this.symbolTable = new LinkedHashMap<>();
   }

   public Map<String, ClassInfo> getSymbolTable() {
      return this.symbolTable;
   }

   public Map<Node, String> getLambdaAstToClassName() {
      return Collections.unmodifiableMap(this.lambdaAstToClassName);
   }

   private void handleError(String message) {
      System.err.println("Symbol Table Error: " + message);
      System.exit(1);
   }

   @Override
   public String visit(LambdaType n, Void argu) {
      String paramType = n.f2.f0.tokenImage;
      String returnType = n.f4.f0.tokenImage;
      return "lambda<" + paramType + "," + returnType + ">";
   }

   // In SymbolTableVisitor.java

   @Override
   public String visit(LambdaExpression n, Void argu) {
      String lambdaClassName = (currentClassName == null ? "Global" : currentClassName) + "_lambda" + (lambdaCounter++);
      FreeVarCollector collector = new FreeVarCollector();
      n.accept(collector, null);
      Set<String> captured = new LinkedHashSet<>(collector.identifierUses);
      captured.removeAll(collector.paramNames);
      captured.removeAll(collector.localDecls);
      ClassInfo ci = new ClassInfo(lambdaClassName, null);
      ci.fields = new LinkedHashMap<>();
      MethodInfo enclosingMethod = (currentClassName != null && currentMethodName != null)
            ? symbolTable.get(currentClassName).methods.get(currentMethodName)
            : null;
      for (String capVar : captured) {
         String capturedVarType = null;
         if (capVar.equals("this")) {
            capturedVarType = currentClassName;
         } else if (enclosingMethod != null) {
            if (enclosingMethod.locals.containsKey(capVar))
               capturedVarType = enclosingMethod.locals.get(capVar);
            else if (enclosingMethod.params.containsKey(capVar))
               capturedVarType = enclosingMethod.params.get(capVar);
         }
         if (capturedVarType == null && currentClassName != null) {
            String cls = currentClassName;
            while (cls != null && capturedVarType == null) {
               ClassInfo enclosingClass = symbolTable.get(cls);
               if (enclosingClass != null && enclosingClass.fields.containsKey(capVar))
                  capturedVarType = enclosingClass.fields.get(capVar);
               cls = (enclosingClass != null) ? enclosingClass.parent : null;
            }
         }
         if (capturedVarType == null)
            handleError("Cannot find type for captured variable '" + capVar + "' in lambda.");
         ci.fields.put(capVar, capturedVarType);
      }
      ci.markSynthetic(n);
      String paramType = "int", returnType = "int";
      if (this.assignmentTargetType != null) {
         String types = this.assignmentTargetType.substring(7, this.assignmentTargetType.length() - 1);
         String[] parts = types.split(",");
         if (parts.length == 2) {
            paramType = parts[0];
            returnType = parts[1];
         }
      }
      MethodInfo apply = new MethodInfo("apply", returnType);
      apply.isSyntheticLambda = true;
      apply.lambdaAst = n;
      for (String pname : collector.paramNames)
         apply.params.put(pname, paramType);
      apply.capturedVars.addAll(captured);
      ci.methods = new LinkedHashMap<>();
      ci.methods.put("apply", apply);
      symbolTable.put(lambdaClassName, ci);
      lambdaAstToClassName.put(n, lambdaClassName);

      String oldClassName = this.currentClassName;
      String oldMethodName = this.currentMethodName;
      this.currentClassName = lambdaClassName;
      this.currentMethodName = "apply";
      n.f4.accept(this, argu);
      this.currentClassName = oldClassName;
      this.currentMethodName = oldMethodName;

      return lambdaClassName;
   }

   @Override
   public String visit(NodeList n, Void argu) {
      for (Enumeration<Node> e = n.elements(); e.hasMoreElements();)
         e.nextElement().accept(this, argu);
      return null;
   }

   @Override
   public String visit(NodeListOptional n, Void argu) {
      if (n.present()) {
         for (Enumeration<Node> e = n.elements(); e.hasMoreElements();)
            e.nextElement().accept(this, argu);
      }
      return null;
   }

   @Override
   public String visit(NodeOptional n, Void argu) {
      if (n.present())
         return n.node.accept(this, argu);
      else
         return null;
   }

   @Override
   public String visit(NodeSequence n, Void argu) {
      for (Enumeration<Node> e = n.elements(); e.hasMoreElements();)
         e.nextElement().accept(this, argu);
      return null;
   }

   @Override
   public String visit(NodeToken n, Void argu) {
      return null;
   }

   @Override
   public String visit(Goal n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ImportFunction n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(MainClass n, Void argu) {
      String mainClassName = n.f1.accept(this, argu);
      this.currentClassName = mainClassName;

      ClassInfo mainClass = new ClassInfo(this.currentClassName, null);

      MethodInfo mainMethod = new MethodInfo("main", "void");
      String paramName = n.f11.accept(this, argu);
      mainMethod.params.put(paramName, "String[]");
      mainClass.methods.put("main", mainMethod);
      symbolTable.put(this.currentClassName, mainClass);

      n.f14.accept(this, argu);

      this.currentClassName = null;
      return null;
   }

   @Override
   public String visit(TypeDeclaration n, Void argu) {
      return n.f0.accept(this, argu);
   }

   @Override
   public String visit(ClassDeclaration n, Void argu) {
      String className = n.f1.accept(this, argu);
      this.currentClassName = className;

      ClassInfo ci = new ClassInfo(className, null);
      ci.fields = new LinkedHashMap<>();
      ci.methods = new LinkedHashMap<>();
      symbolTable.put(className, ci);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      this.currentClassName = null;
      return null;
   }

   @Override
   public String visit(ClassExtendsDeclaration n, Void argu) {
      String className = n.f1.accept(this, argu);
      String parentName = n.f3.accept(this, argu);
      this.currentClassName = className;

      symbolTable.put(className, new ClassInfo(className, parentName));
      n.f5.accept(this, argu);
      n.f6.accept(this, argu);
      this.currentClassName = null;
      return null;
   }

   // In SymbolTableVisitor.java
   @Override
   public String visit(VarDeclaration n, Void argu) {
      String type = n.f0.accept(this, argu);
      String name = n.f1.accept(this, argu);

      if (currentMethodName == null) {
         // This is a field declaration.
         ClassInfo c = symbolTable.get(currentClassName);

         // Add the field to the current class's map of declared fields.
         c.fields.put(name, type);
      } else {
         // This is a local variable.
         MethodInfo m = symbolTable.get(currentClassName).methods.get(currentMethodName);

         m.locals.put(name, type);
      }
      return null;
   }

   @Override
   public String visit(MethodDeclaration n, Void argu) {
      String returnType = n.f1.accept(this, argu);
      String methodName = n.f2.accept(this, argu);

      ClassInfo ci = symbolTable.get(currentClassName);

      MethodInfo mi = new MethodInfo(methodName, returnType);
      ci.methods.put(methodName, mi);

      String prevMethod = this.currentMethodName;
      this.currentMethodName = methodName;

      n.f4.accept(this, argu);
      n.f7.accept(this, argu);
      n.f8.accept(this, argu);
      n.f10.accept(this, argu);

      this.currentMethodName = prevMethod;
      return null;
   }

   @Override
   public String visit(FormalParameterList n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      return null;
   }

   @Override
   public String visit(FormalParameter n, Void argu) {
      String type = n.f0.accept(this, argu);
      String name = n.f1.accept(this, argu);

      MethodInfo m = symbolTable.get(this.currentClassName).methods.get(this.currentMethodName);

      m.params.put(name, type);
      return null;
   }

   @Override
   public String visit(FormalParameterRest n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      return null;
   }

   @Override
   public String visit(Type n, Void argu) {
      return n.f0.accept(this, argu);
   }

   @Override
   public String visit(ArrayType n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return "int[]";
   }

   @Override
   public String visit(BooleanType n, Void argu) {
      n.f0.accept(this, argu);
      return "boolean";
   }

   @Override
   public String visit(IntegerType n, Void argu) {
      n.f0.accept(this, argu);
      return "int";
   }

   @Override
   public String visit(Identifier n, Void argu) {
      return n.f0.tokenImage;
   }

   @Override
   public String visit(Statement n, Void argu) {
      return n.f0.accept(this, argu);
   }

   @Override
   public String visit(Block n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(AssignmentStatement n, Void argu) {
      String lhsName = n.f0.f0.tokenImage;
      String lhsType = null;
      if (currentClassName != null && currentMethodName != null) {
         MethodInfo currentMethod = symbolTable.get(currentClassName).methods.get(currentMethodName);
         if (currentMethod != null) {
            if (currentMethod.locals.containsKey(lhsName))
               lhsType = currentMethod.locals.get(lhsName);
            else if (currentMethod.params.containsKey(lhsName))
               lhsType = currentMethod.params.get(lhsName);
         }
      }
      if (lhsType != null && lhsType.startsWith("lambda<")) {
         this.assignmentTargetType = lhsType;
      }
      n.f2.accept(this, argu);
      this.assignmentTargetType = null;
      n.f0.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ArrayAssignmentStatement n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      n.f5.accept(this, argu);
      n.f6.accept(this, argu);
      return null;
   }

   @Override
   public String visit(IfStatement n, Void argu) {
      return n.f0.accept(this, argu);
   }

   @Override
   public String visit(IfthenStatement n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      return null;
   }

   @Override
   public String visit(IfthenElseStatement n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      n.f5.accept(this, argu);
      n.f6.accept(this, argu);
      return null;
   }

   @Override
   public String visit(WhileStatement n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      return null;
   }

   @Override
   public String visit(PrintStatement n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      return null;
   }

   @Override
   public String visit(Expression n, Void argu) {
      return n.f0.accept(this, argu);
   }

   @Override
   public String visit(AndExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(OrExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(CompareExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(neqExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(AddExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(MinusExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(TimesExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(DivExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ArrayLookup n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ArrayLength n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }

   @Override
   public String visit(MessageSend n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      if (n.f4.present())
         n.f4.accept(this, argu);
      n.f5.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ExpressionList n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ExpressionRest n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      return null;
   }

   @Override
   public String visit(IntegerLiteral n, Void argu) {
      n.f0.accept(this, argu);
      return null;
   }

   @Override
   public String visit(TrueLiteral n, Void argu) {
      n.f0.accept(this, argu);
      return null;
   }

   @Override
   public String visit(FalseLiteral n, Void argu) {
      n.f0.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ThisExpression n, Void argu) {
      n.f0.accept(this, argu);
      return null;
   }

   @Override
   public String visit(ArrayAllocationExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      n.f4.accept(this, argu);
      return null;
   }

   @Override
   public String visit(AllocationExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      n.f3.accept(this, argu);
      return null;
   }

   @Override
   public String visit(NotExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      return null;
   }

   @Override
   public String visit(BracketExpression n, Void argu) {
      n.f0.accept(this, argu);
      n.f1.accept(this, argu);
      n.f2.accept(this, argu);
      return null;
   }
}