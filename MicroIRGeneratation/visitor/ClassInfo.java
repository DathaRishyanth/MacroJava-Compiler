package visitor;

import java.util.LinkedHashMap;
import java.util.Map;
import syntaxtree.Node;
import java.util.Optional;

public class ClassInfo {
    public final String name;
    public String parent;

    // existing maps (use LinkedHashMap to preserve order)
    public Map<String, String> fields = new LinkedHashMap<>();
    public Map<String, MethodInfo> methods = new LinkedHashMap<>();

    // --- new fields for synthetic lambda support ---
    private boolean synthetic = false;
    // optional: the lambda AST node that produced this synthetic class
    private Node lambdaAst = null;

    public ClassInfo(String name, String parent) {
        this.name = name;
        this.parent = parent;
    }

    // mark as synthetic and attach the lambda AST node which produced this class
    public void markSynthetic(Node lambdaAstNode) {
        this.synthetic = true;
        this.lambdaAst = lambdaAstNode;
    }

    public boolean isSynthetic() {
        return this.synthetic;
    }

    public Optional<Node> getLambdaAst() {
        return Optional.ofNullable(this.lambdaAst);
    }
}
