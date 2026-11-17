package io.github.kkami121.codeanalyzer.parser;

/** 구문 오류. 파서는 오류가 난 문장을 건너뛰고 다음 문장부터 계속 분석한다. */
public final class ParseError {

    private final int line;
    private final int column;
    private final String message;

    public ParseError(int line, int column, String message) {
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
