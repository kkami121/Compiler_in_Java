package io.github.kkami121.codeanalyzer.lexer;

import java.util.Objects;

/**
 * 토큰 하나. 줄과 열은 1부터 세고, 끝 위치({@link #endLine()}, {@link #endColumn()})는 마지막 문자를 가리킨다.
 * INDENT/DEDENT 같은 가상 토큰은 텍스트가 비어 있고 시작과 끝 위치가 같다.
 */
public final class Token {

    private final TokenKind kind;
    private final String text;
    private final int line;
    private final int column;
    private final int endLine;
    private final int endColumn;

    public Token(TokenKind kind, String text, int line, int column, int endLine, int endColumn) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = Objects.requireNonNull(text, "text");
        this.line = line;
        this.column = column;
        this.endLine = endLine;
        this.endColumn = endColumn;
    }

    public TokenKind kind() {
        return kind;
    }

    /** 소스에 적힌 그대로의 문자열. */
    public String text() {
        return text;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public int endLine() {
        return endLine;
    }

    public int endColumn() {
        return endColumn;
    }

    public boolean is(TokenKind kind, String text) {
        return this.kind == kind && this.text.equals(text);
    }

    /** 연산자/구분자이면서 텍스트가 같은지. */
    public boolean isOperator(String text) {
        return is(TokenKind.OPERATOR, text);
    }

    public boolean isKeyword(String text) {
        return is(TokenKind.KEYWORD, text);
    }

    @Override
    public String toString() {
        return line + ":" + column + " " + kind + " " + text;
    }
}
