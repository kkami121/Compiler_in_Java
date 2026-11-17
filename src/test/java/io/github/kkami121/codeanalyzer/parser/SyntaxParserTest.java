package io.github.kkami121.codeanalyzer.parser;

import io.github.kkami121.codeanalyzer.CodeAnalyzer;
import io.github.kkami121.codeanalyzer.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxParserTest {

    private static SyntaxTree python(String source) {
        return CodeAnalyzer.parse(source, Language.PYTHON);
    }

    private static SyntaxTree java(String source) {
        return CodeAnalyzer.parse(source, Language.JAVA);
    }

    private static String sexpr(SyntaxTree tree) {
        assertFalse(tree.hasErrors(), () -> "unexpected errors: " + tree.errors());
        return tree.root().toSExpression();
    }

    // ------------------------------------------------------------ Python

    @Test
    void pythonFunctionWithDecoratorsAnnotationsAndDefaults() {
        String tree = sexpr(python("@app.route('/x')\nasync def h(a: int = [], *args, b=1, **kw) -> dict:\n    return a\n"));
        assertEquals("(MODULE (FUNCTION h (DECORATOR app.route (LITERAL '/x')) (PARAMETER a (TYPE (NAME int)) (COLLECTION [))"
                + " (PARAMETER *args) (PARAMETER b (LITERAL 1)) (PARAMETER **kw) (TYPE (NAME dict))"
                + " (BLOCK (RETURN (NAME a)))))", tree);
    }

    @Test
    void pythonOperatorPrecedence() {
        assertEquals("(MODULE (EXPRESSION_STATEMENT (BINARY or (BINARY and (NAME a) (UNARY not (NAME b)))"
                        + " (BINARY < (BINARY + (NAME c) (BINARY * (NAME d) (BINARY ** (NAME e) (NAME f)))) (LITERAL 1)))))",
                sexpr(python("a and not b or c + d * e ** f < 1\n")));
    }

    @Test
    void pythonControlFlow() {
        String source = "for i, x in enumerate(xs):\n    if x is not None:\n        continue\n    elif x in y:\n        break\n"
                + "else:\n    pass\n"
                + "try:\n    f()\nexcept (A, B) as e:\n    raise C from e\nfinally:\n    g()\n"
                + "with open(p) as f, lock:\n    pass\n"
                + "match cmd:\n    case [x, y]:\n        pass\n    case _:\n        pass\n";
        SyntaxTree tree = python(source);
        assertFalse(tree.hasErrors(), tree.errors()::toString);
        Node root = tree.root();
        assertEquals(1, root.findAll(NodeKind.LOOP).size());
        assertEquals(2, root.findAll(NodeKind.IF).size()); // if + elif
        assertEquals("e", root.findAll(NodeKind.CATCH).get(0).text());
        assertEquals(1, root.findAll(NodeKind.FINALLY).size());
        assertEquals(1, root.findAll(NodeKind.WITH).size());
        assertEquals(2, root.findAll(NodeKind.CASE).size());
    }

    @Test
    void pythonImportsAndComprehensions() {
        SyntaxTree tree = python("import os.path, numpy as np\nfrom ..pkg import (a as b, c)\n"
                + "y = {k: v for k, v in d.items() if v}\nz = [i async for i in g()]\nw = x[1:2, ::3]\n");
        assertFalse(tree.hasErrors(), tree.errors()::toString);
        List<Node> imports = tree.root().findAll(NodeKind.IMPORT);
        assertEquals("os np", imports.get(0).children().stream().map(Node::text).collect(Collectors.joining(" ")));
        assertEquals("..pkg", imports.get(1).text());
        assertEquals("b c", imports.get(1).children().stream().map(Node::text).collect(Collectors.joining(" ")));
        assertEquals(2, tree.root().findAll(NodeKind.COMPREHENSION).size());
    }

    @Test
    void pythonWalrusLambdaTernaryAndFStrings() {
        assertFalse(python("if (n := len(a)) > 10:\n    f = lambda x, *y: x if x else y\n"
                + "s = f\"{a!r:>{w}} {b['k']}\"\n").hasErrors());
    }

    @Test
    void pythonSyntaxErrorsAreReportedAndParsingContinues() {
        // (괄호가 닫히지 않으면 CPython처럼 파일 끝까지 한 논리적 줄이 되므로, 닫힌 괄호 안의 오류로 시험한다)
        SyntaxTree tree = python("def f(a b):\n    pass\nx = = 1\nprint 'hi'\ny = 2\n");
        assertEquals(3, tree.errors().size(), tree.errors()::toString);
        assertEquals(1, tree.errors().get(0).line());
        // 오류 뒤의 정상 문장도 트리에 남는다
        assertTrue(tree.root().toSExpression().contains("(ASSIGN = (NAME y) (LITERAL 2))"));
    }

    // ------------------------------------------------------------ Java

    @Test
    void javaClassMembersAndGenerics() {
        SyntaxTree tree = java("package p;\nimport java.util.*;\nimport java.util.List;\n"
                + "public class A<T extends Comparable<T>> extends B implements C {\n"
                + "  private Map<String, List<Integer>> m = new HashMap<>();\n"
                + "  public A(int x) { super(x); }\n"
                + "  @Override public <R> R f(List<? extends T> xs, String... rest) throws IOException { return null; }\n"
                + "  enum E { X(1) { void g() {} }, Y; E() {} E(int v) {} }\n"
                + "  record P(int x, int y) { P { assert x > 0; } }\n"
                + "}\n");
        assertFalse(tree.hasErrors(), tree.errors()::toString);
        Node root = tree.root();
        assertEquals("A,E,X,P", root.findAll(NodeKind.CLASS).stream().map(Node::text).collect(Collectors.joining(",")));
        assertEquals("A,f,g,E,E,P", root.findAll(NodeKind.FUNCTION).stream().map(Node::text).collect(Collectors.joining(",")));
        Node field = root.findAll(NodeKind.VARIABLE).stream().filter(n -> n.text().equals("m")).findFirst().get();
        assertEquals("Map<String,List<Integer>>", field.firstChild().text());
        assertEquals("Override", root.findAll(NodeKind.DECORATOR).get(0).text());
    }

    @Test
    void javaExpressions() {
        String tree = sexpr(java("class A { int f(int x) { return x > 0 ? (int) Math.abs(x) : y = 2; } }"));
        assertTrue(tree.contains("(TERNARY (BINARY > (NAME x) (LITERAL 0)) (CAST int (CALL (ATTRIBUTE abs (NAME Math)) (NAME x))) (NAME y))"), tree);
        String shifts = sexpr(java("class A { void f() { a = b >> 2 >>> c; List<List<String>> l = x; } }"));
        assertTrue(shifts.contains("(BINARY >>> (BINARY >> (NAME b) (LITERAL 2)) (NAME c))"), shifts);
        assertTrue(shifts.contains("(VARIABLE l (TYPE List<List<String>>) (NAME x))"), shifts);
    }

    @Test
    void javaStatementsLambdasAndSwitchExpressions() {
        SyntaxTree tree = java("class A { Object g(Object o) {\n"
                + "  for (int i = 0, j = 1; i < n; i++, j--) { if (o instanceof String s && !s.isEmpty()) continue; }\n"
                + "  for (var e : list) { list.forEach(x -> System.out.println(x)); }\n"
                + "  try (var in = open(); Reader r = in) { r.read(); } catch (IOException | RuntimeException e) { throw e; }\n"
                + "  Runnable r = () -> { Thread.yield(); };\n"
                + "  Comparator<String> c = String::compareTo; int[] arr = new int[]{1, 2}; String[][] grid = new String[3][];\n"
                + "  return switch (o) { case String s when s.isEmpty() -> 1; case Integer i -> { yield i; } default -> 0; };\n"
                + "} }");
        assertFalse(tree.hasErrors(), tree.errors()::toString);
        Node root = tree.root();
        assertEquals(2, root.findAll(NodeKind.LOOP).size());
        assertEquals(2, root.findAll(NodeKind.LAMBDA).size());
        assertEquals("IOException|RuntimeException", root.findAll(NodeKind.CATCH).get(0).firstChild().text());
        assertEquals(3, root.findAll(NodeKind.CASE).size());
    }

    @Test
    void javaAnonymousClassAndInnerCreation() {
        SyntaxTree tree = java("class A { void f() { Runnable r = new Runnable() { public void run() { } }; "
                + "Inner i = outer.new Inner(); int k = Collections.<String>emptyList().size(); } }");
        assertFalse(tree.hasErrors(), tree.errors()::toString);
        assertEquals(1, tree.root().findAll(NodeKind.NEW).stream().filter(n -> n.childOfKind(NodeKind.CLASS) != null).count());
    }

    @Test
    void javaSyntaxErrorsAreReportedAndParsingContinues() {
        SyntaxTree tree = java("class A { void f() { int = 5; if (x { } } int ok; void g() { } }");
        assertTrue(tree.hasErrors());
        assertTrue(tree.root().findAll(NodeKind.FUNCTION).stream().anyMatch(n -> n.text().equals("g")));
    }

    @Test
    void qualifiedNames() {
        Node call = java("class A { void f() { Runtime.getRuntime().exec(cmd); } }").root().findAll(NodeKind.CALL).get(0);
        assertEquals("Runtime.getRuntime().exec", call.firstChild().qualifiedName());
    }

    @Test
    void decafIsNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> CodeAnalyzer.parse("void main() {}", Language.DECAF));
    }

    @Test
    void deeplyNestedInputDoesNotOverflowTheStack() {
        StringBuilder source = new StringBuilder("x = ");
        for (int i = 0; i < 5000; i++) {
            source.append('(');
        }
        SyntaxTree tree = python(source.toString());
        assertTrue(tree.hasErrors());
    }
}
