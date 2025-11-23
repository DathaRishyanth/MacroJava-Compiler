package visitor;

import syntaxtree.*;
import java.util.*;

public class MiniIRVisitor extends GJDepthFirst<MiniIRExp, Map<String, String>> {

   private final Map<String, ClassInfo> symbolTable;
   private final Map<String, ClassLayout> classLayouts;
   private final Map<String, VTable> vTables;
   private final Map<Node, String> lambdaAstToClassName;
   private static final String VTABLE_DIRECTORY_PTR = "TEMP 30";
   private int tempCounter = 100;
   private int labelCounter = 0;
   private String currentClassName;
   private String currentMethodName;
   private final StringBuilder tempInitializations = new StringBuilder();

   public MiniIRVisitor(Map<String, ClassInfo> symbolTable, Map<Node, String> lambdaAstToClassName) {
        this.symbolTable = symbolTable;
        this.lambdaAstToClassName = (lambdaAstToClassName == null) ? new LinkedHashMap<>() : lambdaAstToClassName;
        this.classLayouts = new LinkedHashMap<>();
        this.vTables = new LinkedHashMap<>();
        symbolTable.keySet().forEach(name -> {
            classLayouts.put(name, new ClassLayout(name));
            vTables.put(name, new VTable(name));
        });
        symbolTable.keySet().forEach(this::populateLayoutForClass);
        ClassLayout lambdaLayout = new ClassLayout("lambda");
        VTable lambdaVTable = new VTable("lambda");
        lambdaVTable.addMethod("apply", "lambda");
        this.classLayouts.put("lambda", lambdaLayout);
        this.vTables.put("lambda", lambdaVTable);
    }

