package io.github.kkami121.codeanalyzer.lexer;

import io.github.kkami121.codeanalyzer.Language;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decaf 어휘분석기. 오류 메시지와 규칙은 Decaf 과제 명세를 따른다.
 * 과제 형식의 출력이 필요하면 {@link DecafFormatter}를 쓴다.
 */
public final class DecafLexer implements Lexer {

    /** 식별자 최대 길이. 넘으면 오류를 내지만 토큰은 그대로 만든다. */
    public static final int MAX_IDENTIFIER_LENGTH = 31;

    static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "void", "int", "double", "bool", "string", "class", "interface", "this", "extends",
            "implements", "for", "while", "if", "else", "return", "break", "new", "NewArray",
            "Print", "ReadInteger", "ReadLine")));

    private static final String[] OPERATORS = {
            "<=", ">=", "==", "!=", "&&", "||",
            "+", "-", "*", "/", "%", "<", ">", "=", "!", ";", ",", ".", "[", "]", "(", ")", "{", "}"
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
                } else if (scanCStyleComment("Input ends with unterminated comment")) {
                    // 주석 토큰은 scanCStyleComment가 만든다
                } else if (isDigit(c)) {
                    number();
                } else if (isLetter(c)) {
                    word();
                } else if (c == '"') {
                    string();
                } else if (!scanOperator(OPERATORS)) {
                    error(line, column, "Unrecognized char: '" + c + "'");
                    advance();
                }
            }
            return result(Language.DECAF);
        }

        /**
         * 정수: 10진수 또는 16진수(0x1F). 실수: 숫자 '.' 숫자* 지수?  (지수 = [eE][+-]?숫자+)
         * 지수 형식이 맞지 않으면 실수는 그 앞에서 끝나고 E는 다음 토큰이 된다.
         */
        private void number() {
            mark();
            if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X') && isHexDigit(peek(2))) {
                advance(2);
                while (isHexDigit(peek())) {
                    advance();
                }
                emit(TokenKind.INTEGER_LITERAL);
                return;
            }
            while (isDigit(peek())) {
                advance();
            }
            if (peek() != '.') {
                emit(TokenKind.INTEGER_LITERAL);
                return;
            }
            advance();
            while (isDigit(peek())) {
                advance();
            }
            if (peek() == 'e' || peek() == 'E') {
                int sign = (peek(1) == '+' || peek(1) == '-') ? 1 : 0;
                if (isDigit(peek(1 + sign))) {
                    advance(1 + sign);
                    while (isDigit(peek())) {
                        advance();
                    }
                }
            }
            emit(TokenKind.FLOAT_LITERAL);
        }

        private void word() {
            mark();
            while (isLetter(peek()) || isDigit(peek())) {
                advance();
            }
            String text = markedText();
            if (text.equals("true") || text.equals("false")) {
                emit(TokenKind.BOOLEAN_LITERAL);
            } else if (text.equals("null")) {
                emit(TokenKind.NULL_LITERAL);
            } else if (KEYWORDS.contains(text)) {
                emit(TokenKind.KEYWORD);
            } else {
                if (text.length() > MAX_IDENTIFIER_LENGTH) {
                    errorAtMark("Identifier too long: \"" + text + "\"");
                }
                emit(TokenKind.IDENTIFIER);
            }
        }

        /** 문자열은 한 줄 안에서 끝나야 한다. */
        private void string() {
            mark();
            advance();
            while (!atEnd() && peek() != '"' && peek() != '\n' && peek() != '\r') {
                advance();
            }
            if (peek() != '"') {
                errorAtMark("Unterminated string constant: " + markedText());
                return;
            }
            advance();
            emit(TokenKind.STRING_LITERAL);
        }

        private static boolean isLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }
    }
}
