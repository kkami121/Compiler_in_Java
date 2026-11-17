package io.github.kkami121.codeanalyzer.analysis;

import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.metrics.CodeMetrics;
import io.github.kkami121.codeanalyzer.parser.ParseError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 소스 하나의 종합 분석 결과: 지표, 구조, 품질/보안 발견 사항, 스타일. */
public final class AnalysisReport {

    private final String sourceName;
    private final Language language;
    private final CodeMetrics metrics;
    private final List<Finding> findings;
    private final List<ParseError> parseErrors;
    private final int classCount;
    private final int functionCount;
    private final int importCount;
    private final StyleProfile style;

    AnalysisReport(String sourceName, Language language, CodeMetrics metrics, List<Finding> findings,
                   List<ParseError> parseErrors, int classCount, int functionCount, int importCount,
                   StyleProfile style) {
        this.sourceName = sourceName;
        this.language = language;
        this.metrics = metrics;
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        this.parseErrors = Collections.unmodifiableList(new ArrayList<>(parseErrors));
        this.classCount = classCount;
        this.functionCount = functionCount;
        this.importCount = importCount;
        this.style = style;
    }

    public String sourceName() {
        return sourceName;
    }

    public Language language() {
        return language;
    }

    public CodeMetrics metrics() {
        return metrics;
    }

    /** 발견 사항. 줄 번호 순. */
    public List<Finding> findings() {
        return findings;
    }

    public List<Finding> findings(Category category) {
        List<Finding> result = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.category() == category) {
                result.add(finding);
            }
        }
        return result;
    }

    /** 심각도별 개수 (0인 심각도도 포함). */
    public Map<Severity, Integer> severityCounts() {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        for (Finding finding : findings) {
            counts.put(finding.severity(), counts.get(finding.severity()) + 1);
        }
        return counts;
    }

    /** 가장 높은 심각도. 발견 사항이 없으면 null. */
    public Severity highestSeverity() {
        Severity highest = null;
        for (Finding finding : findings) {
            if (highest == null || finding.severity().compareTo(highest) > 0) {
                highest = finding.severity();
            }
        }
        return highest;
    }

    /** 구문 오류. 있으면 오류가 난 문장 안의 규칙 검사는 빠진다. */
    public List<ParseError> parseErrors() {
        return parseErrors;
    }

    /** 구문분석을 해서 규칙 검사를 했는지 (Decaf는 지표만 계산). */
    public boolean rulesApplied() {
        return style != null;
    }

    public int classCount() {
        return classCount;
    }

    public int functionCount() {
        return functionCount;
    }

    public int importCount() {
        return importCount;
    }

    /** 스타일 지표. 구문분석을 지원하지 않는 언어면 null. {@link StyleProfile#NOTE} 참고. */
    public StyleProfile style() {
        return style;
    }
}
