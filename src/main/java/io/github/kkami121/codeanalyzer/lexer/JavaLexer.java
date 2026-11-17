package io.github.kkami121.codeanalyzer.lexer;

import io.github.kkami121.codeanalyzer.Language;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Java 어휘분석기 (Java 21까지의 문법).
 * {@code var}, {@code record}, {@code yield}, {@code sealed} 같은 문맥 키워드는 식별자로 분류한다.
 * 소스 안의 유니코드 이스케이프({@code A})는 변환하지 않는다.
 */
public final class JavaLexer implements Lexer {

    static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while")));

    private static final String[] OPERATORS = {
            ">>>=",
            "<<=", ">>=", ">>>", "...",
            "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "<<", ">>",
            "=", ">", "<", "!", "~", "?", ":", "+", "-", "*", "/", "&", "|", "^", "%",
            "(", ")", "{", "}", "[", "]", ";", ",", ".", "@"
    };

    @Override
    public LexResult tokenize(String source) {
        return new Scanner(source).run();
    }

    private static final class Scanner extends SourceScanner {

        Scanner(String source) {
            super(source);
        }

        LexResult run() {
            while (!atEnd()) {
                char c = peek();
                if (isInlineWhitespace(c) || c == '\n') {
                    advance();
                } else if (scanCStyleComment("Unterminated comment")) {
                    // 주석
                } else if (Character.isJavaIdentifierStart(peekCodePoint())) {
                    word();
                } else if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
                    number();
                } else if (c == '"') {
                    string();
                } else if (c == '\'') {
                    character();
                } else if (!scanOperator(OPERATORS)) {
                    error(line, column, "Unrecognized character: '" + new String(Character.toChars(peekCodePoint())) + "'");
                    advanceCodePoint();
                }
            }
            return result(Language.JAVA);
        }

        private void word() {
            mark();
            while (!atEnd() && Character.isJavaIdentifierPart(peekCodePoint())) {
                advanceCodePoint();
            }
            String text = markedText();
            if (text.equals("true") || text.equals("false")) {
                emit(TokenKind.BOOLEAN_LITERAL);
            } else if (text.equals("null")) {
                emit(TokenKind.NULL_LITERAL);
            } else if (KEYWORDS.contains(text)) {
                emit(TokenKind.KEYWORD);
            } else {
                emit(TokenKind.IDENTIFIER);
            }
        }

        /** 10/16/8/2진 정수, 실수, 16진 실수(0x1.8p3), 밑줄(1_000), 접미사(L, f, d)를 처리한다. */
        private void number() {
            mark();
            boolean isFloat = false;
            if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
                advance(2);
                skipDigits(true);
                if (peek() == '.') {
                    advance();
                    skipDigits(true);
                    isFloat = true;
                }
                if (peek() == 'p' || peek() == 'P') {
                    exponent();
                    isFloat = true;
                }
            } else if (peek() == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
                advance(2);
                skipDigits(false);
            } else {
                skipDigits(false);
                if (peek() == '.' && startsFraction(peek(1))) {
                    advance();
                    skipDigits(false);
                    isFloat = true;
                }
                if (peek() == 'e' || peek() == 'E') {
                    exponent();
                    isFloat = true;
                }
            }
            char suffix = peek();
            if (suffix == 'f' || suffix == 'F' || suffix == 'd' || suffix == 'D') {
                advance();
                isFloat = true;
            } else if (suffix == 'l' || suffix == 'L') {
                advance();
            }
            emit(isFloat ? TokenKind.FLOAT_LITERAL : TokenKind.INTEGER_LITERAL);
        }

        /** {@code 1.}, {@code 1.5}, {@code 1.e5}, {@code 1.f} 처럼 '.' 뒤가 실수의 일부인지. */
        private static boolean startsFraction(char next) {
            return isDigit(next) || "eEfFdD".indexOf(next) >= 0
                    || (!Character.isJavaIdentifierStart(next) && next != '.');
        }

        private void skipDigits(boolean hex) {
            while (hex ? isHexDigit(peek()) || peek() == '_' : isDigit(peek()) || peek() == '_') {
                advance();
            }
        }

        private void exponent() {
            advance(); // e, E, p, P
            if (peek() == '+' || peek() == '-') {
                advance();
            }
            skipDigits(false);
        }

        private void string() {
            mark();
            if (lookingAt("\"\"\"")) {
                textBlock();
                return;
            }
            advance();
            while (!atEnd() && peek() != '"') {
                if (peek() == '\n' || peek() == '\r') {
                    errorAtMark("Unterminated string literal");
                    return;
                }
                if (peek() == '\\') {
                    advance(2); // 이스케이프 문자까지 (파일 끝이면 거기서 멈춤)
                } else {
                    advance();
                }
            }
            if (atEnd()) {
                errorAtMark("Unterminated string literal");
                return;
            }
            advance();
            emit(TokenKind.STRING_LITERAL);
        }

        private void textBlock() {
            advance(3);
            while (!atEnd() && !lookingAt("\"\"\"")) {
                if (peek() == '\\') {
                    advance(2); // 이스케이프 문자까지 (파일 끝이면 거기서 멈춤)
                } else {
                    advance();
                }
            }
            if (atEnd()) {
                errorAtMark("Unterminated text block");
                emit(TokenKind.STRING_LITERAL);
                return;
            }
            advance(3);
            emit(TokenKind.STRING_LITERAL);
        }

        private void character() {
            mark();
            advance();
            while (!atEnd() && peek() != '\'' && peek() != '\n' && peek() != '\r') {
                if (peek() == '\\') {
                    advance(2); // 이스케이프 문자까지 (파일 끝이면 거기서 멈춤)
                } else {
                    advance();
                }
            }
            if (peek() != '\'') {
                errorAtMark("Unterminated character literal");
                return;
            }
            advance();
            emit(TokenKind.CHAR_LITERAL);
        }
    }
}
