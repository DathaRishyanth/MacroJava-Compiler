package visitor;

import java.util.HashMap;
import java.util.Map;


class ClassInfo {
      String parent= null;
      public Map<String, String> fields = new HashMap<>();
      public Map<String, MethodInfo> methods = new HashMap<>();
      public Map<String, String> var_types = new HashMap<>();
      public String parent_class;

   
      public boolean addField(String name, String type) {
         fields.put(name, type);
         return true;
      }

 
      public boolean addMethod(String name, MethodInfo method) {
         methods.put(name, method);
         return true;
      }

      public void fillvar_types()
      {
         for(String field_name : fields.keySet())
         {
            var_types.put(field_name, fields.get(field_name));
         }

         for(String method_name : methods.keySet())
         {
            MethodInfo method = methods.get(method_name);
            for(String param_name : method.parameters.keySet())
            {
               var_types.put(param_name, method.parameters.get(param_name));
            }
            for(String localvar_name : method.localVariables.keySet())
            {
               var_types.put(localvar_name, method.localVariables.get(localvar_name));
            }
         }
      }

      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        if (parent!= null) {
            sb.append("  Extends: ").append(parent).append("\n");
        }
        sb.append("  Fields: ").append(fields).append("\n");
        sb.append("  Methods: ").append(methods);
   
        
        return sb.toString();
      }
   }