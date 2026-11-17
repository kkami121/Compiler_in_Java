package io.github.kkami121.codeanalyzer.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.kkami121.codeanalyzer.lexer.LexerTestSupport.describe;
import static io.github.kkami121.codeanalyzer.lexer.LexerTestSupport.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonLexerTest {

    private final PythonLexer lexer = new PythonLexer();

    @Test
    void indentationProducesIndentAndDedent() {
        String source = "def f(x):\n    if x:\n        return 1\n    return 2\nprint(f(1))\n";
        assertEquals(list(
                "KEYWORD def", "IDENTIFIER f", "OPERATOR (", "IDENTIFIER x", "OPERATOR )", "OPERATOR :", "NEWLINE",
                "INDENT", "KEYWORD if", "IDENTIFIER x", "OPERATOR :", "NEWLINE",
                "INDENT", "KEYWORD return", "INTEGER_LITERAL 1", "NEWLINE",
                "DEDENT", "KEYWORD return", "INTEGER_LITERAL 2", "NEWLINE",
                "DEDENT", "IDENTIFIER print", "OPERATOR (", "IDENTIFIER f", "OPERATOR (", "INTEGER_LITERAL 1",
                "OPERATOR )", "OPERATOR )", "NEWLINE"),
                describe(lexer.tokenize(source)));
    }

    @Test
    void crlfLineEndingsAndMissingFinalNewline() {
        assertEquals(list("KEYWORD if", "IDENTIFIER a", "OPERATOR :", "NEWLINE", "INDENT", "IDENTIFIER b",
                "NEWLINE", "DEDENT"),
                describe(lexer.tokenize("if a:\r\n    b")));
    }

    @Test
    void newlinesInsideBracketsAndBackslashContinuationJoinLines() {
        assertEquals(list("IDENTIFIER x", "OPERATOR =", "OPERATOR [", "INTEGER_LITERAL 1", "OPERATOR ,",
                "INTEGER_LITERAL 2", "OPERATOR ]", "NEWLINE",
                "IDENTIFIER y", "OPERATOR =", "INTEGER_LITERAL 1", "OPERATOR +", "INTEGER_LITERAL 2", "NEWLINE"),
                describe(lexer.tokenize("x = [1,\n        2]\ny = 1 + \\\n      2\n")));
    }

    @Test
    void blankAndCommentLinesDoNotChangeIndentation() {
        assertEquals(list("KEYWORD if", "IDENTIFIER a", "OPERATOR :", "NEWLINE", "INDENT", "IDENTIFIER b",
                "NEWLINE", "COMMENT # c", "IDENTIFIER d", "NEWLINE", "DEDENT"),
                describe(lexer.tokenize("if a:\n    b\n\n# c\n    d\n")));
    }

    @Test
    void strings() {
        String source = "r'\\d' b\"x\" F\"{a['k']}\" f\"{x!r:>{w}}\" '''multi\nline''' rb'y' \"it's\" f\"{{literal}}\"";
        List<String> tokens = describe(lexer.tokenize(source));
        assertEquals(list(
                "STRING_LITERAL r'\\d'", "STRING_LITERAL b\"x\"", "STRING_LITERAL F\"{a['k']}\"",
                "STRING_LITERAL f\"{x!r:>{w}}\"", "STRING_LITERAL '''multi\nline'''", "STRING_LITERAL rb'y'",
                "STRING_LITERAL \"it's\"", "STRING_LITERAL f\"{{literal}}\"", "NEWLINE"),
                tokens);
    }

    @Test
    void pep701FStrings() {
        // raw f-string의 \{{ : 백슬래시는 { 를 이스케이프하지 않는다
        assertSingleString("rf\"\\s*\\{{?\"");
        // 한 줄 따옴표 f-string이라도 {} 안에서는 줄바꿈 가능
        assertSingleString("f\"{test(\n    1)}\"");
        // 여러 줄 f-string의 {} 안 주석 (주석 안의 따옴표는 무시)
        assertSingleString("f'''{ # it's a comment\n x}'''");
        // 서식 지정 안의 # 와 중첩 필드, := 는 서식 지정
        assertSingleString("f\"{x:#x} {y:>{w}} {z:=10}\"");
        // 같은 따옴표 중첩 (3.12)
        assertSingleString("f\"{\", \".join(items)}\"");
    }

    private void assertSingleString(String source) {
        LexResult result = lexer.tokenize(source);
        assertFalse(result.hasErrors(), () -> source + " -> " + result.errors());
        assertEquals(list("STRING_LITERAL " + source, "NEWLINE"), describe(result));
    }

    @Test
    void supplementaryIdentifiersAndByteOrderMark() {
        assertEquals(list("IDENTIFIER 𝔘𝔫𝔦", "OPERATOR =", "INTEGER_LITERAL 1", "NEWLINE"),
                describe(lexer.tokenize("﻿𝔘𝔫𝔦 = 1")));
        assertEquals(1, lexer.tokenize("﻿x").tokens().get(0).column());
        assertEquals(list("IDENTIFIER x󠄀", "NEWLINE"), describe(lexer.tokenize("x󠄀")));
    }

    @Test
    void numbers() {
        assertEquals(list("INTEGER_LITERAL 1_000", "INTEGER_LITERAL 0x_ff", "INTEGER_LITERAL 0o17",
                "INTEGER_LITERAL 0b1", "FLOAT_LITERAL 3.14", "FLOAT_LITERAL 1e10", "FLOAT_LITERAL 1.",
                "FLOAT_LITERAL .5", "FLOAT_LITERAL 3j", "FLOAT_LITERAL 1.5J", "FLOAT_LITERAL 1E-3", "NEWLINE"),
                describe(lexer.tokenize("1_000 0x_ff 0o17 0b1 3.14 1e10 1. .5 3j 1.5J 1E-3")));
    }

    @Test
    void operatorsKeywordsAndLiterals() {
        assertEquals(list("IDENTIFIER a", "OPERATOR **=", "INTEGER_LITERAL 2", "OPERATOR ;",
                "OPERATOR (", "IDENTIFIER c", "OPERATOR :=", "INTEGER_LITERAL 4", "OPERATOR )",
                "OPERATOR ->", "OPERATOR ...", "OPERATOR @", "OPERATOR //=",
                "BOOLEAN_LITERAL True", "BOOLEAN_LITERAL False", "NULL_LITERAL None",
                "KEYWORD lambda", "KEYWORD async", "KEYWORD await", "IDENTIFIER match", "IDENTIFIER 변수", "NEWLINE"),
                describe(lexer.tokenize("a **= 2; (c := 4) -> ... @ //= True False None lambda async await match 변수")));
    }

    @Test
    void errors() {
        LexResult unterminated = lexer.tokenize("s = 'abc\nx = 1\n");
        assertEquals("Unterminated string literal", unterminated.errors().get(0).message());
        assertTrue(describe(unterminated).contains("IDENTIFIER x"));

        LexResult badDedent = lexer.tokenize("if a:\n        b\n    c\n");
        assertEquals("Unindent does not match any outer indentation level", badDedent.errors().get(0).message());

        assertEquals("Unrecognized character: '$'", lexer.tokenize("$").errors().get(0).message());
        assertTrue(lexer.tokenize("'''never closed").hasErrors());
    }

    @Test
    void validCodeHasNoErrors() {
        String source = "class A:\n    \"\"\"doc\"\"\"\n\n    def m(self, *args, **kw) -> int:\n"
                + "        return sum(x ** 2 for x in args if x)  # comment\n";
        assertFalse(lexer.tokenize(source).hasErrors());
    }
}
