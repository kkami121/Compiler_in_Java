package io.github.kkami121.codeanalyzer.clone;

/** 서로 같은 구조를 가진 코드 조각 두 개. */
public final class ClonePair {

    private final CodeFragment first;
    private final CodeFragment second;
    private final int tokenCount;
    private final CloneType type;

    ClonePair(CodeFragment first, CodeFragment second, int tokenCount, CloneType type) {
        this.first = first;
        this.second = second;
        this.tokenCount = tokenCount;
        this.type = type;
    }

    public CodeFragment first() {
        return first;
    }

    public CodeFragment second() {
        return second;
    }

    /** 일치하는 코드 토큰 수 (주석 제외). */
    public int tokenCount() {
        return tokenCount;
    }

    public CloneType type() {
        return type;
    }

    @Override
    public String toString() {
        return type + " " + tokenCount + " tokens: " + first + " ~ " + second;
    }
}
