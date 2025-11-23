%{
#include <bits/stdc++.h>
#include <regex>
#include <sstream>
using namespace std;

void yyerror(const char *s){
    printf("// Failed to parse macrojava code.");
    exit(1);
}


struct Macro {
    bool isExpression;
    vector<string> params;
    string body;
};



static int lambdaCounter = 0;
map<string, Macro> macroTable;
int indent = 0;


string indentation(int level) {
    return string(level * 4, ' ');
}




string trim_parameters(const string& s){
    size_t i=0, j=s.size();
    while(i<j && isspace((unsigned char)s[i])) ++i;
    while(j>i && isspace((unsigned char)s[j-1])) --j;
    return s.substr(i, j-i);
}


static vector<string> parse_arguments(const string& s){
    vector<string> out; 
    string cur;
    int depth=0;
    bool in_single=false, in_double=false;
    for(size_t i=0;i<s.size();++i){
        char c=s[i];
        if(c=='\'' && !in_double) 
        { 
            in_single = !in_single; 
            cur.push_back(c); 
            continue; 
        }
        if(c=='\"' && !in_single) 
        { 
            in_double = !in_double; 
            cur.push_back(c); 
            continue; 
        }
        if(!in_single && !in_double) {
            if(c=='('||c=='['||c=='{'){ 
                ++depth; 
                cur.push_back(c); 
                continue; 
            }
            if(c==')'||c==']'||c=='}'){ --depth; cur.push_back(c); continue; }
            if(c==',' && depth==0){ out.push_back(trim_parameters(cur)); cur.clear(); continue; }
        }
        cur.push_back(c);
    }
    if(!cur.empty()) out.push_back(trim_parameters(cur));
    if(out.size()==1 && out[0].empty()) out.clear();
    return out;
}


static vector<string> split_parameters(const string& s){
    vector<string> out; string cur;
    for(char c: s){ 
        if(c==','){ 
        out.push_back(trim_parameters(cur)); 
        cur.clear(); 
        } 
        else 
        cur.push_back(c); }
    if(!cur.empty()) out.push_back(trim_parameters(cur));
    if(out.size()==1 && out[0].empty()) out.clear();
    return out;
}

static string substitute_arguments(const string& body,
                               const vector<string>& params,
                               const vector<string>& args){
    string res = body;
    vector<string> tobechanged;
    for (size_t i = 0; i < params.size(); ++i) {
        string p_holder = "__MACRO_PARAM_" + to_string(i) + "__";
        tobechanged.push_back(p_holder);
        regex re("\\b" + params[i] + "\\b");
        res = regex_replace(res, re, p_holder);
    }

    for (size_t i = 0; i < tobechanged.size(); ++i) {
        regex re(tobechanged[i]);
        string replaced_var = "";

        if(regex_match(args[i], regex("^[a-zA-Z_][a-zA-Z0-9_]*$")))
            replaced_var = args[i];
        else
            replaced_var += '(' + args[i] + ')';
        res = regex_replace(res, re, replaced_var);
    }

    return res;
}


static string reindent_to(const string& s, int level){
    string ind = indentation(level);
    string out;
    bool atLineStart = true;
    for(char c : s){
        if(atLineStart) out += ind;
        out += c;
        atLineStart = (c == '\n');
    }
    return out;
}

static string expand_macro(const char* nameC, const char* argsStrC, bool expectExpression){
    string name = nameC;
    string argsStr = argsStrC;

    auto it = macroTable.find(name);
    if(it == macroTable.end())
    {
        yyerror("error");
    }

    const Macro& macro = it->second;
    if(expectExpression && !macro.isExpression)
        yyerror("error");
    if(!expectExpression && macro.isExpression)
        yyerror("error");
    vector<string> args = parse_arguments(argsStr);
    if(args.size() != macro.params.size()){
        yyerror("error");
    }
    return substitute_arguments(macro.body, macro.params, args);
}


int yylex(void);
%}

%start Goal


%union {
   char* val;
}

%token CLASS PUBLIC STATIC VOID MAIN STRING EXTENDS RETURN NEW IMPORT JAVA UTIL APPLY FUNCTION FUNCTION_ ARROW
%token EQ NEQ LEQ AND OR
%token IF ELSE WHILE DO
%token TRUE_ FALSE_ THIS LENGTH

