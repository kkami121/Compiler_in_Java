package io.github.kkami121.codeanalyzer.lexer;

import io.github.kkami121.codeanalyzer.Language;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Python 3 어휘분석기.
 *
 * <p>CPython 토크나이저처럼 논리적 줄 끝에 NEWLINE, 들여쓰기가 바뀔 때 INDENT/DEDENT 가상 토큰을 만든다.
 * 괄호 안의 줄바꿈과 백슬래시 줄 이음은 같은 논리적 줄로 본다. 빈 줄과 주석만 있는 줄은 들여쓰기에 영향을 주지 않는다.
 * {@code match}, {@code case}, {@code type} 같은 소프트 키워드는 식별자로 분류한다.
 * f-string은 하나의 문자열 토큰으로 만든다.
 */
public final class PythonLexer implements Lexer {

    static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
            "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
            "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield")));

    private static final Set<String> STRING_PREFIXES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "r", "u", "b", "f", "t", "br", "rb", "fr", "rf", "tr", "rt")));

    private static final String[] OPERATORS = {
            "**=", "//=", ">>=", "<<=", "...",
            "->", ":=", "**", "//", "<<", ">>", "<=", ">=", "==", "!=", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "@=",
            "+", "-", "*", "/", "%", "@", "&", "|", "^", "~", "<", ">",
            "(", ")", "[", "]", "{", "}", ",", ":", ";", ".", "="
    };

    private static final int TAB_SIZE = 8;

    @Override
    public LexResult tokenize(String source) {
        return new Scanner(source).run();
    }

    private static final class Scanner extends SourceScanner {

        private final Deque<Integer> indents = new ArrayDeque<>();
        private int bracketDepth = 0;
        private boolean atLineStart = true;
        private boolean lineHasCode = false;

        Scanner(String source) {
            super(source);
            indents.push(0);
        }

        LexResult run() {
            while (true) {
                if (atLineStart && bracketDepth == 0) {
                    atLineStart = false;
                    indentation();
                }
                if (atEnd()) {
                    break;
                }
                char c = peek();
                if (isInlineWhitespace(c)) {
                    advance();
                } else if (c == '\n') {
                    newline();
                } else if (c == '#') {
                    mark();
                    while (!atEnd() && peek() != '\n' && peek() != '\r') {
                        advance();
                    }
                    emit(TokenKind.COMMENT);
                } else if (c == '\\' && (peek(1) == '\n' || (peek(1) == '\r' && peek(2) == '\n'))) {
                    advance(peek(1) == '\r' ? 3 : 2); // 줄 이음: 같은 논리적 줄
                } else {
                    lineHasCode = true;
                    codeToken(c);
                }
            }
            if (lineHasCode) {
                emitVirtual(TokenKind.NEWLINE);
            }
            while (indents.size() > 1) {
                indents.pop();
                emitVirtual(TokenKind.DEDENT);
            }
            return result(Language.PYTHON);
        }

        private void codeToken(char c) {
            int codePoint = peekCodePoint();
            if (Character.isUnicodeIdentifierStart(codePoint) || c == '_') {
                word();
            } else if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
                number();
            } else if (c == '"' || c == '\'') {
                mark();
                string("");
            } else if (scanOperator(OPERATORS)) {
                char op = c;
                if (op == '(' || op == '[' || op == '{') {
                    bracketDepth++;
                } else if ((op == ')' || op == ']' || op == '}') && bracketDepth > 0) {
                    bracketDepth--;
                }
            } else {
                error(line, column, "Unrecognized character: '" + new String(Character.toChars(codePoint)) + "'");
                advanceCodePoint();
            }
        }

        private void newline() {
            mark();
            advance();
            if (bracketDepth == 0) {
                if (lineHasCode) {
                    emit(TokenKind.NEWLINE);
                    lineHasCode = false;
                }
                atLineStart = true;
            }
        }

        /** 줄 맨 앞의 공백을 세어 INDENT/DEDENT를 만든다. 빈 줄과 주석 줄은 건너뛴다. */
        private void indentation() {
            int width = 0;
            while (peek() == ' ' || peek() == '\t' || peek() == '\f') {
                char c = advance();
                if (c == ' ') {
                    width++;
                } else if (c == '\t') {
                    width = (width / TAB_SIZE + 1) * TAB_SIZE;
                } else {
                    width = 0;
                }
            }
            char c = peek();
            if (atEnd() || c == '\n' || c == '\r' || c == '#') {
                return;
            }
            if (width > indents.peek()) {
                indents.push(width);
                emitVirtual(TokenKind.INDENT);
                return;
            }
            while (width < indents.peek()) {
                indents.pop();
                emitVirtual(TokenKind.DEDENT);
            }
            if (width != indents.peek()) {
                error(line, column, "Unindent does not match any outer indentation level");
                indents.push(width);
            }
        }

        private void word() {
            mark();
            while (!atEnd()) {
                int cp = peekCodePoint();
                if (!(Character.isUnicodeIdentifierPart(cp) || cp == '_') || Character.isIdentifierIgnorable(cp)) {
                    break;
                }
                advanceCodePoint();
            }
            String text = markedText();
            if ((peek() == '"' || peek() == '\'') && STRING_PREFIXES.contains(text.toLowerCase(Locale.ROOT))) {
                string(text.toLowerCase(Locale.ROOT));
                return;
            }
            switch (text) {
                case "True":
                case "False":
                    emit(TokenKind.BOOLEAN_LITERAL);
                    break;
                case "None":
                    emit(TokenKind.NULL_LITERAL);
                    break;
                default:
                    emit(KEYWORDS.contains(text) ? TokenKind.KEYWORD : TokenKind.IDENTIFIER);
            }
        }

        /** 10/16/8/2진 정수, 실수, 허수(3j), 밑줄(1_000)을 처리한다. */
        private void number() {
            mark();
            boolean isFloat = false;
            char second = Character.toLowerCase(peek(1));
            if (peek() == '0' && (second == 'x' || second == 'o' || second == 'b')) {
                advance(2);
                while (isHexDigit(peek()) || peek() == '_') {
                    advance();
                }
                emit(TokenKind.INTEGER_LITERAL);
                return;
            }
            skipDigits();
            if (peek() == '.') {
                char next = peek(1);
                if (isDigit(next) || next == 'e' || next == 'E' || next == 'j' || next == 'J'
                        || !(Character.isUnicodeIdentifierStart(next) || next == '_' || next == '.')) {
                    advance();
                    skipDigits();
                    isFloat = true;
                }
            }
            if ((peek() == 'e' || peek() == 'E')) {
                int sign = (peek(1) == '+' || peek(1) == '-') ? 1 : 0;
                if (isDigit(peek(1 + sign))) {
                    advance(1 + sign);
                    skipDigits();
                    isFloat = true;
                }
            }
            if (peek() == 'j' || peek() == 'J') {
                advance();
                isFloat = true;
            }
            emit(isFloat ? TokenKind.FLOAT_LITERAL : TokenKind.INTEGER_LITERAL);
        }

        private void skipDigits() {
            while (isDigit(peek()) || peek() == '_') {
                advance();
            }
        }

        /** 접두사(이미 읽음)와 따옴표부터 시작하는 문자열. mark()는 호출하는 쪽에서 했다. */
        private void string(String prefix) {
            boolean formatted = prefix.indexOf('f') >= 0 || prefix.indexOf('t') >= 0;
            char quote = peek();
            boolean triple = peek(1) == quote && peek(2) == quote;
            advance(triple ? 3 : 1);
            if (stringBody(quote, triple, formatted)) {
                emit(TokenKind.STRING_LITERAL);
            } else {
                errorAtMark(triple ? "Unterminated triple-quoted string literal" : "Unterminated string literal");
                if (triple) {
                    emit(TokenKind.STRING_LITERAL);
                }
            }
        }

        /** 닫는 따옴표까지 읽는다. 성공하면 true. */
        private boolean stringBody(char quote, boolean triple, boolean formatted) {
            while (!atEnd()) {
                char c = peek();
                if (triple) {
                    if (c == quote && peek(1) == quote && peek(2) == quote) {
                        advance(3);
                        return true;
                    }
                } else if (c == quote) {
                    advance();
                    return true;
                } else if (c == '\n' || c == '\r') {
                    return false;
                }
                if (c == '\\') {
                    // 원시 문자열에서도 따옴표 앞 백슬래시는 문자열을 끝내지 않는다. \r\n 줄 이음은 한 번에 넘긴다.
                    // f-string에서 백슬래시는 { } 를 이스케이프하지 않는다 (PEP 701).
                    char next = peek(1);
                    if (formatted && (next == '{' || next == '}')) {
                        advance();
                    } else {
                        advance(next == '\r' && peek(2) == '\n' ? 3 : 2);
                    }
                } else if (formatted && c == '{') {
                    if (peek(1) == '{') {
                        advance(2);
                    } else {
                        advance();
                        if (!replacementField()) {
                            return false;
                        }
                    }
                } else {
                    advance();
                }
            }
            return false;
        }

        /**
         * f-string의 {...} 중 식 부분 (여는 { 다음부터). PEP 701(Python 3.12) 규칙을 따른다:
         * 안쪽에 같은 따옴표의 문자열, 줄바꿈, # 주석이 올 수 있다. 괄호 밖의 ':'부터는 서식 지정이다.
         */
        private boolean replacementField() {
            int brackets = 0;
            while (!atEnd()) {
                char c = peek();
                if (c == '(' || c == '[' || c == '{') {
                    brackets++;
                    advance();
                } else if (c == ')' || c == ']') {
                    brackets = Math.max(0, brackets - 1);
                    advance();
                } else if (c == '}') {
                    advance();
                    if (brackets == 0) {
                        return true;
                    }
                    brackets--;
                } else if (c == ':' && brackets == 0) {
                    advance();
                    return formatSpec();
                } else if (c == '"' || c == '\'') {
                    boolean nestedTriple = peek(1) == c && peek(2) == c;
                    advance(nestedTriple ? 3 : 1);
                    if (!stringBody(c, nestedTriple, false)) {
                        return false;
                    }
                } else if (c == '#') {
                    while (!atEnd() && peek() != '\n') {
                        advance();
                    }
                } else {
                    advance();
                }
            }
            return false;
        }

        /** {@code {x:>{width}}} 의 ':' 뒤 서식 지정. 안에 다시 {식}이 올 수 있다. */
        private boolean formatSpec() {
            while (!atEnd()) {
                char c = peek();
                if (c == '{') {
                    advance();
                    if (!replacementField()) {
                        return false;
                    }
                } else if (c == '}') {
                    advance();
                    return true;
                } else {
                    advance();
                }
            }
            return false;
        }
    }
}
