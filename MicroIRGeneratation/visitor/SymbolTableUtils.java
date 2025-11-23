package visitor;

import java.util.*;;

public class SymbolTableUtils {
    /** Nicely prints the symbol table for debugging. */
    public static void printSymbolTable(Map<String, visitor.ClassInfo> symbolTable) {
        if (symbolTable == null) {
            System.out.println("symbolTable == null");
            return;
        }
        if (symbolTable.isEmpty()) {
            System.out.println("symbolTable is empty");
            return;
        }

        // Deterministic order
        List<String> classNames = new ArrayList<>(symbolTable.keySet());
        Collections.sort(classNames);

        for (String cname : classNames) {
            visitor.ClassInfo ci = symbolTable.get(cname);
            System.out.println("Class: " + cname);
            System.out.println("  Parent: " + (ci.parent == null ? "<none>" : ci.parent));

            System.out.println("  Fields:");
            if (ci.fields == null || ci.fields.isEmpty()) {
                System.out.println("    (none)");
            } else {
                for (Map.Entry<String, String> fe : ci.fields.entrySet()) {
                    System.out.println("    " + fe.getKey() + " : " + fe.getValue());
                }
            }

            System.out.println("  Methods:");
            if (ci.methods == null || ci.methods.isEmpty()) {
                System.out.println("    (none)");
            } else {
                // Preserve declaration order if MethodInfo map is LinkedHashMap
                for (Map.Entry<String, visitor.MethodInfo> me : ci.methods.entrySet()) {
                    visitor.MethodInfo mi = me.getValue();
                    System.out.println("    " + mi.name + " -> return: " + mi.returnType);
                    // params
                    if (mi.params == null || mi.params.isEmpty()) {
                        System.out.println("      params: (none)");
                    } else {
                        StringBuilder ps = new StringBuilder();
                        for (Map.Entry<String, String> p : mi.params.entrySet()) {
                            if (ps.length() > 0) ps.append(", ");
                            ps.append(p.getKey()).append(":").append(p.getValue());
                        }
                        System.out.println("      params: " + ps.toString());
                    }
                    // locals
                    if (mi.locals == null || mi.locals.isEmpty()) {
                        System.out.println("      locals: (none)");
                    } else {
                        StringBuilder ls = new StringBuilder();
                        for (Map.Entry<String, String> l : mi.locals.entrySet()) {
                            if (ls.length() > 0) ls.append(", ");
                            ls.append(l.getKey()).append(":").append(l.getValue());
                        }
                        System.out.println("      locals: " + ls.toString());
                    }
                }
            }

            // optional ClassInfo fields (if present)
            try {
                if (ci.vTableTemp != null) {
                    System.out.println("  vTableTemp: " + ci.vTableTemp);
                }
            } catch (Throwable ignored) {}

            System.out.println();
        }
    }
}