%type<val> Expression MatchedStatement Type ExprList ExpressionRests ExpressionRest
%type<val>  TypeDeclarations TypeDeclaration VarDecls VarDecl
%type<val> Statements Statement UnmatchedStatement Block ClassType
%type<val> OptExprs OptParams ParamList ParamRests
%type<val> FunctionType FunctionDecl
%type<val> MacroDefinitions MacroDefinition MacroDefExpression MacroDefStatement MethodDeclarations MethodDeclaration
%type<val> OptIdList IdList IdRests IdRest ImportFunctionOpt Goal MainClass 

%token PRINT
%token INT BOOLEAN

%nonassoc IFX
%nonassoc ELSE

%token DEFINE

%right '='
%right ARROW
%left OR
%left AND
%left NEQ LEQ
%left '+' '-'
%left '*' '/'
%right '!'
%token UMINUS
%left '.' '[' ']'

%token <val> IDENTIFIER INTEGER_LITERAL 

%%



Goal
    : ImportFunctionOpt MacroDefinitions MainClass TypeDeclarations
    {
        string s = "";
        s += string($1);
        s += string($2);
        s += string($3);
        s += string($4);
        cout << s;
    }
    ;
ImportFunctionOpt :
    IMPORT JAVA '.' UTIL '.' FUNCTION_ '.' FUNCTION ';'
    {
        $$ = strdup("import java.util.function.Function;\n");
    }
    |
    { $$ = strdup(""); }
    ;

MacroDefinitions :
    MacroDefinition MacroDefinitions
    {
        $$ = strdup((string($1) + string($2)).c_str());
    }
    |{ $$ = strdup(""); }
    ;

TypeDeclarations :
    TypeDeclaration TypeDeclarations
    {
        $$ = strdup((string($1) + string($2)).c_str());
    }
    |{ $$ = strdup(""); }
    ;

MainClass
    : CLASS IDENTIFIER '{' PUBLIC STATIC VOID MAIN'(' STRING '[' ']' IDENTIFIER ')' '{'
      PRINT'(' Expression')' ';'
      '}'
      '}'
      {
        string s = "class " + string($2) + " {\n";
        indent++;
        s += indentation(indent) + "public static void main(String[] " + string($12) + ") {\n";
        indent++;
        s += indentation(indent) + "System.out.println(" + string($17) + ");\n";
        indent--;
        s += indentation(indent) + "}\n";
        indent--;
        s += "}\n";
        $$ = strdup(s.c_str());
      }
    ;

TypeDeclaration
    : CLASS IDENTIFIER'{' VarDecls MethodDeclarations '}'
    {
        string s = "class " + string($2) + " {\n";
        indent++;
        s += string($4);
        s += string($5);
        indent--;
        s += "}\n";
        $$ = strdup(s.c_str());
    }
    | CLASS IDENTIFIER EXTENDS IDENTIFIER '{' VarDecls MethodDeclarations '}'
    {
        string s = "class " + string($2) + " extends " + string($4) + " {\n";
        indent++;
        s += string($6);
        s += string($7);
        indent--;
        s += "}\n";
        $$ = strdup(s.c_str());
    }
    ;

VarDecls :
    VarDecl VarDecls
    {
        $$ = strdup((string($1) + string($2)).c_str());
    }
    |{$$ = strdup("");}
    ;

VarDecl :
    Type IDENTIFIER ';'
    {
        string s = indentation(indent) + string($1) + " " + string($2) + ";\n";
        $$ = strdup(s.c_str());
    }
    | FunctionDecl IDENTIFIER ';'
    {
        string s = indentation(indent) + string($1) + " " + string($2) + ";\n";
        $$ = strdup(s.c_str());
    }
    ;

FunctionDecl :
    FUNCTION '<' IDENTIFIER ','IDENTIFIER '>'
    {
        string s = "Function <" + string($3) + ", " + string($5) + ">";
        $$ = strdup(s.c_str());
    };

MethodDeclarations :
    MethodDeclaration MethodDeclarations
    {
        $$ = strdup((string($1) + string($2)).c_str());
    }
    |{ $$ = strdup(""); }
    ;

MethodDeclaration
    : FunctionType IDENTIFIER '(' OptParams ')' '{' {indent+= 2;} Statements RETURN Expression ';' '}'
    {
        indent-= 2;
        string s = indentation(indent) + string($1) + string($2) + "(" + string($4) + ") {\n";
        s += string($8);
        s += indentation(indent + 2) + "return " + string($10) + ";\n";
        s += indentation(indent + 1) + "}\n";
        $$ = strdup(s.c_str());
    }
    ;

FunctionType :
    PUBLIC Type
    {
        string s = "    public " + string($2) + " ";
        $$ = strdup(s.c_str());
    }
    ;

OptParams :
    ParamList
    {
        $$ = $1;
    }
    |{$$ = strdup("");}
    ;

