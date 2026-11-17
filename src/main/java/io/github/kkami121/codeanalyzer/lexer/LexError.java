package io.github.kkami121.codeanalyzer.lexer;

/** 어휘 오류. 렉서는 오류가 나도 멈추지 않고 다음 문자부터 분석을 계속한다. */
public final class LexError {

    private final int line;
    private final int column;
    private final String message;

    public LexError(int line, int column, String message) {
        this.line = line;
        this.column = column;
        this.message = message;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return line + ":" + column + " " + message;
    }
}
