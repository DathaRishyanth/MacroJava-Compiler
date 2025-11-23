import syntaxtree.*;
import visitor.*;
import java.io.*;

public class P5 {
    

    public static void main(String[] args) {
        InputStream in = System.in;
        try {
            microIRParser parser = new microIRParser(in);
            Node root = parser.Goal();

            BuildCFGAndLiveness builder = new BuildCFGAndLiveness();
            root.accept(builder, null);


            MicroIRtoMiniRAVisitor miniRAVisitor = new MicroIRtoMiniRAVisitor(builder);
            root.accept(miniRAVisitor, null);

            String miniRA = miniRAVisitor.getMiniRACode();
            System.out.print(miniRA);

        } catch (ParseException pex) {
            System.err.println("Parse error while reading microIR: " + pex.getMessage());
            pex.printStackTrace(System.err);
            System.exit(2);
        } catch (Throwable t) {
            System.err.println("Unhandled exception in P5: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(4);
        }
    }
}