ParamList :
    Type IDENTIFIER ParamRests
    {
        string s = string($1) + " " + string($2) + string($3);
        $$ = strdup(s.c_str());
    }
    ;

ParamRests :
    ',' Type IDENTIFIER ParamRests
    {
        string s = ", " + string($2) + " " + string($3) + string($4);
        $$ = strdup(s.c_str());
    }
    | {$$ = strdup("");}
    ;

Type
    : INT '[' ']'{$$ = strdup("int[]");}
    | BOOLEAN{$$ = strdup("boolean");}
    | INT{$$ = strdup("int");}
    | ClassType{$$ = $1;}
    | '(' Type ARROW Type ')'{
        string s = "(" + string($2) + " -> " + string($4) + ")";
        $$ = strdup(s.c_str());
    }
    ;

ClassType
    : IDENTIFIER{ $$ = $1; }
    ;

Statements  :
    Statement Statements
    {
        $$ = strdup((string($1) + string($2)).c_str());
    }
    |{ $$ = strdup(""); }
    ;

Statement
    : MatchedStatement{$$ = $1;}
    | UnmatchedStatement{$$ = $1;}
    | VarDecl{$$ = $1;}
    ;

MatchedStatement
    : IF '(' Expression ')' MatchedStatement ELSE MatchedStatement
      {
          string s = indentation(indent) + "if (" + string($3) + ")\n";
          indent++; s += string($5); indent--;
          s += indentation(indent) + "else\n";
          indent++; s += string($7); indent--;
          $$ = strdup(s.c_str());
          free($3); free($5); free($7);
      }
    | WHILE '(' Expression ')' MatchedStatement
      {
          string s = indentation(indent) + "while (" + string($3) + ")\n";
          s += string($5);
          $$ = strdup(s.c_str());
          free($3); free($5);
      }
    | 
      PRINT'(' Expression ')' ';'
      {
          string s = indentation(indent) + "System.out.println(" + string($3) + ");\n";
          $$ = strdup(s.c_str());
          free($3);
      }
    | Block { $$ = $1; }
    | IDENTIFIER '=' Expression ';'
      {
          string s = indentation(indent) + string($1) + " = " + string($3) + ";\n";
          $$ = strdup(s.c_str());
          free($1); free($3);
      }
    | IDENTIFIER '[' Expression ']' '=' Expression ';'
      {
          string s = indentation(indent) + string($1) + "[" + string($3) + "] = " + string($6) + ";\n";
          $$ = strdup(s.c_str());
          free($1); free($3); free($6);
      }
    | IDENTIFIER '(' OptExprs ')' ';'
      {
          
        string expanded = expand_macro($1, $3, false);
        string s = reindent_to(expanded, indent);
        $$ = strdup(s.c_str());
         
          free($1); free($3);
      }
    ;


Block :
    '{' {indent++;} Statements '}'
    {
        indent--;
        string s = indentation(indent) + "{\n";
        s += string($3);
        s += indentation(indent) + "}\n";
        $$ = strdup(s.c_str());
    }
    ;

UnmatchedStatement
    : IF '(' Expression ')' Statement %prec IFX
      {
          string s = indentation(indent) + "if (" + string($3) + ")\n";
          indent++;
          s += string($5);
          indent--;
          $$ = strdup(s.c_str());
          free($3); free($5);
      }
    | IF '(' Expression ')' MatchedStatement ELSE UnmatchedStatement
      {
          string s = indentation(indent) + "if (" + string($3) + ")\n";
          indent++; s += string($5); indent--;
          s += indentation(indent) + "else\n";
          indent++; s += string($7); indent--;
          $$ = strdup(s.c_str());
          free($3); free($5); free($7);
      }
    ;

OptExprs
    :{
        $$ = strdup("");
    }
    | ExprList
    {
        $$ = $1;
    }
    ;

ExprList :
    Expression ExpressionRests
    {
        string s = string($1) + $2;
        $$ = strdup(s.c_str());
    }
    ;

ExpressionRests :
    ExpressionRest ExpressionRests
    {
        $$ = strdup((string($1) + string($2)).c_str());
    }
    |{ $$ = strdup(""); }
    ;

ExpressionRest :
    ',' Expression
    {
        $$ = strdup((string(", ") + string($2)).c_str());
    }
    ;