   private String getExpressionType(Expression exp, Map<String, String> env) {
    if (exp == null || exp.f0 == null || exp.f0.choice == null)
        throw new RuntimeException("getExpressionType: null expression");

   
    java.util.function.Function<String, String> normalizePotentialLambdaClassName = (typeString) -> {
        if (typeString == null) return null;
        if (typeString.startsWith("lambda<")) return typeString;
        ClassInfo maybe = symbolTable.get(typeString);
        if (maybe != null && maybe.isSynthetic()) {
            MethodInfo applyMi = maybe.methods.get("apply");
            if (applyMi == null)
                throw new RuntimeException("Internal: synthetic class " + typeString + " has no apply method");
            String paramType;
            if (applyMi.params.size() == 1) {
                paramType = new ArrayList<>(applyMi.params.values()).get(0);
            } else if (applyMi.params.size() == 0) {
                paramType = "void";
            } else {
                paramType = String.join("|", new ArrayList<>(applyMi.params.values()));
            }
            String returnType = applyMi.returnType == null ? "void" : applyMi.returnType;
            return "lambda<" + paramType + "," + returnType + ">";
        }
        return typeString;
    };

    
    java.util.function.BiFunction<Node, Map<String, String>, String> getTypeForNodeChoice =
        (node, environment) -> {
            if (node == null) return null;

            
            if (node instanceof Expression) {
                return getExpressionType((Expression) node, environment);
            }

           
            if (node instanceof PrimaryExpression) {
                PrimaryExpression pe = (PrimaryExpression) node;
                Node pc = pe.f0.choice;
                if (pc instanceof IntegerLiteral) return "int";
                if (pc instanceof TrueLiteral) return "boolean";
                if (pc instanceof FalseLiteral) return "boolean";
                if (pc instanceof ThisExpression) {
                    ClassInfo currentClass = symbolTable.get(this.currentClassName);
                    if (currentClass != null && currentClass.isSynthetic() && currentClass.fields.containsKey("this")) {
                        return currentClass.fields.get("this");
                    }
                    return this.currentClassName;
                }
                if (pc instanceof AllocationExpression) {
                    return ((AllocationExpression) pc).f1.f0.tokenImage;
                }
                if (pc instanceof BracketExpression) {
                    return getExpressionType(((BracketExpression) pc).f1, environment);
                }
                if (pc instanceof Identifier) {
                    String id = ((Identifier) pc).f0.tokenImage;
                    if (currentMethodName != null) {
                        MethodInfo mi = symbolTable.get(currentClassName).methods.get(currentMethodName);
                        if (mi != null) {
                            if (mi.params.containsKey(id)) return mi.params.get(id);
                            if (mi.locals.containsKey(id)) return mi.locals.get(id);
                        }
                    }
                    String cls = currentClassName;
                    while (cls != null) {
                        ClassInfo ci = symbolTable.get(cls);
                        if (ci != null && ci.fields.containsKey(id))
                            return ci.fields.get(id);
                        cls = (ci != null) ? ci.parent : null;
                    }
                    throw new RuntimeException("Unknown identifier '" + id + "' when computing type (owner class: " + currentClassName + ")");
                }
            }

            if (node instanceof MessageSend) {
                Expression receiverExpr = new Expression(new NodeChoice(((MessageSend) node).f0));
                return getExpressionType(receiverExpr, environment);
            }

            if (node instanceof LambdaExpression) {
                Node lambdaNode = node;
                String lambdaClassName = lambdaAstToClassName.get(lambdaNode);
                if (lambdaClassName == null)
                    throw new RuntimeException("getExpressionType: no synthetic class recorded for lambda AST");
                ClassInfo lambdaCI = symbolTable.get(lambdaClassName);
                if (lambdaCI == null)
                    throw new RuntimeException("getExpressionType: no ClassInfo for synthetic lambda class " + lambdaClassName);
                MethodInfo applyMi = lambdaCI.methods.get("apply");
                if (applyMi == null)
                    throw new RuntimeException("getExpressionType: synthetic lambda class " + lambdaClassName + " has no apply method");

                String paramType;
                if (applyMi.params.size() == 1) {
                    paramType = new ArrayList<>(applyMi.params.values()).get(0);
                } else if (applyMi.params.size() == 0) {
                    paramType = "void";
                } else {
                    paramType = String.join("|", new ArrayList<>(applyMi.params.values()));
                }
                String returnType = applyMi.returnType == null ? "void" : applyMi.returnType;
                return "lambda<" + paramType + "," + returnType + ">";
            }
            return null;
        };

    Node choice = exp.f0.choice;

    if (choice instanceof MessageSend) {
        MessageSend ms = (MessageSend) choice;
        Node receiverNode = ms.f0;
        String receiverTypeRaw = getTypeForNodeChoice.apply(receiverNode, env);
        if (receiverTypeRaw == null) {
            receiverTypeRaw = getExpressionType(new Expression(new NodeChoice(ms.f0)), env);
        }
        if (receiverTypeRaw == null) throw new RuntimeException("getExpressionType: cannot determine receiver type for message send");

        String receiverType = normalizePotentialLambdaClassName.apply(receiverTypeRaw);
        String methodName = ms.f2.f0.tokenImage;

        if (receiverType.startsWith("lambda<")) {
            if ("apply".equals(methodName)) {
                int comma = receiverType.indexOf(',');
                int end = receiverType.length() - 1;
                if (comma < 0 || end <= comma) throw new RuntimeException("Malformed lambda type: " + receiverType);
                return receiverType.substring(comma + 1, end);
            } else {
                throw new RuntimeException("Method " + methodName + " not found for lambda type " + receiverType);
            }
        }

        String cls = receiverType;
        while (cls != null) {
            ClassInfo ci = symbolTable.get(cls);
            if (ci != null && ci.methods.containsKey(methodName)) {
                return ci.methods.get(methodName).returnType;
            }
            cls = (ci != null) ? ci.parent : null;
        }
        throw new RuntimeException("Method " + methodName + " not found in class " + receiverType);
    }

    if (choice instanceof PrimaryExpression) {
        PrimaryExpression p = (PrimaryExpression) choice;
        Node pc = p.f0.choice;
        if (pc instanceof IntegerLiteral) return "int";
        if (pc instanceof TrueLiteral) return "boolean";
        if (pc instanceof FalseLiteral) return "boolean";
        if (pc instanceof ThisExpression) {
            ClassInfo currentClass = symbolTable.get(this.currentClassName);
            if (currentClass != null && currentClass.isSynthetic() && currentClass.fields.containsKey("this")) {
                return currentClass.fields.get("this");
            }
            return this.currentClassName;
        }
        if (pc instanceof AllocationExpression) return ((AllocationExpression) pc).f1.f0.tokenImage;
        if (pc instanceof BracketExpression) return getExpressionType(((BracketExpression) pc).f1, env);
        if (pc instanceof Identifier) {
            String id = ((Identifier) pc).f0.tokenImage;
            if (currentMethodName != null) {
                MethodInfo mi = symbolTable.get(currentClassName).methods.get(currentMethodName);
                if (mi != null) {
                    if (mi.params.containsKey(id)) return mi.params.get(id);
                    if (mi.locals.containsKey(id)) return mi.locals.get(id);
                }
            }
            String cls = currentClassName;
            while (cls != null) {
                ClassInfo ci = symbolTable.get(cls);
                if (ci != null && ci.fields.containsKey(id)) return ci.fields.get(id);
                cls = (ci != null) ? ci.parent : null;
            }
        }
    }

    if (choice instanceof AndExpression || choice instanceof OrExpression || choice instanceof CompareExpression
            || choice instanceof neqExpression || choice instanceof NotExpression) {
        return "boolean";
    }
    if (choice instanceof AddExpression || choice instanceof MinusExpression || choice instanceof TimesExpression
            || choice instanceof DivExpression || choice instanceof ArrayLookup || choice instanceof ArrayLength) {
        return "int";
    }

    if (choice instanceof LambdaExpression) {
        Node lambdaNode = choice;
        String lambdaClassName = lambdaAstToClassName.get(lambdaNode);
        if (lambdaClassName == null) throw new RuntimeException("getExpressionType: no synthetic class recorded for lambda AST");
        ClassInfo lambdaCI = symbolTable.get(lambdaClassName);
        if (lambdaCI == null) throw new RuntimeException("getExpressionType: no ClassInfo for synthetic lambda class " + lambdaClassName);
        MethodInfo applyMi = lambdaCI.methods.get("apply");
        if (applyMi == null) throw new RuntimeException("getExpressionType: synthetic lambda class " + lambdaClassName + " has no apply method");

        String paramType;
        if (applyMi.params.size() == 1) {
            paramType = new ArrayList<>(applyMi.params.values()).get(0);
        } else if (applyMi.params.size() == 0) {
            paramType = "void";
        } else {
            paramType = String.join("|", new ArrayList<>(applyMi.params.values()));
        }
        String returnType = applyMi.returnType == null ? "void" : applyMi.returnType;
        return "lambda<" + paramType + "," + returnType + ">";
    }

    throw new RuntimeException("getExpressionType: Unhandled expression type: " + choice.getClass().getName());
}




   

