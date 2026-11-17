package io.github.kkami121.codeanalyzer.metrics;

import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 어휘분석 결과로 {@link CodeMetrics}를 계산한다. */
public final class MetricsCalculator {

    /** 순환 복잡도에서 분기 하나로 세는 키워드/연산자. */
    private static final Set<String> JAVA_DECISIONS = set("if", "for", "while", "case", "catch", "&&", "||", "?");
    private static final Set<String> PYTHON_DECISIONS = set("if", "elif", "for", "while", "except", "and", "or");
    private static final Set<String> DECAF_DECISIONS = set("if", "for", "while", "&&", "||");

    private MetricsCalculator() {
    }

    /**
     * @param source 줄 수를 세기 위한 원본 소스
     * @param result 같은 소스의 어휘분석 결과
     */
    public static CodeMetrics calculate(String source, LexResult result) {
        Language language = result.language();
        List<Token> tokens = new ArrayList<>();   // 주석을 뺀 토큰 (Python은 레이아웃 포함)
        for (Token token : result.tokens()) {
            if (token.kind() != TokenKind.COMMENT) {
                tokens.add(token);
            }
        }

        CodeStructure structure = language == Language.PYTHON
                ? IndentStructureAnalyzer.analyze(tokens)
                : BraceStructureAnalyzer.analyze(tokens);
        List<CodeStructure.FunctionSpan> spans = new ArrayList<>(structure.functions);
        spans.sort(Comparator.comparingInt(span -> span.start));

        // 토큰마다 가장 안쪽 함수를 주인으로 정한다 (-1: 함수 밖)
        int[] owner = new int[tokens.size()];
        Arrays.fill(owner, -1);
        for (int f = 0; f < spans.size(); f++) {
            CodeStructure.FunctionSpan span = spans.get(f);
            for (int i = span.start; i <= span.end && i < tokens.size(); i++) {
                owner[i] = f;
            }
        }

        Set<String> decisions = decisionsFor(language);
        int[] complexity = new int[spans.size()];
        List<List<Token>> owned = new ArrayList<>();
        for (int f = 0; f < spans.size(); f++) {
            complexity[f] = 1;
            owned.add(new ArrayList<Token>());
        }
        int topLevelComplexity = 1;
        List<Token> codeTokens = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.kind().isCode()) {
                continue;
            }
            codeTokens.add(token);
            boolean decision = isDecision(tokens, i, decisions);
            if (owner[i] >= 0) {
                owned.get(owner[i]).add(token);
                if (decision) {
                    complexity[owner[i]]++;
                }
            } else if (decision) {
                topLevelComplexity++;
            }
        }

        List<FunctionMetrics> functions = new ArrayList<>();
        for (int f = 0; f < spans.size(); f++) {
            CodeStructure.FunctionSpan span = spans.get(f);
            Token first = tokens.get(span.start);
            Token last = tokens.get(Math.min(span.end, tokens.size() - 1));
            functions.add(new FunctionMetrics(span.name, first.line(), last.endLine(), complexity[f],
                    span.maxNesting, Halstead.of(owned.get(f))));
        }

        int maxNesting = structure.topLevelMaxNesting;
        for (FunctionMetrics function : functions) {
            maxNesting = Math.max(maxNesting, function.maxNestingDepth());
        }

        Halstead halstead = Halstead.of(codeTokens);
        List<String> reasons = new ArrayList<>();
        DifficultyLevel level = judge(functions, topLevelComplexity, structure.topLevelMaxNesting, halstead, reasons);

        return new CodeMetrics(language, countLines(source, result.tokens()), codeTokens.size(),
                result.errors().size(), halstead, functions, topLevelComplexity, maxNesting, level, reasons);
    }

    private static boolean isDecision(List<Token> tokens, int i, Set<String> decisions) {
        Token token = tokens.get(i);
        if (token.kind() != TokenKind.KEYWORD && token.kind() != TokenKind.OPERATOR) {
            return false;
        }
        if (!decisions.contains(token.text())) {
            return false;
        }
        if (token.text().equals("?") && i + 1 < tokens.size()) {
            // List<?>, Map<? extends K, ?> 의 와일드카드는 삼항 연산자가 아니다
            Token next = tokens.get(i + 1);
            String t = next.text();
            return !(t.equals(">") || t.equals(",") || t.equals(">>") || t.equals(">>>")
                    || t.equals("extends") || t.equals("super"));
        }
        return true;
    }

    private static Set<String> decisionsFor(Language language) {
        switch (language) {
            case PYTHON:
                return PYTHON_DECISIONS;
            case DECAF:
                return DECAF_DECISIONS;
            default:
                return JAVA_DECISIONS;
        }
    }

    private static DifficultyLevel judge(List<FunctionMetrics> functions, int topLevelComplexity,
                                         int topLevelNesting, Halstead fileHalstead, List<String> reasons) {
        String complexityWhere = "top-level code";
        int maxComplexity = topLevelComplexity;
        String nestingWhere = "top-level code";
        int maxNesting = topLevelNesting;
        String halsteadWhere = "whole file";
        double maxHalstead = functions.isEmpty() ? fileHalstead.difficulty() : 0;

        for (FunctionMetrics function : functions) {
            if (function.cyclomaticComplexity() > maxComplexity) {
                maxComplexity = function.cyclomaticComplexity();
                complexityWhere = "function '" + function.name() + "'";
            }
            if (function.maxNestingDepth() > maxNesting) {
                maxNesting = function.maxNestingDepth();
                nestingWhere = "function '" + function.name() + "'";
            }
            if (function.halstead().difficulty() > maxHalstead) {
                maxHalstead = function.halstead().difficulty();
                halsteadWhere = "function '" + function.name() + "'";
            }
        }

        DifficultyLevel byComplexity = DifficultyLevel.grade(maxComplexity, 10, 20, 50);
        DifficultyLevel byNesting = DifficultyLevel.grade(maxNesting, 2, 4, 6);
        DifficultyLevel byHalstead = DifficultyLevel.grade(maxHalstead, 15, 30, 60);

        reasons.add(String.format(Locale.ROOT, "Cyclomatic complexity: max %d in %s -> %s",
                maxComplexity, complexityWhere, byComplexity));
        reasons.add(String.format(Locale.ROOT, "Nesting depth: max %d in %s -> %s",
                maxNesting, nestingWhere, byNesting));
        reasons.add(String.format(Locale.ROOT, "Halstead difficulty: max %.1f in %s -> %s",
                maxHalstead, halsteadWhere, byHalstead));

        return DifficultyLevel.max(byComplexity, DifficultyLevel.max(byNesting, byHalstead));
    }

    private static LineMetrics countLines(String source, List<Token> tokens) {
        if (source.isEmpty()) {
            return new LineMetrics(0, 0, 0, 0);
        }
        int total = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n' && i < source.length() - 1) {
                total++;
            }
        }
        boolean[] code = new boolean[total + 2];
        boolean[] comment = new boolean[total + 2];
        for (Token token : tokens) {
            if (token.kind().isLayout()) {
                continue;
            }
            boolean[] target = token.kind() == TokenKind.COMMENT ? comment : code;
            for (int line = token.line(); line <= Math.min(token.endLine(), total); line++) {
                target[line] = true;
            }
        }
        int codeLines = 0;
        int commentLines = 0;
        for (int line = 1; line <= total; line++) {
            if (code[line]) {
                codeLines++;
            } else if (comment[line]) {
                commentLines++;
            }
        }
        return new LineMetrics(total, codeLines, commentLines, total - codeLines - commentLines);
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
