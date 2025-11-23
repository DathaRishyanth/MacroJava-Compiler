package visitor;
import java.util.List;
import java.util.ArrayList;
import syntaxtree.*;
public class LambdaMeta {
      public Node lambdaAst;
      public List<String> paramNames = new ArrayList<>();
      public List<String> capturedVars = new ArrayList<>();
}