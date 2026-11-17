package io.github.kkami121.codeanalyzer.clone;

import io.github.kkami121.codeanalyzer.CodeAnalyzer;
import io.github.kkami121.codeanalyzer.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloneDetectorTest {

    private static final String SUM = String.join("\n",
            "class A {",
            "    // sums positive values",
            "    static int sum(int[] values) {",
            "        int total = 0;",
            "        for (int i = 0; i < values.length; i++) {",
            "            if (values[i] > 0) {",
            "                total += values[i];",
            "            }",
            "        }",
            "        return total;",
            "    }",
            "}");

    /** 이름과 상수, 주석, 줄바꿈만 바꾼 복사본. */
    private static final String SUM_RENAMED = String.join("\n",
            "class B {",
            "    static int add(int[] xs) { int acc = 1;",
            "        for (int k = 0; k < xs.length; k++) {",
            "            if (xs[k] > 5) { acc += xs[k]; }",
            "        }",
            "        return acc;",
            "    }",
            "}");

    private static final String UNRELATED = String.join("\n",
            "class C {",
            "    String greet(String name) {",
            "        StringBuilder sb = new StringBuilder();",
            "        sb.append(\"Hello, \").append(name).append('!');",
            "        return sb.toString();",
            "    }",
            "}");

    @Test
    void findsRenamedCopyAsType2() {
        List<ClonePair> clones = CodeAnalyzer.findClones(Arrays.asList(
                new SourceFile("A.java", SUM), new SourceFile("B.java", SUM_RENAMED)), 20);

        assertEquals(1, clones.size());
        ClonePair pair = clones.get(0);
        assertEquals(CloneType.TYPE_2, pair.type());
        assertEquals("A.java", pair.first().sourceName());
        assertEquals("B.java", pair.second().sourceName());
        assertTrue(pair.tokenCount() >= 50, "tokens: " + pair.tokenCount());
        // 클래스 선언부터 끝까지 구조가 같다 (class ID { static int ID(...) ... })
        assertEquals(1, pair.first().startLine());
        assertEquals(12, pair.first().endLine());
        assertEquals(1, pair.second().startLine());
        assertEquals(8, pair.second().endLine());
    }

    @Test
    void identicalCopyIsType1() {
        String reformatted = SUM.replace("    ", "\t").replace("// sums positive values", "/* other comment */");
        List<ClonePair> clones = CodeAnalyzer.findClones(Arrays.asList(
                new SourceFile("A.java", SUM), new SourceFile("A2.java", reformatted)));
        assertEquals(1, clones.size());
        assertEquals(CloneType.TYPE_1, clones.get(0).type());
    }

    @Test
    void unrelatedCodeHasNoClones() {
        assertTrue(CodeAnalyzer.findClones(Arrays.asList(
                new SourceFile("A.java", SUM), new SourceFile("C.java", UNRELATED)), 20).isEmpty());
    }

    @Test
    void findsDuplicateInsideOneFileWithoutOverlap() {
        String block = "    total = 0\n    for v in values:\n        if v > 0:\n            total += v * 2\n"
                + "    print(total)\n";
        String source = "def a(values):\n" + block + "\ndef b(values):\n" + block;
        List<ClonePair> clones = CodeAnalyzer.findClones(
                Collections.singletonList(new SourceFile("dup.py", source)), 15);
        assertEquals(1, clones.size());
        ClonePair pair = clones.get(0);
        assertTrue(pair.first().endLine() < pair.second().startLine());
    }

    @Test
    void similarityScores() {
        SourceFile a = new SourceFile("A.java", SUM);
        assertEquals(1.0, CodeAnalyzer.similarity(a, new SourceFile("A2.java", SUM)), 1e-9);
        double renamed = CodeAnalyzer.similarity(a, new SourceFile("B.java", SUM_RENAMED));
        double unrelated = CodeAnalyzer.similarity(a, new SourceFile("C.java", UNRELATED));
        assertTrue(renamed > 0.8, "renamed: " + renamed);
        assertTrue(unrelated < 0.3, "unrelated: " + unrelated);
    }

    @Test
    void similarityOfEmptySources() {
        assertEquals(1.0, CodeAnalyzer.similarity(new SourceFile("a.py", ""), new SourceFile("b.py", "")), 1e-9);
        assertEquals(0.0, CodeAnalyzer.similarity(new SourceFile("a.py", ""), new SourceFile("b.py", "x = 1")), 1e-9);
    }

    @Test
    void rejectsTooSmallMinimum() {
        assertThrows(IllegalArgumentException.class, () -> new CloneDetector(1));
    }
}
