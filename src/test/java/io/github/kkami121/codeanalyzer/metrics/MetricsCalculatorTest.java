package io.github.kkami121.codeanalyzer.metrics;

import io.github.kkami121.codeanalyzer.CodeAnalyzer;
import io.github.kkami121.codeanalyzer.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCalculatorTest {

    private static final String JAVA = String.join("\n",
            "import java.util.List;",
            "",
            "/** A class. */",
            "class A {",
            "    int f(int x) {",
            "        if (x > 0 && x < 10) {",
            "            for (int i = 0; i < x; i++) {",
            "                if (i % 2 == 0) { x--; }",
            "            }",
            "        }",
            "        int[] arr = {1, 2};",
            "        return x > 5 ? 1 : 0;",
            "    }",
            "    A() { }",
            "    void g(List<?> list) throws java.io.IOException, RuntimeException {",
            "        switch (list.size()) { case 1: break; case 2: break; default: break; }",
            "        try { } catch (Exception e) { }",
            "        Runnable r = new Runnable() {",
            "            public void run() { if (true) { } }",
            "        };",
            "    }",
            "}",
            "");

    @Test
    void javaFunctionsComplexityAndNesting() {
        CodeMetrics metrics = CodeAnalyzer.measure(JAVA, Language.JAVA);
        List<FunctionMetrics> functions = metrics.functions();

        assertEquals(names("f", "A", "g", "run"), functions.stream().map(FunctionMetrics::name).collect(Collectors.toList()));

        FunctionMetrics f = functions.get(0);
        assertEquals(6, f.cyclomaticComplexity());   // if, &&, for, if, ?:
        assertEquals(3, f.maxNestingDepth());        // if > for > if (배열 초기화는 제외)
        assertEquals(5, f.startLine());
        assertEquals(13, f.endLine());

        assertEquals(1, functions.get(1).cyclomaticComplexity());

        FunctionMetrics g = functions.get(2);
        assertEquals(4, g.cyclomaticComplexity());   // case, case, catch (List<?>는 제외)
        assertEquals(1, g.maxNestingDepth());

        FunctionMetrics run = functions.get(3);       // 익명 클래스의 메서드는 따로 센다
        assertEquals(2, run.cyclomaticComplexity());

        assertEquals(6, metrics.maxCyclomaticComplexity());
        assertEquals(3, metrics.maxNestingDepth());
        assertEquals(DifficultyLevel.MODERATE, metrics.difficulty());
        assertEquals(3, metrics.difficultyReasons().size());
    }

    @Test
    void lineCounts() {
        LineMetrics lines = CodeAnalyzer.measure(JAVA, Language.JAVA).lines();
        assertEquals(22, lines.total());
        assertEquals(1, lines.comment());
        assertEquals(1, lines.blank());
        assertEquals(20, lines.code());
    }

    @Test
    void pythonFunctionsComplexityAndNesting() {
        String source = String.join("\n",
                "import os",
                "",
                "def outer(items):",
                "    total = 0",
                "    for item in items:",
                "        if item > 0 and item < 100:",
                "            total += item",
                "        elif item == -1:",
                "            break",
                "    def inner(): return 1 if items else 0",
                "    return total",
                "",
                "class K:",
                "    def method(self):",
                "        try:",
                "            pass",
                "        except ValueError:",
                "            pass",
                "",
                "x = 1 if os.name else 2",
                "");
        CodeMetrics metrics = CodeAnalyzer.measure(source, Language.PYTHON);
        List<FunctionMetrics> functions = metrics.functions();

        assertEquals(names("outer", "inner", "method"),
                functions.stream().map(FunctionMetrics::name).collect(Collectors.toList()));
        assertEquals(5, functions.get(0).cyclomaticComplexity());   // for, if, and, elif
        assertEquals(2, functions.get(0).maxNestingDepth());
        assertEquals(3, functions.get(0).startLine());
        assertEquals(11, functions.get(0).endLine());
        assertEquals(2, functions.get(1).cyclomaticComplexity());   // 한 줄 함수의 if
        assertEquals(10, functions.get(1).startLine());
        assertEquals(2, functions.get(2).cyclomaticComplexity());   // except
        assertEquals(1, functions.get(2).maxNestingDepth());
        assertEquals(2, metrics.topLevelComplexity());              // 모듈 수준 삼항 if
        // 복잡도와 중첩은 EASY, Halstead 난이도(약 17)가 MODERATE라 전체는 MODERATE
        assertTrue(metrics.difficultyReasons().get(0).endsWith("EASY"));
        assertTrue(metrics.difficultyReasons().get(1).endsWith("EASY"));
        assertTrue(metrics.difficultyReasons().get(2).endsWith("MODERATE"));
        assertEquals(DifficultyLevel.MODERATE, metrics.difficulty());
    }

    @Test
    void halsteadForSimpleExpression() {
        Halstead h = CodeAnalyzer.measure("x = a + a\n", Language.PYTHON).halstead();
        assertEquals(2, h.distinctOperators());   // = +
        assertEquals(2, h.distinctOperands());    // x a
        assertEquals(2, h.totalOperators());
        assertEquals(3, h.totalOperands());
        assertEquals(10.0, h.volume(), 1e-9);     // 5 * log2(4)
        assertEquals(1.5, h.difficulty(), 1e-9);  // (2/2) * (3/2)
        assertEquals(15.0, h.effort(), 1e-9);
    }

    @Test
    void manyBranchesAreHard() {
        StringBuilder body = new StringBuilder("class B { int f(int x) {\n");
        for (int i = 0; i < 25; i++) {
            body.append("  if (x == ").append(i).append(") return ").append(i).append(";\n");
        }
        body.append("  return -1; } }\n");
        CodeMetrics metrics = CodeAnalyzer.measure(body.toString(), Language.JAVA);
        assertEquals(26, metrics.maxCyclomaticComplexity());
        assertEquals(DifficultyLevel.HARD, metrics.difficulty());
        assertTrue(metrics.difficultyReasons().get(0).contains("function 'f'"));
    }

    @Test
    void deepNestingIsVeryHard() {
        StringBuilder python = new StringBuilder("def deep(x):\n");
        String indent = "    ";
        for (int i = 0; i < 8; i++) {
            python.append(indent).append("if x > ").append(i).append(":\n");
            indent += "    ";
        }
        python.append(indent).append("return x\n");
        CodeMetrics metrics = CodeAnalyzer.measure(python.toString(), Language.PYTHON);
        assertEquals(8, metrics.maxNestingDepth());
        assertEquals(DifficultyLevel.VERY_HARD, metrics.difficulty());
    }

    @Test
    void decafProgram() {
        String source = "class Animal { double weight; void Eat() { weight = weight * 2.0; } }\n"
                + "void main() { if (true && false) { Print(\"x\"); } }\n";
        CodeMetrics metrics = CodeAnalyzer.measure(source, Language.DECAF);
        assertEquals(names("Eat", "main"),
                metrics.functions().stream().map(FunctionMetrics::name).collect(Collectors.toList()));
        assertEquals(3, metrics.functions().get(1).cyclomaticComplexity());
    }

    @Test
    void emptySource() {
        CodeMetrics metrics = CodeAnalyzer.measure("", Language.JAVA);
        assertEquals(0, metrics.lines().total());
        assertEquals(0, metrics.tokenCount());
        assertEquals(DifficultyLevel.EASY, metrics.difficulty());
    }

    private static List<String> names(String... names) {
        return java.util.Arrays.asList(names);
    }
}
