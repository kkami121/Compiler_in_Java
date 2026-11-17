package io.github.kkami121.codeanalyzer.metrics;

/**
 * 줄 수 통계. 코드와 주석이 같이 있는 줄은 코드 줄로 센다.
 * total = code + comment + blank.
 */
public final class LineMetrics {

    private final int total;
    private final int code;
    private final int comment;
    private final int blank;

    LineMetrics(int total, int code, int comment, int blank) {
        this.total = total;
        this.code = code;
        this.comment = comment;
        this.blank = blank;
    }

    public int total() {
        return total;
    }

    public int code() {
        return code;
    }

    /** 주석만 있는 줄. */
    public int comment() {
        return comment;
    }

    public int blank() {
        return blank;
    }
}