   @Override
public MiniIRExp visit(MessageSend n, Map<String, String> env) {
    Expression receiverExpr = new Expression(new NodeChoice(n.f0));
    String recvType = getExpressionType(receiverExpr, env);
    if (recvType == null) {
        throw new RuntimeException("MessageSend: cannot determine receiver type for expression");
    }

    String methodName = n.f2.f0.tokenImage;

    MiniIRExp receiver = n.f0.accept(this, env);
    receiver = ensureHasResult(receiver);

    if (!receiver.result.startsWith("TEMP")) {
        String tmp = newTemp();
        receiver = new MiniIRExp(receiver.code + "MOVE " + tmp + " " + receiver.result + "\n", tmp);
    }

    StringBuilder argsCode = new StringBuilder();
    List<String> argsTemps = new ArrayList<>();
    if (n.f4.present()) {
        ExpressionList el = (ExpressionList) n.f4.node;
        MiniIRExp first = el.f0.accept(this, env);
        first = ensureHasResult(first);
        argsCode.append(first.code);
        argsTemps.add(first.result);
        for (Node node : el.f1.nodes) {
            MiniIRExp r = ((ExpressionRest) node).f1.accept(this, env);
            r = ensureHasResult(r);
            argsCode.append(r.code);
            argsTemps.add(r.result);
        }
    }

    String vtableLookupType;
    if (recvType.startsWith("lambda<")) {
        if (!"apply".equals(methodName)) {
            throw new RuntimeException("Method " + methodName + " not found for lambda type " + recvType);
        }
        vtableLookupType = "lambda";
    } else {
        vtableLookupType = recvType;
    }

    VTable vt = vTables.get(vtableLookupType);
    if (vt == null) {
        throw new RuntimeException("MiniIRVisitor: no vtable for receiver type: " + vtableLookupType);
    }

    Integer offset = vt.methodOffsets.get(methodName);
    if (offset == null) {
        if ("apply".equals(methodName) && vTables.containsKey("lambda")) {
            offset = vTables.get("lambda").methodOffsets.get("apply");
        }
        if (offset == null) {
            throw new RuntimeException("Method " + methodName + " not in vtable for " + vtableLookupType);
        }
    }

    String vtablePtr = newTemp(), methodPtr = newTemp(), resultTemp = newTemp();
    String argsList = String.join(" ", argsTemps);
    String code = receiver.code + argsCode.toString() +
          "HLOAD " + vtablePtr + " " + receiver.result + " 0\n" +
          "HLOAD " + methodPtr + " " + vtablePtr + " " + offset + "\n" +
          "MOVE " + resultTemp + " CALL " + methodPtr + " ( " + receiver.result
          + (argsList.isEmpty() ? "" : " " + argsList) + " )\n";
    return new MiniIRExp(code, resultTemp);
}

   private final SortedSet<Integer> createdTemps = new TreeSet<>();

   private final Map<String, LambdaMeta> lambdaMetas = new LinkedHashMap<>();

   public static class LambdaMeta {
      public final List<String> capturedVars = new ArrayList<>();
      public Node lambdaAst = null;
   }

    private String newTemp() {
        String t = "TEMP " + (tempCounter++);
        tempInitializations.append("MOVE ").append(t).append(" 0").append("\n");
        return t;
    }

   private String newLabel() {
      return "L" + (labelCounter++);
   }

   private MiniIRExp ensureHasResult(MiniIRExp e) {
      if (e == null)
         throw new RuntimeException("ensureHasResult: null MiniIRExp");
      if (e.result != null)
         return e;
      throw new RuntimeException("Expression produced code but no result TEMP. Code:\n" + e.code);
   }

   private void populateLayoutForClass(String className) {
        ClassInfo ci = symbolTable.get(className);
        if (ci == null) return;
        ClassLayout layout = classLayouts.get(className);
        if (layout.isFinalized()) return;
    
        if (ci.parent != null) {
            populateLayoutForClass(ci.parent);
            ClassLayout parentLayout = classLayouts.get(ci.parent);
            layout.fieldOffsets.putAll(parentLayout.fieldOffsets);
            layout.objectSize = parentLayout.objectSize;
        } else {
            layout.objectSize = 4;
        }
    
        for (String fieldName : ci.fields.keySet()) {
            layout.fieldOffsets.put(fieldName, layout.objectSize);
            layout.objectSize += 4;
        }
        
        if (ci.parent != null) {
            VTable pv = vTables.get(ci.parent);
            vTables.get(className).methodOffsets.putAll(pv.methodOffsets);
            vTables.get(className).methodLabels.addAll(pv.methodLabels);
        }
        for (String m : ci.methods.keySet()) {
            if ("main".equals(m)) continue;
            int off = vTables.get(className).methodOffsets.getOrDefault(m, -1);
            if (off != -1) {
                vTables.get(className).methodLabels.set(off / 4, className + "_" + m);
            } else {
                vTables.get(className).addMethod(m, className);
            }
        }
        layout.finalizeLayout();
    }

   private MiniIRExp binaryOp(String op, PrimaryExpression n1, PrimaryExpression n2, Map<String, String> env) {
      MiniIRExp lhs = n1.accept(this, env);
      MiniIRExp rhs = n2.accept(this, env);
      String temp = newTemp();
      String code = (lhs.code == null ? "" : lhs.code) + (rhs.code == null ? "" : rhs.code)
            + "MOVE " + temp + " " + op + " " + lhs.result + " " + rhs.result + "\n";
      return new MiniIRExp(code, temp);
   }

   private String getVTableInitializationCode() {
      int numClasses = vTables.size();
      StringBuilder sb = new StringBuilder();
      sb.append("MOVE ").append(VTABLE_DIRECTORY_PTR).append(" HALLOCATE ").append(numClasses * 4).append("\n");
      List<String> sorted = new ArrayList<>(vTables.keySet());
      Collections.sort(sorted);
      int idx = 0;
      for (String cname : sorted) {
         VTable vt = vTables.get(cname);
         String classVTablePtr = newTemp();
         sb.append("MOVE ").append(classVTablePtr).append(" HALLOCATE ").append(vt.methodLabels.size() * 4)
               .append("\n");
         for (int i = 0; i < vt.methodLabels.size(); i++) {
            String methodLabel = vt.methodLabels.get(i);
            String methodAddrTemp = newTemp();
            sb.append("MOVE ").append(methodAddrTemp).append(" ").append(methodLabel).append("\n");
            sb.append("HSTORE ").append(classVTablePtr).append(" ").append(i * 4).append(" ").append(methodAddrTemp)
                  .append("\n");
         }
         sb.append("HSTORE ").append(VTABLE_DIRECTORY_PTR).append(" ").append(idx * 4).append(" ")
               .append(classVTablePtr).append("\n");
         classLayouts.get(cname).vTableDirectoryIndex = idx;
         idx++;
      }
      return sb.toString();
   }

