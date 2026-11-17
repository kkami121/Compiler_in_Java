package io.github.kkami121.codeanalyzer.analysis;

import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.SourceFile;
import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;
import io.github.kkami121.codeanalyzer.metrics.CodeMetrics;
import io.github.kkami121.codeanalyzer.metrics.FunctionMetrics;
import io.github.kkami121.codeanalyzer.metrics.MetricsCalculator;
import io.github.kkami121.codeanalyzer.parser.Node;
import io.github.kkami121.codeanalyzer.parser.NodeKind;
import io.github.kkami121.codeanalyzer.parser.ParseError;
import io.github.kkami121.codeanalyzer.parser.SyntaxTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 종합 분석기: 어휘분석 → 구문분석 → 지표 → 품질/보안 규칙 → 스타일. 상태가 없다. */
public final class Analyzer {

    private Analyzer() {
    }

    public static AnalysisReport analyze(SourceFile file) {
        LexResult lex = file.language().lexer().tokenize(file.content());
        CodeMetrics metrics = MetricsCalculator.calculate(file.content(), lex);
        if (!SyntaxTree.isSupported(file.language())) {
            return new AnalysisReport(file.name(), file.language(), metrics, Collections.<Finding>emptyList(),
                    Collections.<ParseError>emptyList(), 0, metrics.functions().size(), 0, null);
        }
        SyntaxTree tree = SyntaxTree.parse(lex);
        RuleContext ctx = new RuleContext(file, tree, metrics);
        QualityRules.run(ctx);
        SecurityRules.run(ctx);

        List<Finding> findings = withoutSuppressed(deduplicate(ctx.findings), lex);
        findings.sort(Comparator.comparingInt(Finding::line).thenComparingInt(Finding::column)
                .thenComparing(f -> f.severity(), Comparator.reverseOrder()));

        Node root = tree.root();
        int imports = 0;
        for (Node node : root.findAll(NodeKind.IMPORT)) {
            imports += Math.max(1, node.children().size());
        }
        return new AnalysisReport(file.name(), file.language(), metrics, findings, tree.errors(),
                root.findAll(NodeKind.CLASS).size(), root.findAll(NodeKind.FUNCTION).size(), imports,
                style(ctx, root, metrics));
    }

    /** 이 주석이 붙은 줄의 발견 사항은 보고하지 않는다 (flake8/Bandit/SonarQube 관례와 이 도구 전용 표시). */
    static final Pattern SUPPRESSION = Pattern.compile("(?i)\\b(noqa|nosec|NOSONAR|codeanalyzer:\\s*ignore)\\b");

    private static List<Finding> withoutSuppressed(List<Finding> findings, LexResult lex) {
        Set<Integer> suppressedLines = new HashSet<>();
        for (Token comment : lex.comments()) {
            if (SUPPRESSION.matcher(comment.text()).find()) {
                suppressedLines.add(comment.line());
            }
        }
        if (suppressedLines.isEmpty()) {
            return findings;
        }
        List<Finding> result = new ArrayList<>();
        for (Finding finding : findings) {
            if (!suppressedLines.contains(finding.line())) {
                result.add(finding);
            }
        }
        return result;
    }

    /** 같은 규칙이 같은 위치를 여러 번 보고한 것을 하나로. */
    private static List<Finding> deduplicate(List<Finding> findings) {
        Set<String> seen = new HashSet<>();
        List<Finding> result = new ArrayList<>();
        for (Finding finding : findings) {
            if (seen.add(finding.ruleId() + "@" + finding.line() + ":" + finding.column())) {
                result.add(finding);
            }
        }
        return result;
    }

