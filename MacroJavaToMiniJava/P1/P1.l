%{
    #include "P1.tab.h"
    #include <bits/stdc++.h>
    using namespace std;
    void yyerror(char *);
%}


%%

"//".*                { //yylval.val = strdup(yytext); return COMMENT; 
}

"/*"([^*]|\*[^/])*"*/"    { //yylval.val = strdup(yytext); return COMMENT; 
}


"class"                 { return CLASS; }
"public"                { return PUBLIC; }
"static"                { return STATIC; }
"void"                  { return VOID; }
"main"                  { return MAIN; }
"String"                { return STRING; }
"extends"               { return EXTENDS; }
"return"                { return RETURN; }
"int"                   { return INT; }
"boolean"               { return BOOLEAN; }
"length"                { return LENGTH; }
"true"                  { return TRUE_; }
"false"                 { return FALSE_; }

"if"                    { return IF; }
"else"                  { return ELSE; }
"while"                 { return WHILE; }
"this"                  { return THIS; }
"new"                   { return NEW; }
"do"                    { return DO; }



"#define"               { return DEFINE; }


"System.out.println"    { return PRINT; }
"apply"                { return APPLY; }
"import"                { return IMPORT; }
"java"                 { return JAVA; }
"util"                 { return UTIL; }
"function"            { return FUNCTION_; }
"Function"         { return FUNCTION; }


"->"                    { return ARROW; }
"("                     { return '('; }
")"                     { return ')'; }
"{"                     { return '{'; }
"}"                     { return '}'; }
"["                     { return '['; }
"]"                     { return ']'; }
";"                     { return ';'; }
"."                     { return '.'; }
","                     { return ','; }
"="                     { return '='; }
"!"                     { return '!'; }
">"                     { return '>'; }
"<"                     { return '<'; }
"+"                     { return '+'; }
"-"                     { return '-'; }
"*"                     { return '*'; }
"&&"                    { return AND; }
"||"                    { return OR; }
"=="                    { return EQ; }
"!="                    { return NEQ; }
"<="                    { return LEQ; }









[0-9]+                  { yylval.val = strdup(yytext); return INTEGER_LITERAL; }
[a-zA-Z_][a-zA-Z0-9_]*   { yylval.val = strdup(yytext); return IDENTIFIER; }

[ \t\r\n]+               { /* ignore whitespace */ }
.                   { /* ignore any other character */ }

%%
int yywrap(void) {
    return 1;
}

    