   public MiniIRExp visit(NodeList n, Map<String, String> env) {
      StringBuilder sb = new StringBuilder();
      for (Enumeration<Node> e = n.elements(); e.hasMoreElements();) {
         Node node = e.nextElement();
         MiniIRExp child = (MiniIRExp) node.accept(this, env);
         if (child != null && child.code != null)
            sb.append(child.code);
      }
      return new MiniIRExp(sb.toString(), null);
   }

   public MiniIRExp visit(NodeListOptional n, Map<String, String> env) {
      if (!n.present())
         return new MiniIRExp("", null);
      StringBuilder sb = new StringBuilder();
      for (Enumeration<Node> e = n.elements(); e.hasMoreElements();) {
         Node node = e.nextElement();
         MiniIRExp child = (MiniIRExp) node.accept(this, env);
         if (child != null && child.code != null)
            sb.append(child.code);
      }
      return new MiniIRExp(sb.toString(), null);
   }

   public MiniIRExp visit(NodeOptional n, Map<String, String> env) {
      if (!n.present())
         return new MiniIRExp("", null);
      return (MiniIRExp) n.node.accept(this, env);
   }

   public MiniIRExp visit(NodeSequence n, Map<String, String> env) {
      StringBuilder sb = new StringBuilder();
      for (Enumeration<Node> e = n.elements(); e.hasMoreElements();) {
         Node node = e.nextElement();
         MiniIRExp child = (MiniIRExp) node.accept(this, env);
         if (child != null && child.code != null)
            sb.append(child.code);
      }
      return new MiniIRExp(sb.toString(), null);
   }

   @Override
   public MiniIRExp visit(NodeToken n, Map<String, String> env) {
      return new MiniIRExp("", null);
   }

   /**
    * f0 -> ( ImportFunction() )?
    * f1 -> MainClass()
    * f2 -> ( TypeDeclaration() )*
    * f3 -> <EOF>
    */
   @Override
   public MiniIRExp visit(Goal n, Map<String, String> env) {
      String vinit = getVTableInitializationCode();

      MiniIRExp mc = n.f1.accept(this, new LinkedHashMap<>());
      String mainCode = (mc == null) ? "" : mc.code;
      MiniIRExp procs = n.f2.accept(this, new LinkedHashMap<>());
      String procedures = (procs == null) ? "" : procs.code;

      StringBuilder tempInits = new StringBuilder();
      if (tempInitializations.length() > 0) {
         tempInits.append(tempInitializations.toString());
      }

      StringBuilder full = new StringBuilder();
      full.append("MAIN\n");
      if (vinit != null && !vinit.isEmpty())
         full.append(vinit);
      if (tempInits.length() > 0)
         full.append(tempInits.toString());
      full.append("\n");
      full.append(mainCode);
      full.append("END\n\n");
      if (procedures != null && !procedures.isEmpty())
         full.append(procedures);

      StringBuilder lambdaProcs = new StringBuilder();
      for (Map.Entry<String, ClassInfo> centry : symbolTable.entrySet()) {
         String className = centry.getKey();
         ClassInfo ci = centry.getValue();
         if (ci == null || ci.methods == null)
            continue;

         MethodInfo applyMi = ci.methods.get("apply");
         if (applyMi == null)
            applyMi = ci.methods.get("Apply");

         if (applyMi == null)
            continue;

         boolean looksLikeLambda = (applyMi.isSyntheticLambda || applyMi.lambdaAst != null || ci.isSynthetic());
         if (!looksLikeLambda)
            continue;

         Map<String, String> methodEnv = new LinkedHashMap<>();
         methodEnv.put("this", "TEMP 0");
         int ptemp = 1;
         List<String> paramNames = new ArrayList<>(applyMi.params.keySet());
         for (String pname : paramNames)
            methodEnv.put(pname, "TEMP " + (ptemp++));

         String savedClass = this.currentClassName;
         String savedMethod = this.currentMethodName;
         this.currentClassName = className;
         this.currentMethodName = "apply";

         if (applyMi.lambdaAst == null) {
            this.currentClassName = savedClass;
            this.currentMethodName = savedMethod;
            continue;
         }

         if (applyMi.lambdaAst == null) {
            continue;
         }

         LambdaExpression lambdaNode = (LambdaExpression) applyMi.lambdaAst;

         MiniIRExp bodyExp = (MiniIRExp) lambdaNode.f4.accept(this, methodEnv);
         if (bodyExp == null) {
            this.currentClassName = savedClass;
            this.currentMethodName = savedMethod;
            throw new RuntimeException("Lambda apply body generation returned null for " + className);
         }
         if (bodyExp.result == null) {
            this.currentClassName = savedClass;
            this.currentMethodName = savedMethod;
            throw new RuntimeException(
                  "Lambda apply body did not produce a TEMP result for " + className + ". Code:\n" + bodyExp.code);
         }

         this.currentClassName = savedClass;
         this.currentMethodName = savedMethod;

         int arity = paramNames.size() + 1;
         String procLabel = className + "_apply";
         String proc = String.format("%s [%d]\nBEGIN\n%sRETURN %s\nEND\n\n", procLabel, arity,
               (bodyExp.code == null ? "" : bodyExp.code), bodyExp.result);
         lambdaProcs.append(proc);
      }

      if (lambdaProcs.length() > 0)
         full.append(lambdaProcs.toString());

      return new MiniIRExp(full.toString(), null);
   }