Expression :
      INTEGER_LITERAL { $$ = $1; }
    | TRUE_           { $$ = strdup("true"); }
    | FALSE_          { $$ = strdup("false"); }
    | IDENTIFIER      { $$ = $1; }
    | THIS            { $$ = strdup("this"); }
    | Expression '.' LENGTH
        { $$ = strdup((string($1) + ".length").c_str()); }
    | Expression '.' IDENTIFIER '(' OptExprs ')'
        { $$ = strdup((string($1) + "." + string($3) + "(" + string($5) + ")").c_str());}
    | NEW INT '[' Expression ']'
        { $$ = strdup(("new int[" + string($4) + "]").c_str()); }
    | NEW IDENTIFIER '(' ')'
        { $$ = strdup(("new " + string($2) + "()").c_str()); }
    | Expression '[' Expression ']'
        { $$ = strdup((string($1) + "[" + string($3) + "]").c_str()); }
    | '!' Expression
        { $$ = strdup(("!" + string($2)).c_str()); }
    | Expression '*' Expression
        { $$ = strdup((string($1) + " * " + string($3)).c_str());}
    | Expression '/' Expression
        { $$ = strdup((string($1) + " / " + string($3)).c_str()); }
    | Expression '+' Expression
        { $$ = strdup((string($1) + " + " + string($3)).c_str()); }
    | Expression '-' Expression
        { $$ = strdup((string($1) + " - " + string($3)).c_str()); }
    | Expression LEQ Expression
        { $$ = strdup((string($1) + " <= " + string($3)).c_str()); }
    | Expression NEQ Expression
        { $$ = strdup((string($1) + " != " + string($3)).c_str()); }
    | Expression AND Expression
        { $$ = strdup((string($1) + " && " + string($3)).c_str()); }
    | Expression OR Expression
        { $$ = strdup((string($1) + " || " + string($3)).c_str()); }
    | IDENTIFIER '(' OptExprs ')'
    {
        try {
            $$ = strdup(expand_macro($1, $3, true).c_str());
        } catch (const runtime_error& e) {
            yyerror(e.what());
            YYERROR;
        }
    }
    | '(' Expression ')'
        { $$ = strdup(("(" + string($2) + ")").c_str()); }
    | Expression ARROW Expression
    {
        string old_var_with_parens = string($1);
        string old_var_without_parens = old_var_with_parens.substr(1, old_var_with_parens.size() - 2);
        string new_var = "__lambda_param_" + to_string(lambdaCounter++) + "__";
        string body = string($3);
        regex re("\\b" + old_var_without_parens + "\\b");
        body = regex_replace(body, re, new_var);
        string s = "((" + new_var + ") -> " + body + ")";
        $$ = strdup(s.c_str());

       
        

    }
    | Expression '.' APPLY '(' Expression ')'
        { $$ = strdup((string($1) + ".apply(" + string($5) + ")").c_str()); }
    ;

MacroDefinition :
    MacroDefExpression
    | MacroDefStatement
    ;

MacroDefExpression
    : DEFINE IDENTIFIER '(' OptIdList ')' '(' Expression ')'
    {
        string name($2 ? $2 : "");
        string paramsStr($4 ? $4 : "");
        string body($7 ? $7 : "");
        vector<string> params = split_parameters(paramsStr);
        set<string> paramSet(params.begin(), params.end());
        if(paramSet.size() != params.size()){
            yyerror("Duplicate parameter in macro definition");
            YYERROR;
        }

        Macro macro;
        macro.isExpression = true;
        macro.params = params;
        macro.body = body;
        macroTable[name] = macro;
        $$ = strdup("");
    }
    ;

MacroDefStatement
    : DEFINE IDENTIFIER '(' OptIdList ')' '{' Statements '}'
    {
        string name($2 ? $2 : "");
        string paramsStr($4 ? $4 : "");
        string body($7 ? $7 : "");
        vector<string> params = split_parameters(paramsStr);
        set<string> paramSet(params.begin(), params.end());
        if(paramSet.size() != params.size()){
            yyerror("Duplicate parameter in macro definition");
            YYERROR;
        }

        Macro macro;
        macro.isExpression = false;
        macro.params = params;
        macro.body = body;
        macroTable[name] = macro;
        $$ = strdup("");
    }
    ;

OptIdList :
    IdList{$$ = $1; }
    |{ $$ = strdup(""); }
    ;

IdList :
    IDENTIFIER IdRests{$$ = strdup((string($1) + string($2)).c_str()); }
    ;

IdRests :
    IdRest IdRests{$$ = strdup((string($1) + string($2)).c_str()); }
    |{ $$ = strdup(""); }
    ;

IdRest :
    ',' IDENTIFIER
    {
        $$ = strdup((string(", ") + string($2)).c_str());
    }
    ;



%%

int main(void) {
    return yyparse();
}