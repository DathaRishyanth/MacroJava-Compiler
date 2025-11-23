package visitor;
import java.util.*;

public class Context {
    /** The master symbol table mapping class names to their info. */
    public Map<String, ClassInfo> symbolTable;

    /** The name of the class currently being visited. */
    public String currentClassName;
    /** The name of the method currently being visited. */
    public String currentMethodName;

    /** A map from a variable name (param or local) to its TEMP number in the current method. */
    public Map<String, Integer> varToTempMap;

    public int tempCounter = 20; // Start temps high to avoid conflict with params
    public int labelCounter = 0;

    public Context(Map<String, ClassInfo> st) {
        this.symbolTable = st;
        this.varToTempMap = new LinkedHashMap<>();
    }

    /** Generates a new, unique temporary variable string (e.g., "TEMP 20"). */
    public String newTemp() {
        return "TEMP " + (tempCounter++);
    }

    /** Generates a new, unique label string (e.g., "L0"). */
    public String newLabel() {
        return "L" + (labelCounter++);
    }
    
    /** Gets the ClassInfo for the current class. */
    public ClassInfo getCurrentClass() {
        return symbolTable.get(currentClassName);
    }
    
    /** Gets the MethodInfo for the current method. */
    public MethodInfo getCurrentMethod() {
        if (getCurrentClass() == null || currentMethodName == null) return null;
        return getCurrentClass().methods.get(currentMethodName);
    }
}