   @Override
   public MiniIRExp visit(ImportFunction n, Map<String, String> env) {
      n.f0.accept(this, env);
      n.f1.accept(this, env);
      n.f2.accept(this, env);
      return new MiniIRExp("", null);
   }

   @Override
   public MiniIRExp visit(MainClass n, Map<String, String> env) {
      String className = n.f1.f0.tokenImage;
      this.currentClassName = className;
      MiniIRExp codeForPrint = n.f14.accept(this, env);
      this.currentClassName = null;
      return new MiniIRExp(codeForPrint == null ? "" : codeForPrint.code, null);
   }

   @Override
   public MiniIRExp visit(TypeDeclaration n, Map<String, String> env) {
      return n.f0.accept(this, env);
   }

   @Override
   public MiniIRExp visit(ClassDeclaration n, Map<String, String> env) {
      String className = n.f1.f0.tokenImage;
      this.currentClassName = className;
      MiniIRExp fieldsCode = n.f3.accept(this, env);
      MiniIRExp methodsCode = n.f4.accept(this, env);
      this.currentClassName = null;
      String code = (fieldsCode == null ? "" : fieldsCode.code) + (methodsCode == null ? "" : methodsCode.code);
      return new MiniIRExp(code, null);
   }

   @Override
   public MiniIRExp visit(ClassExtendsDeclaration n, Map<String, String> env) {
      String className = n.f1.f0.tokenImage;
      this.currentClassName = className;
      MiniIRExp fieldsCode = n.f5.accept(this, env);
      MiniIRExp methodsCode = n.f6.accept(this, env);
      this.currentClassName = null;
      String code = (fieldsCode == null ? "" : fieldsCode.code) + (methodsCode == null ? "" : methodsCode.code);
      return new MiniIRExp(code, null);
   }

   @Override
   public MiniIRExp visit(VarDeclaration n, Map<String, String> env) {
      return new MiniIRExp("", null);
   }

   @Override
   public MiniIRExp visit(MethodDeclaration n, Map<String, String> env) {
      String methodName = n.f2.f0.tokenImage;
      Map<String, String> methodEnv = new LinkedHashMap<>();
      methodEnv.put("this", "TEMP 0");
      MethodInfo mi = symbolTable.get(currentClassName).methods.get(methodName);
      int ptemp = 1;
      for (String p : mi.params.keySet())
         methodEnv.put(p, "TEMP " + ptemp++);
      for (String l : mi.locals.keySet())
         methodEnv.put(l, newTemp());
      this.currentMethodName = methodName;
      MiniIRExp stmts = n.f8.accept(this, methodEnv);
      String stmtsCode = (stmts == null) ? "" : stmts.code;
      MiniIRExp retExp = n.f10.accept(this, methodEnv);
      if (retExp == null)
         throw new RuntimeException("Method return expression produced null for " + methodName);
      if (retExp.result == null)
         throw new RuntimeException(
               "Return expression missing TEMP result for " + methodName + ". Code:\n" + retExp.code);
      String procedure = String.format("%s_%s [%d]\nBEGIN\n%s%sRETURN %s\nEND\n", currentClassName, methodName,
            mi.params.size() + 1, stmtsCode, retExp.code, retExp.result);
      this.currentMethodName = null;
      return new MiniIRExp(procedure, null);
   }

   @Override
   public MiniIRExp visit(FormalParameterList n, Map<String, String> env) {
      if (n == null)
         return new MiniIRExp("", null);
      return n.f0.accept(this, env);
   }

   @Override
   public MiniIRExp visit(FormalParameter n, Map<String, String> env) {
      n.f0.accept(this, env);
      n.f1.accept(this, env);
      return new MiniIRExp("", null);
   }

   @Override
   public MiniIRExp visit(FormalParameterRest n, Map<String, String> env) {
      n.f0.accept(this, env);
      n.f1.accept(this, env);
      return new MiniIRExp("", null);
   }

   @Override
   public MiniIRExp visit(Type n, Map<String, String> env) {
   
      return new MiniIRExp("", null);
   }

   @Override
   public MiniIRExp visit(ArrayType n, Map<String, String> env) {
      n.f0.accept(this, env);
      n.f1.accept(this, env);
      n.f2.accept(this, env);
      return new MiniIRExp("", "int[]");
   }

   @Override
   public MiniIRExp visit(BooleanType n, Map<String, String> env) {
      n.f0.accept(this, env);
      return new MiniIRExp("", "boolean");
   }

   @Override
   public MiniIRExp visit(IntegerType n, Map<String, String> env) {
      n.f0.accept(this, env);
      return new MiniIRExp("", "int");
   }

   @Override
   public MiniIRExp visit(LambdaType n, Map<String, String> env) {
      n.f0.accept(this, env);
      n.f1.accept(this, env);
      n.f2.accept(this, env);
      n.f3.accept(this, env);
      n.f4.accept(this, env);
      n.f5.accept(this, env);
      return new MiniIRExp("", null);
   }

   @Override
   public MiniIRExp visit(Statement n, Map<String, String> env) {
      return n.f0.accept(this, env);
   }

   @Override
   public MiniIRExp visit(Block n, Map<String, String> env) {
      return n.f1.accept(this, env);
   }

   @Override
   public MiniIRExp visit(PrintStatement n, Map<String, String> env) {
      MiniIRExp e = n.f2.accept(this, env);
      e = ensureHasResult(e);
      return new MiniIRExp(e.code + "PRINT " + e.result + "\n", null);
   }

