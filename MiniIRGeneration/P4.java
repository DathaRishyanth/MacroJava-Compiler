import syntaxtree.*;
import visitor.*;

public class P4 {
   public static void main(String [] args) {
      try {
         Node root = new MiniIRParser(System.in).Goal();
         
         GJVisitor<Object, Void> visitor = new GJDepthFirst<Object, Void>();
         Object result = root.accept(visitor, null);
         
         if (result instanceof String) {
            System.out.println((String) result);
         }
      }
      catch (ParseException e) {
         System.out.println(e.toString());
      }
   }
}