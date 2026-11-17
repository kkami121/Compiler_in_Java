package io.github.kkami121.codeanalyzer.lexer;

import io.github.kkami121.codeanalyzer.CodeAnalyzer;
import io.github.kkami121.codeanalyzer.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** src/test/resources/decaf 의 과제 샘플을 기대 출력(.out)과 비교한다. */
class DecafLexerTest {

    static Stream<Path> samples() throws IOException, URISyntaxException {
        Path dir = Paths.get(DecafLexerTest.class.getResource("/decaf").toURI());
        List<Path> inputs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".frag") || p.toString().endsWith(".decaf"))
                    .sorted()
                    .forEach(inputs::add);
        }
        return inputs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void matchesExpectedOutput(Path input) throws IOException {
        String name = input.getFileName().toString();
        Path expected = input.resolveSibling(name.substring(0, name.lastIndexOf('.')) + ".out");
        String source = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);

        String actual = DecafFormatter.format(CodeAnalyzer.tokenize(source, Language.DECAF));

        assertEquals(normalize(new String(Files.readAllBytes(expected), StandardCharsets.UTF_8)), normalize(actual));
    }

    @Test
    void samplesExist() throws Exception {
        assertEquals(16, samples().count());
    }

    @Test
    void endOfInputInsideOperatorDoesNotCrash() {
        LexResult result = CodeAnalyzer.tokenize("a =", Language.DECAF);
        assertEquals(2, result.codeTokens().size());
        assertTrue(CodeAnalyzer.tokenize("x /", Language.DECAF).errors().isEmpty());
    }

    @Test
    void unterminatedBlockCommentIsReported() {
        LexResult result = CodeAnalyzer.tokenize("int a; /* never closed", Language.DECAF);
        assertEquals(1, result.errors().size());
        assertEquals("Input ends with unterminated comment", result.errors().get(0).message());
    }

    /** 줄 끝 공백과 CR, 빈 줄 차이는 무시한다 (diff -Z -B 와 같음). */
    private static String normalize(String text) {
        return Stream.of(text.split("\n"))
                .map(line -> line.replaceAll("\\s+$", ""))
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }
}
