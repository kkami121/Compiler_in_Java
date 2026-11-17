package io.github.kkami121.codeanalyzer.clone;

/** 소스 안의 코드 조각 위치. 줄과 열은 1부터, 끝 위치는 마지막 문자를 포함한다. */
public final class CodeFragment {

    private final String sourceName;
    private final int startLine;
    private final int startColumn;
    private final int endLine;
    private final int endColumn;

    CodeFragment(String sourceName, int startLine, int startColumn, int endLine, int endColumn) {
        this.sourceName = sourceName;
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
    }

    public String sourceName() {
        return sourceName;
    }

    public int startLine() {
        return startLine;
    }

    public int startColumn() {
        return startColumn;
    }

    public int endLine() {
        return endLine;
    }

    public int endColumn() {
        return endColumn;
    }

    public int lineCount() {
        return endLine - startLine + 1;
    }

    @Override
    public String toString() {
        return sourceName + ":" + startLine + "-" + endLine;
    }
}