    private static StyleProfile style(RuleContext ctx, Node root, CodeMetrics metrics) {
        int codeLines = Math.max(1, metrics.lines().code());
        double commentRatio = (double) metrics.lines().comment() / codeLines;

        List<Node> functions = root.findAll(NodeKind.FUNCTION);
        int documented = 0;
        int params = 0;
        int typed = 0;
        Map<String, Integer> styles = new HashMap<>();
        for (Node function : functions) {
            if (isDocumented(ctx, function)) {
                documented++;
            }
            for (Node param : function.childrenOfKind(NodeKind.PARAMETER)) {
                if (param.text().equals("self") || param.text().equals("cls")) {
                    continue;
                }
                params++;
                if (param.childOfKind(NodeKind.TYPE) != null) {
                    typed++;
                }
            }
            addStyle(styles, function.text());
        }
        for (Node node : root.descendants()) {
            if (node.kind() == NodeKind.VARIABLE && ctx.parent(node) != null
                    && ctx.parent(node).kind() != NodeKind.IMPORT) {
                addStyle(styles, node.text());
            } else if (node.kind() == NodeKind.ASSIGN && node.firstChild() != null
                    && node.firstChild().kind() == NodeKind.NAME) {
                addStyle(styles, node.firstChild().text());
            }
        }
        String dominant = "none";
        int dominantCount = 0;
        int total = 0;
        for (Map.Entry<String, Integer> entry : styles.entrySet()) {
            total += entry.getValue();
            if (entry.getValue() > dominantCount) {
                dominant = entry.getKey();
                dominantCount = entry.getValue();
            }
        }

        Set<String> identifiers = new LinkedHashSet<>();
        for (Token token : ctx.tokens) {
            if (token.kind() == TokenKind.IDENTIFIER) {
                identifiers.add(token.text());
            }
        }
        double identifierLength = 0;
        for (String identifier : identifiers) {
            identifierLength += identifier.length();
        }
        double functionLines = 0;
        for (FunctionMetrics f : metrics.functions()) {
            functionLines += f.lineCount();
        }
        return new StyleProfile(round(commentRatio),
                functions.isEmpty() ? 0 : round((double) documented / functions.size()),
                ctx.language == Language.JAVA ? 1.0 : params == 0 ? 0 : round((double) typed / params),
                total == 0 ? 1.0 : round((double) dominantCount / total), dominant,
                identifiers.isEmpty() ? 0 : round(identifierLength / identifiers.size()),
                metrics.functions().isEmpty() ? 0 : round(functionLines / metrics.functions().size()));
    }

    private static boolean isDocumented(RuleContext ctx, Node function) {
        if (ctx.language == Language.PYTHON) {
            Node body = function.lastChild();
            return body != null && body.kind() == NodeKind.BLOCK && !body.children().isEmpty()
                    && body.child(0).kind() == NodeKind.EXPRESSION_STATEMENT
                    && body.child(0).firstChild().isStringLiteral();
        }
        for (Token comment : ctx.comments) {
            if (comment.text().startsWith("/**") && comment.endLine() >= function.line() - 1
                    && comment.endLine() <= function.line()) {
                return true;
            }
        }
        return false;
    }

    private static void addStyle(Map<String, Integer> styles, String name) {
        String style = namingStyle(name);
        if (style != null) {
            styles.merge(style, 1, Integer::sum);
        }
    }

    /** 한 단어짜리 소문자 이름(x, value)은 어느 규칙에도 맞으므로 세지 않는다. */
    static String namingStyle(String name) {
        String core = name.replaceAll("^_+|_+$", "");
        if (core.isEmpty() || core.matches("[a-z0-9]+")) {
            return null;
        }
        if (core.matches("[a-z][a-z0-9]*(_[a-z0-9]+)+")) {
            return "snake_case";
        }
        if (core.matches("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")) {
            return "UPPER_CASE";
        }
        if (core.matches("[a-z][a-z0-9]*([A-Z][a-z0-9]*)+")) {
            return "camelCase";
        }
        if (core.matches("[A-Z][a-z0-9]+([A-Z][a-z0-9]*)*")) {
            return "PascalCase";
        }
        return "mixed";
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
