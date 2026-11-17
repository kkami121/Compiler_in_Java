package io.github.kkami121.codeanalyzer.lexer;

import org.junit.jupiter.api.Test;

import static io.github.kkami121.codeanalyzer.lexer.LexerTestSupport.describe;
import static io.github.kkami121.codeanalyzer.lexer.LexerTestSupport.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLexerTest {

    private final JavaLexer lexer = new JavaLexer();

    @Test
    void numbers() {
        LexResult result = lexer.tokenize("0x1F_FF 1_000L .5e-3f 1.e5 0b1010 017 3.14 1e10 2f 7d 0x1.8p3 1.");
        assertEquals(list(
                "INTEGER_LITERAL 0x1F_FF", "INTEGER_LITERAL 1_000L", "FLOAT_LITERAL .5e-3f", "FLOAT_LITERAL 1.e5",
                "INTEGER_LITERAL 0b1010", "INTEGER_LITERAL 017", "FLOAT_LITERAL 3.14", "FLOAT_LITERAL 1e10",
                "FLOAT_LITERAL 2f", "FLOAT_LITERAL 7d", "FLOAT_LITERAL 0x1.8p3", "FLOAT_LITERAL 1."),
                describe(result));
        assertFalse(result.hasErrors());
    }

    @Test
    void operatorsUseLongestMatch() {
        assertEquals(list(
                "IDENTIFIER a", "OPERATOR >>>=", "IDENTIFIER b", "OPERATOR ;",
                "IDENTIFIER x", "OPERATOR ->", "IDENTIFIER y",
                "IDENTIFIER String", "OPERATOR ::", "IDENTIFIER valueOf",
                "OPERATOR @", "IDENTIFIER Override",
                "IDENTIFIER i", "OPERATOR ++", "OPERATOR ...", "OPERATOR !="),
                describe(lexer.tokenize("a >>>= b; x -> y String::valueOf @Override i++ ... !=")));
    }

    @Test
    void keywordsLiteralsAndContextualKeywords() {
        assertEquals(list(
                "KEYWORD public", "KEYWORD class", "IDENTIFIER var", "IDENTIFIER record", "IDENTIFIER yield",
                "BOOLEAN_LITERAL true", "BOOLEAN_LITERAL false", "NULL_LITERAL null", "IDENTIFIER $dollar_1"),
                describe(lexer.tokenize("public class var record yield true false null $dollar_1")));
    }

    @Test
    void stringsCharsAndTextBlocks() {
        String source = "\"a\\\"b\" '\\n' '\\'' \"\"\"\n  hi \"quoted\"\n  \"\"\" \"\"";
        LexResult result = lexer.tokenize(source);
        assertEquals(list(
                "STRING_LITERAL \"a\\\"b\"", "CHAR_LITERAL '\\n'", "CHAR_LITERAL '\\''",
                "STRING_LITERAL \"\"\"\n  hi \"quoted\"\n  \"\"\"", "STRING_LITERAL \"\""),
                describe(result));
        Token textBlock = result.tokens().get(3);
        assertEquals(1, textBlock.line());
        assertEquals(3, textBlock.endLine());
    }

    @Test
    void comments() {
        LexResult result = lexer.tokenize("int a; // line\n/** doc\n */ int b;");
        assertEquals(2, result.comments().size());
        assertEquals("// line", result.comments().get(0).text());
        assertEquals(6, result.codeTokens().size());
    }

    @Test
    void positions() {
        Token x = lexer.tokenize("int\n  xyz;").tokens().get(1);
        assertEquals(2, x.line());
        assertEquals(3, x.column());
        assertEquals(5, x.endColumn());
    }

    @Test
    void genericsProduceShiftOperatorAtLexerLevel() {
        // 렉서는 >> 를 하나로 읽는다. 제네릭 해석은 파서의 일이다.
        assertTrue(describe(lexer.tokenize("List<List<String>> x;")).contains("OPERATOR >>"));
    }

    @Test
    void errorsAreReportedAndLexingContinues() {
        LexResult result = lexer.tokenize("String s = \"abc\nint # x; /* open");
        assertEquals(3, result.errors().size());
        assertEquals("Unterminated string literal", result.errors().get(0).message());
        assertEquals("Unrecognized character: '#'", result.errors().get(1).message());
        assertEquals("Unterminated comment", result.errors().get(2).message());
        assertTrue(describe(result).contains("KEYWORD int"));
    }

    @Test
    void backslashAtEndOfInputDoesNotCrash() {
        assertTrue(lexer.tokenize("\"abc\\").hasErrors());
        assertTrue(lexer.tokenize("'\\").hasErrors());
        assertTrue(lexer.tokenize("\"\"\"\n\\").hasErrors());
    }

    @Test
    void emptySource() {
        assertTrue(lexer.tokenize("").tokens().isEmpty());
    }
}
