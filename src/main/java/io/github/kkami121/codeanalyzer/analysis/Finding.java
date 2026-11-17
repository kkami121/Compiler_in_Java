package io.github.kkami121.codeanalyzer.analysis;

/** 규칙 하나가 찾은 문제 하나. */
public final class Finding {

    private final String ruleId;
    private final Category category;
    private final Severity severity;
    private final String cwe;
    private final String message;
    private final int line;
    private final int column;
    private final int endLine;

    Finding(String ruleId, Category category, Severity severity, String cwe, String message,
            int line, int column, int endLine) {
        this.ruleId = ruleId;
        this.category = category;
        this.severity = severity;
        this.cwe = cwe;
        this.message = message;
        this.line = line;
        this.column = column;
        this.endLine = endLine;
    }

    /** 규칙 이름. 예: {@code sql-injection}. */
    public String ruleId() {
        return ruleId;
    }

    public Category category() {
        return category;
    }

    public Severity severity() {
        return severity;
    }

    /** 대응하는 CWE (예: {@code CWE-89}). 없으면 null. */
    public String cwe() {
        return cwe;
    }

    public String message() {
        return message;
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

    @Override
    public String toString() {
        return line + ":" + column + " " + severity + " " + ruleId + (cwe == null ? "" : " (" + cwe + ")") + " " + message;
    }
}
