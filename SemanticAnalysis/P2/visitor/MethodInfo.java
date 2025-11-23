package visitor;

import java.util.*;

class MethodInfo {
      String returnType;
      public LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
      public Map<String, String> localVariables = new HashMap<>();
      public Map<String, String> var_types = new HashMap<>();


      public boolean addParameter(String name, String type) {
         parameters.put(name, type);
         var_types.put(name, type);
         return true;
      }


      public boolean addLocalVariable(String name, String type) {
         localVariables.put(name, type);
         var_types.put(name, type);
         return true;
      }
      
      @Override
      public String toString() {
         return "[Returns: " + returnType + ", Params: " + parameters + ", Locals: " + localVariables + "]";
      }
   }