   @Override
   public MiniIRExp visit(AssignmentStatement n, Map<String, String> env) {
      String id = n.f0.f0.tokenImage;
      MiniIRExp rhs = n.f2.accept(this, env);
      rhs = ensureHasResult(rhs);
      String lhsTemp = env.get(id);
      if (lhsTemp != null) {
         return new MiniIRExp(rhs.code + "MOVE " + lhsTemp + " " + rhs.result + "\n", null);
      } else {
         Integer offset = classLayouts.get(currentClassName).fieldOffsets.get(id);
         if (offset == null)
            throw new RuntimeException("Unknown field " + id + " of class " + currentClassName);
         return new MiniIRExp(rhs.code + "HSTORE TEMP 0 " + offset + " " + rhs.result + "\n", null);
      }
   }

   @Override
   public MiniIRExp visit(ArrayAssignmentStatement n, Map<String, String> env) {
      String arrayName = n.f0.f0.tokenImage;
      String arrayPtr = env.get(arrayName);
      String prefix = "";
      if (arrayPtr == null) {
         Integer off = classLayouts.get(currentClassName).fieldOffsets.get(arrayName);
         if (off == null)
            throw new RuntimeException("Unknown array field " + arrayName);
         String arrTmp = newTemp();
         prefix = "HLOAD " + arrTmp + " TEMP 0 " + off + "\n";
         arrayPtr = arrTmp;
      }
      MiniIRExp index = n.f2.accept(this, env);
      index = ensureHasResult(index);
      MiniIRExp rhs = n.f5.accept(this, env);
      rhs = ensureHasResult(rhs);
      String offsetTmp = newTemp();
      String addrTmp = newTemp();
      String code = prefix + index.code + rhs.code
            + "MOVE " + offsetTmp + " TIMES 4 " + index.result + "\n"
            + "MOVE " + addrTmp + " PLUS " + arrayPtr + " " + offsetTmp + "\n"
            + "HSTORE " + addrTmp + " 4 " + rhs.result + "\n";
      return new MiniIRExp(code, null);
   }

   @Override
   public MiniIRExp visit(IfStatement n, Map<String, String> env) {
      return n.f0.accept(this, env);
   }

   @Override
   public MiniIRExp visit(IfthenStatement n, Map<String, String> env) {
      MiniIRExp cond = n.f2.accept(this, env);
      cond = ensureHasResult(cond);
      MiniIRExp thenS = n.f4.accept(this, env);
      String lend = newLabel();

      String code = cond.code + "CJUMP " + cond.result + " " + lend + "\n"
            + (thenS == null ? "" : thenS.code)
            + lend + "\nNOOP\n";
      return new MiniIRExp(code, null);
   }

   @Override
   public MiniIRExp visit(IfthenElseStatement n, Map<String, String> env) {
      MiniIRExp cond = n.f2.accept(this, env);
      cond = ensureHasResult(cond);
      MiniIRExp thenS = n.f4.accept(this, env);
      MiniIRExp elseS = n.f6.accept(this, env);
      String lelse = newLabel(), lend = newLabel();

      String code = cond.code + "CJUMP " + cond.result + " " + lelse + "\n" +
            (thenS == null ? "" : thenS.code) + "JUMP " + lend + "\n" +
            lelse + "\n" +
            (elseS == null ? "" : elseS.code) +
            lend + "\nNOOP\n";
      return new MiniIRExp(code, null);
   }

   @Override
   public MiniIRExp visit(WhileStatement n, Map<String, String> env) {
      String lstart = newLabel(), lend = newLabel();
      MiniIRExp cond = n.f2.accept(this, env);
      cond = ensureHasResult(cond);
      MiniIRExp body = n.f4.accept(this, env);
      String code = lstart + "\n" + cond.code + "CJUMP " + cond.result + " " + lend + "\n"
            + (body == null ? "" : body.code) + "JUMP " + lstart + "\n" + lend + "\nNOOP\n";
      return new MiniIRExp(code, null);
   }

   @Override
   public MiniIRExp visit(Expression n, Map<String, String> env) {
      return n.f0.accept(this, env);
   }

   @Override
    public MiniIRExp visit(LambdaExpression n, Map<String, String> env) {
        String lambdaClassName = lambdaAstToClassName.get(n);
        if (lambdaClassName == null) throw new RuntimeException("No synthetic class recorded for lambda AST");
        ClassLayout layout = classLayouts.get(lambdaClassName);
        if (layout == null) throw new RuntimeException("No ClassLayout for synthetic lambda class: " + lambdaClassName);

        String objPtr = newTemp();
        String vptr = newTemp();
        StringBuilder code = new StringBuilder();
        code.append("MOVE ").append(objPtr).append(" HALLOCATE ").append(layout.objectSize).append("\n");
        code.append("HLOAD ").append(vptr).append(" ").append(VTABLE_DIRECTORY_PTR).append(" ").append(layout.vTableDirectoryIndex * 4).append("\n");
        code.append("HSTORE ").append(objPtr).append(" 0 ").append(vptr).append("\n");

        MethodInfo apply = symbolTable.get(lambdaClassName).methods.get("apply");
        List<String> capturedVars = (apply != null) ? apply.capturedVars : Collections.emptyList();

        for (String capVar : capturedVars) {
            String valueToStore = null;
            ClassInfo currentClassInfo = symbolTable.get(this.currentClassName);
            boolean inLambdaApply = (currentClassInfo != null && currentClassInfo.isSynthetic());

            if (capVar.equals("this")) {
                if (inLambdaApply) {
                    Integer offset = classLayouts.get(this.currentClassName).fieldOffsets.get("this");
                    if (offset == null) throw new RuntimeException("Parent lambda was expected to capture 'this', but did not.");
                    String lexicalThisTemp = newTemp();
                    code.append("HLOAD ").append(lexicalThisTemp).append(" TEMP 0 ").append(offset).append("\n");
                    valueToStore = lexicalThisTemp;
                } else {
                    valueToStore = "TEMP 0";
                }
            } else {
                valueToStore = env.get(capVar);
                if (valueToStore == null) {
                    Integer offset = classLayouts.get(this.currentClassName).fieldOffsets.get(capVar);
                    if (offset == null) throw new RuntimeException("Cannot find captured var '" + capVar + "' in scope.");
                    String tmp = newTemp();
                    code.append("HLOAD ").append(tmp).append(" TEMP 0 ").append(offset).append("\n");
                    valueToStore = tmp;
                }
            }
            if (valueToStore == null) throw new RuntimeException("Could not find value for captured variable '" + capVar + "'.");
            Integer offInClosure = layout.fieldOffsets.get(capVar);
            if (offInClosure == null) throw new RuntimeException("Internal Error: Closure layout missing field for captured var: " + capVar);
            code.append("HSTORE ").append(objPtr).append(" ").append(offInClosure).append(" ").append(valueToStore).append("\n");
        }
        return new MiniIRExp(code.toString(), objPtr);
    }

