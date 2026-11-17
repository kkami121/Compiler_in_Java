package io.github.kkami121.codeanalyzer.metrics;

/** 함수(메서드) 하나의 지표. 중첩 함수의 코드는 바깥 함수가 아니라 중첩 함수 쪽에 센다. */
public final class FunctionMetrics {

    private final String name;
    private final int startLine;
    private final int endLine;
    private final int cyclomaticComplexity;
    private final int maxNestingDepth;
    private final Halstead halstead;

    FunctionMetrics(String name, int startLine, int endLine, int cyclomaticComplexity, int maxNestingDepth,
                    Halstead halstead) {
        this.name = name;
        this.startLine = startLine;
        this.endLine = endLine;
        this.cyclomaticComplexity = cyclomaticComplexity;
        this.maxNestingDepth = maxNestingDepth;
        this.halstead = halstead;
    }

    public String name() {
        return name;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }

    public int lineCount() {
        return endLine - startLine + 1;
    }

    /** McCabe 순환 복잡도 = 1 + 분기 수. */
    public int cyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    /** 함수 안 제어 블록의 최대 중첩 깊이. */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    public Halstead halstead() {
        return halstead;
    }
}
