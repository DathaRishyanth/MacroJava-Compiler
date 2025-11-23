package visitor;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

public class MiniIRExp {
    public String code;
    public String result; 

    public MiniIRExp(String code, String result) {
        this.code = code;
        this.result = result;
    }
}

class ClassLayout {
    public String className;
    public Map<String, Integer> fieldOffsets;
    public int objectSize;
    public String vTableTemp; 
    public int vTableDirectoryIndex;
    private boolean finalized = false;

    public ClassLayout(String name) {
        this.className = name;
        this.fieldOffsets = new LinkedHashMap<>();
        this.objectSize = 4; 
        this.vTableTemp = null;
        this.vTableDirectoryIndex = -1;
    }

    public void finalizeLayout() {
        this.finalized = true;
    }
    public boolean isFinalized() {
        return this.finalized;
    }

    private int nextFieldOffset = 4; 

    public void addField(String name) {
        fieldOffsets.put(name, nextFieldOffset);
        nextFieldOffset += 4;
        objectSize = nextFieldOffset;
    }
}

class VTable {
    public String className;
    public Map<String, Integer> methodOffsets;
    public List<String> methodLabels;

    public VTable(String name) {
        this.className = name;
        this.methodOffsets = new LinkedHashMap<>();
        this.methodLabels = new ArrayList<>();
    }

    public void addMethod(String methodName, String definingClass) {
        if (!methodOffsets.containsKey(methodName)) {
            int offset = methodLabels.size() * 4;
            methodOffsets.put(methodName, offset);
            methodLabels.add(definingClass + "_" + methodName);
        }
    }
}