   @Override
   public MiniIRExp visit(AndExpression n, Map<String, String> env) {
      String falseLabel = newLabel();
      String endLabel = newLabel();
      String resultTemp = newTemp();
      MiniIRExp lhs = n.f0.accept(this, env);
      MiniIRExp rhs = n.f2.accept(this, env);

      String code = lhs.code +
            "CJUMP " + lhs.result + " " + falseLabel + "\n" +
            rhs.code +
            "MOVE " + resultTemp + " " + rhs.result + "\n" +
            "JUMP " + endLabel + "\n" +
            falseLabel + "\n" +
            "MOVE " + resultTemp + " 0\n" +
            endLabel + "\n" + "NOOP\n";
      return new MiniIRExp(code, resultTemp);
   }

   @Override
   public MiniIRExp visit(OrExpression n, Map<String, String> env) {
      String trueLabel = newLabel();
      String endLabel = newLabel();
      String resultTemp = newTemp();
      MiniIRExp lhs = n.f0.accept(this, env);
      MiniIRExp rhs = n.f2.accept(this, env);

      String not_lhs = newTemp();

      String code = lhs.code +
            "MOVE " + not_lhs + " MINUS 1 " + lhs.result + "\n" +
            "CJUMP " + not_lhs + " " + trueLabel + "\n" +
            rhs.code +
            "MOVE " + resultTemp + " " + rhs.result + "\n" +
            "JUMP " + endLabel + "\n" +
            trueLabel + "\n" +
            "MOVE " + resultTemp + " 1\n" +
            endLabel + "\n" + "NOOP\n";

      return new MiniIRExp(code, resultTemp);
   }

   public MiniIRExp visit(CompareExpression n, Map<String, String> env) {
      return binaryOp("LE", n.f0, n.f2, env);
   }

   public MiniIRExp visit(neqExpression n, Map<String, String> env) {
      return binaryOp("NE", n.f0, n.f2, env);
   }

   public MiniIRExp visit(AddExpression n, Map<String, String> env) {
      return binaryOp("PLUS", n.f0, n.f2, env);
   }

   public MiniIRExp visit(MinusExpression n, Map<String, String> env) {
      return binaryOp("MINUS", n.f0, n.f2, env);
   }

   public MiniIRExp visit(TimesExpression n, Map<String, String> env) {
      return binaryOp("TIMES", n.f0, n.f2, env);
   }

   public MiniIRExp visit(DivExpression n, Map<String, String> env) {
      return binaryOp("DIV", n.f0, n.f2, env);
   }

   @Override
   public MiniIRExp visit(ArrayLength n, Map<String, String> env) {
      MiniIRExp arr = n.f0.accept(this, env);
      arr = ensureHasResult(arr);
      String r = newTemp();
      String code = arr.code + "HLOAD " + r + " " + arr.result + " 0\n";
      return new MiniIRExp(code, r);
   }

   @Override
   public MiniIRExp visit(ArrayLookup n, Map<String, String> env) {
      MiniIRExp arr = n.f0.accept(this, env);
      arr = ensureHasResult(arr);
      MiniIRExp idx = n.f2.accept(this, env);
      idx = ensureHasResult(idx);
      String off = newTemp(), addr = newTemp(), res = newTemp();
      String code = arr.code + idx.code
            + "MOVE " + off + " TIMES 4 " + idx.result + "\n"
            + "MOVE " + addr + " PLUS " + arr.result + " " + off + "\n"
            + "HLOAD " + res + " " + addr + " 4\n";
      return new MiniIRExp(code, res);
   }

   @Override
   public MiniIRExp visit(PrimaryExpression n, Map<String, String> env) {
      return (MiniIRExp) n.f0.accept(this, env);
   }

   @Override
   public MiniIRExp visit(ExpressionList n, Map<String, String> env) {
      MiniIRExp first = n.f0.accept(this, env);
      StringBuilder sb = new StringBuilder();
      if (first != null && first.code != null)
         sb.append(first.code);
      for (Node node : n.f1.nodes) {
         MiniIRExp r = ((ExpressionRest) node).f1.accept(this, env);
         if (r != null && r.code != null)
            sb.append(r.code);
      }
      return new MiniIRExp(sb.toString(), null);
   }

   @Override
   public MiniIRExp visit(ExpressionRest n, Map<String, String> env) {
      n.f0.accept(this, env);
      return n.f1.accept(this, env);
   }

   public MiniIRExp visit(IntegerLiteral n, Map<String, String> env) {
      return new MiniIRExp("", n.f0.tokenImage);
   }

   @Override
   public MiniIRExp visit(TrueLiteral n, Map<String, String> env) {
      return new MiniIRExp("", "1");
   }

