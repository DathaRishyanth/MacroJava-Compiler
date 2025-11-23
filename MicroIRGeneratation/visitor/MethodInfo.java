package visitor;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import syntaxtree.Node;

public class MethodInfo {
    public final String name;
    public String returnType;
    public final Map<String, String> params = new LinkedHashMap<>();
    public final Map<String, String> locals = new LinkedHashMap<>();

    public boolean isSyntheticLambda = false;
    public Node lambdaAst = null; 
    public final List<String> capturedVars = new LinkedList<>();

    public MethodInfo(String name, String returnType) {
        this.name = name;
        this.returnType = returnType;
    }
}
