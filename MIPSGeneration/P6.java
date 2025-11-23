import visitor.*;


public class P6 {
    public static void main(String [] args) {
        try {
           
            MiniRAParser parser = new MiniRAParser(System.in);

            syntaxtree.Goal root = parser.Goal();

            
            GJVisitor<String, Void> visitor = new GJDepthFirst<String, Void>();

            root.accept(visitor, null);

          
            String mipsCode = ((GJDepthFirst<String, Void>) visitor).getMIPSCode();

            System.out.println(mipsCode);

        } catch (ParseException e) {
            System.err.println("Parser Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred:");
            e.printStackTrace();
        }
    }
}