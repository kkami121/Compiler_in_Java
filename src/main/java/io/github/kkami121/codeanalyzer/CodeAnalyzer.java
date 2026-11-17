package io.github.kkami121.codeanalyzer;

import io.github.kkami121.codeanalyzer.clone.CloneDetector;
import io.github.kkami121.codeanalyzer.clone.ClonePair;
import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.metrics.CodeMetrics;
import io.github.kkami121.codeanalyzer.metrics.MetricsCalculator;

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

    public static final String VERSION = "0.1.0";

    private static final CloneDetector DEFAULT_DETECTOR = new CloneDetector();

    private CodeAnalyzer() {
    }

    /** 토큰 분리 (어휘분석). */
    public static LexResult tokenize(String source, Language language) {
        return language.lexer().tokenize(source);
    }

    /** 줄 수, Halstead, 순환 복잡도, 중첩 깊이, 난이도 등급. */
    public static CodeMetrics measure(String source, Language language) {
        return MetricsCalculator.calculate(source, tokenize(source, language));
    }

    public static CodeMetrics measure(SourceFile file) {
        return measure(file.content(), file.language());
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
