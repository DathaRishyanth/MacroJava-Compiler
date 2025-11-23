#define NUM() (10+0)
#define ONE() (1+0)
class Factorial{
    public static void main(String[] a){
        System.out.println(new Fac().ComputeFac(NUM()));
    }
}

// This class computes the factorial of a number
// using a recursive method
/* Comment 
   should be ignored */
class Fac {
    public int ComputeFac(int num, int a){
        int num_aux ;
        if ((num <= 1)&&(num != 1))
            num_aux = ONE() ;
        else
            num_aux = num * (this.ComputeFac(num-1)) ;
        return num_aux ;
    }
}