   @Override
   public MiniIRExp visit(FalseLiteral n, Map<String, String> env) {
      return new MiniIRExp("", "0");
   }

   @Override
   public MiniIRExp visit(Identifier n, Map<String, String> env) {
      String name = n.f0.tokenImage;
      if (env != null && env.containsKey(name)) {
         return new MiniIRExp("", env.get(name));
      }
      if (this.currentClassName == null) {
         return new MiniIRExp("", null);
      }

      String owner = this.currentClassName;
      String declaringClass = null;
      while (owner != null) {
         ClassInfo ci = symbolTable.get(owner);
         if (ci != null && ci.fields.containsKey(name)) {
            declaringClass = owner;
            break;
         }
         owner = (ci != null) ? ci.parent : null;
      }
      if (declaringClass == null) {
         throw new RuntimeException("MiniIRVisitor: identifier '" + name + "' not found as local/param/field in class '"
               + this.currentClassName + "'.");
      }

      Integer offset = null;
      ClassLayout declLayout = classLayouts.get(declaringClass);
      if (declLayout != null)
         offset = declLayout.fieldOffsets.get(name);
      if (offset == null) {
         ClassLayout objLayout = classLayouts.get(this.currentClassName);
         if (objLayout != null)
            offset = objLayout.fieldOffsets.get(name);
      }
      if (offset == null) {
         throw new RuntimeException("Internal Compiler Error: Cannot find offset for field '" + name + "'");
      }

      String temp = newTemp();
      String code = "HLOAD " + temp + " TEMP 0 " + offset + "\n";
      return new MiniIRExp(code, temp);
   }

   @Override
    public MiniIRExp visit(ThisExpression n, Map<String, String> env) {
       ClassInfo ci = symbolTable.get(this.currentClassName);
       boolean inLambdaApply = (ci != null && ci.isSynthetic());
       if (inLambdaApply) {
          Integer offset = classLayouts.get(this.currentClassName).fieldOffsets.get("this");
          if (offset == null) throw new RuntimeException("Lexical 'this' used in lambda but not captured.");
          String capturedThisTemp = newTemp();
          String code = "HLOAD " + capturedThisTemp + " TEMP 0 " + offset + "\n";
          return new MiniIRExp(code, capturedThisTemp);
       } else {
          return new MiniIRExp("", "TEMP 0");
       }
    }

   @Override
   public MiniIRExp visit(ArrayAllocationExpression n, Map<String, String> env) {
      MiniIRExp size = n.f3.accept(this, env);
      size = ensureHasResult(size);

      String numElements = newTemp();
      String total = newTemp();
      String arrayPtr = newTemp();
      String idx = newTemp();
      String cond = newTemp();
      String off = newTemp();
      String addr = newTemp();
      String Lstart = newLabel();
      String Lend = newLabel();

      StringBuilder code = new StringBuilder();
      code.append(size.code);
      code.append("MOVE ").append(numElements).append(" ").append(size.result).append("\n");
      code.append("MOVE ").append(total).append(" PLUS 1 ").append(numElements).append("\n");
      code.append("MOVE ").append(total).append(" TIMES 4 ").append(total).append("\n");
      code.append("MOVE ").append(arrayPtr).append(" HALLOCATE ").append(total).append("\n");
      code.append("HSTORE ").append(arrayPtr).append(" 0 ").append(numElements).append("\n");

      code.append("MOVE ").append(idx).append(" 0\n");
      code.append(Lstart).append("\n");
      String bound = newTemp();
      code.append("MOVE ").append(bound).append(" MINUS ").append(numElements).append(" 1\n");
      code.append("MOVE ").append(cond).append(" LE ").append(idx).append(" ").append(bound).append("\n");

      code.append("CJUMP ").append(cond).append(" ").append(Lend).append("\n");
      code.append("MOVE ").append(off).append(" TIMES 4 ").append(idx).append("\n");
      code.append("MOVE ").append(addr).append(" PLUS ").append(arrayPtr).append(" ").append(off).append("\n");
      code.append("HSTORE ").append(addr).append(" 4 0\n");
      code.append("MOVE ").append(idx).append(" PLUS ").append(idx).append(" 1\n");
      code.append("JUMP ").append(Lstart).append("\n");
      code.append(Lend).append("\n");
      code.append("NOOP\n");

      return new MiniIRExp(code.toString(), arrayPtr);
   }

   @Override
   public MiniIRExp visit(AllocationExpression n, Map<String, String> env) {
      String className = n.f1.f0.tokenImage;
      ClassLayout layout = classLayouts.get(className);
      String objPtr = newTemp();
      String vptr = newTemp();
      int vIndex = layout.vTableDirectoryIndex;
      StringBuilder code = new StringBuilder();
      code.append("MOVE ").append(objPtr).append(" HALLOCATE ").append(layout.objectSize).append("\n");
      code.append("HLOAD ").append(vptr).append(" ").append(VTABLE_DIRECTORY_PTR).append(" ").append(vIndex * 4)
            .append("\n");
      code.append("HSTORE ").append(objPtr).append(" 0 ").append(vptr).append("\n");
      for (int offset = 4; offset < layout.objectSize; offset += 4) {
         code.append("HSTORE ").append(objPtr).append(" ").append(offset).append(" 0\n");
      }
      return new MiniIRExp(code.toString(), objPtr);
   }

   @Override
   public MiniIRExp visit(NotExpression n, Map<String, String> env) {
      MiniIRExp e = n.f1.accept(this, env);
      e = ensureHasResult(e);
      String t = newTemp();
      String code = e.code + "MOVE " + t + " MINUS 1 " + e.result + "\n";
      return new MiniIRExp(code, t);
   }

   @Override
   public MiniIRExp visit(BracketExpression n, Map<String, String> env) {
      return n.f1.accept(this, env);
   }
}