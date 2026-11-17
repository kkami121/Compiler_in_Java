package io.github.kkami121.codeanalyzer;

import io.github.kkami121.codeanalyzer.analysis.AnalysisReport;
import io.github.kkami121.codeanalyzer.analysis.Analyzer;
import io.github.kkami121.codeanalyzer.clone.CloneDetector;
import io.github.kkami121.codeanalyzer.clone.ClonePair;
import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.metrics.CodeMetrics;
import io.github.kkami121.codeanalyzer.metrics.MetricsCalculator;
import io.github.kkami121.codeanalyzer.parser.SyntaxTree;

import java.util.List;

/**
 * 라이브러리의 진입점. 모든 메서드는 상태가 없고 스레드 안전하다.
 *
 * <pre>{@code
 * LexResult tokens  = CodeAnalyzer.tokenize("int x = 1;", Language.JAVA);
 * CodeMetrics m     = CodeAnalyzer.measure(source, Language.PYTHON);
 * m.difficulty();                                  // EASY, MODERATE, HARD, VERY_HARD
 * List<ClonePair> c = CodeAnalyzer.findClones(Arrays.asList(fileA, fileB));
 * double s          = CodeAnalyzer.similarity(fileA, fileB);   // 0.0 ~ 1.0
 * }</pre>
 */
public final class CodeAnalyzer {

    public static final String VERSION = "0.2.0";

    private static final CloneDetector DEFAULT_DETECTOR = new CloneDetector();

    private CodeAnalyzer() {
    }

    /** 토큰 분리 (어휘분석). */
    public static LexResult tokenize(String source, Language language) {
        return language.lexer().tokenize(source);
    }

    /**
     * 구문분석. Java와 Python만 지원한다.
     *
     * @throws UnsupportedOperationException Decaf 등 구문분석을 지원하지 않는 언어
     */
    public static SyntaxTree parse(String source, Language language) {
        return SyntaxTree.parse(tokenize(source, language));
    }

    /** 줄 수, Halstead, 순환 복잡도, 중첩 깊이, 난이도 등급. */
    public static CodeMetrics measure(String source, Language language) {
        return MetricsCalculator.calculate(source, tokenize(source, language));
    }

    public static CodeMetrics measure(SourceFile file) {
        return measure(file.content(), file.language());
    }

    /**
     * 종합 분석: 지표, 구조, 품질/보안 발견 사항(CWE), 스타일 지표.
     * Decaf는 구문분석을 지원하지 않아 지표만 계산한다.
     */
    public static AnalysisReport analyze(SourceFile file) {
        return Analyzer.analyze(file);
    }

    public static AnalysisReport analyze(String source, Language language) {
        return Analyzer.analyze(new SourceFile("<source>", language, source));
    }

    /** 기본 최소 길이({@value CloneDetector#DEFAULT_MIN_TOKENS} 토큰)로 클론을 찾는다. */
    public static List<ClonePair> findClones(List<SourceFile> files) {
        return DEFAULT_DETECTOR.detect(files);
    }

    public static List<ClonePair> findClones(List<SourceFile> files, int minTokens) {
        return new CloneDetector(minTokens).detect(files);
    }

    /** 두 소스의 구조적 유사도 (0~1). */
    public static double similarity(SourceFile first, SourceFile second) {
        return DEFAULT_DETECTOR.similarity(first, second);
    }
}
