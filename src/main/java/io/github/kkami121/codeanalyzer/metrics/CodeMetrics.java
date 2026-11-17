package io.github.kkami121.codeanalyzer.metrics;

import io.github.kkami121.codeanalyzer.Language;

import java.util.Collections;
import java.util.List;

/**
 * 소스 하나의 지표와 난이도.
 *
 * <h2>난이도 판정</h2>
 * 아래 세 항목의 등급 중 가장 높은 것을 전체 난이도로 한다. 판정 근거는 {@link #difficultyReasons()}에 있다.
 * <table border="1">
 *   <caption>등급 기준</caption>
 *   <tr><th>항목</th><th>EASY</th><th>MODERATE</th><th>HARD</th><th>VERY_HARD</th></tr>
 *   <tr><td>최대 순환 복잡도 (McCabe/SEI 기준)</td><td>≤ 10</td><td>11–20</td><td>21–50</td><td>&gt; 50</td></tr>
 *   <tr><td>최대 중첩 깊이</td><td>≤ 2</td><td>3–4</td><td>5–6</td><td>&gt; 6</td></tr>
 *   <tr><td>함수별 최대 Halstead 난이도</td><td>≤ 15</td><td>≤ 30</td><td>≤ 60</td><td>&gt; 60</td></tr>
 * </table>
 * 순환 복잡도 기준은 널리 쓰이는 SEI 분류이고, 중첩 깊이와 Halstead 기준은 이 라이브러리의 경험적 기준이다.
 */
public final class CodeMetrics {

    private final Language language;
    private final LineMetrics lines;
    private final int tokenCount;
    private final int lexErrorCount;
    private final Halstead halstead;
    private final List<FunctionMetrics> functions;
    private final int topLevelComplexity;
    private final int maxNestingDepth;
    private final DifficultyLevel difficulty;
    private final List<String> difficultyReasons;

    CodeMetrics(Language language, LineMetrics lines, int tokenCount, int lexErrorCount, Halstead halstead,
                List<FunctionMetrics> functions, int topLevelComplexity, int maxNestingDepth,
                DifficultyLevel difficulty, List<String> difficultyReasons) {
        this.language = language;
        this.lines = lines;
        this.tokenCount = tokenCount;
        this.lexErrorCount = lexErrorCount;
        this.halstead = halstead;
        this.functions = Collections.unmodifiableList(functions);
        this.topLevelComplexity = topLevelComplexity;
        this.maxNestingDepth = maxNestingDepth;
        this.difficulty = difficulty;
        this.difficultyReasons = Collections.unmodifiableList(difficultyReasons);
    }

    public Language language() {
        return language;
    }

    public LineMetrics lines() {
        return lines;
    }

    /** 주석과 레이아웃을 뺀 코드 토큰 수. */
    public int tokenCount() {
        return tokenCount;
    }

    /** 어휘 오류 수. 0이 아니면 지표가 부정확할 수 있다. */
    public int lexErrorCount() {
        return lexErrorCount;
    }

    /** 소스 전체의 Halstead 지표. */
    public Halstead halstead() {
        return halstead;
    }

    /** 함수별 지표. 소스에 나온 순서. */
    public List<FunctionMetrics> functions() {
        return functions;
    }

    /** 함수 밖 코드(Python 모듈 수준 코드, 필드 초기화 등)의 순환 복잡도. */
    public int topLevelComplexity() {
        return topLevelComplexity;
    }

    /** 함수들과 함수 밖 코드 중 가장 큰 순환 복잡도. */
    public int maxCyclomaticComplexity() {
        int max = topLevelComplexity;
        for (FunctionMetrics function : functions) {
            max = Math.max(max, function.cyclomaticComplexity());
        }
        return max;
    }

    /** 함수 평균 순환 복잡도. 함수가 없으면 함수 밖 코드의 복잡도. */
    public double averageCyclomaticComplexity() {
        if (functions.isEmpty()) {
            return topLevelComplexity;
        }
        double sum = 0;
        for (FunctionMetrics function : functions) {
            sum += function.cyclomaticComplexity();
        }
        return sum / functions.size();
    }

    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    public DifficultyLevel difficulty() {
        return difficulty;
    }

    /** 난이도 판정 근거. 항목마다 한 줄. */
    public List<String> difficultyReasons() {
        return difficultyReasons;
    }
}
