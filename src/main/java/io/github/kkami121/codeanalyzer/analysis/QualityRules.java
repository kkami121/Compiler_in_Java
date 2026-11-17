package io.github.kkami121.codeanalyzer.analysis;

import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.SourceFile;
import io.github.kkami121.codeanalyzer.clone.CloneDetector;
import io.github.kkami121.codeanalyzer.clone.ClonePair;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;
import io.github.kkami121.codeanalyzer.metrics.FunctionMetrics;
import io.github.kkami121.codeanalyzer.parser.Node;
import io.github.kkami121.codeanalyzer.parser.NodeKind;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 품질 규칙. 각 메서드가 규칙 하나다. */
final class QualityRules {

    static final int LONG_FUNCTION_LINES = 80;
    static final int VERY_LONG_FUNCTION_LINES = 150;
    static final int MAX_PARAMETERS = 6;
    static final int COMPLEX_FUNCTION = 15;
    static final int VERY_COMPLEX_FUNCTION = 30;
    static final int MAX_NESTING = 4;
    static final int DUPLICATE_MIN_TOKENS = 100;

    private static final Pattern TODO = Pattern.compile("\\b(TODO|FIXME|XXX|HACK)\\b");
    private static final Pattern NOT_IMPLEMENTED = Pattern.compile("(?i)(not\\s*(yet\\s*)?implemented|unimplemented|todo|implement\\s+(me|this))");

    private QualityRules() {
    }

    static void run(RuleContext ctx) {
        emptyCatch(ctx);
        broadException(ctx);
        functionSize(ctx);
        tooManyParameters(ctx);
        mutableDefaultArgument(ctx);
        unusedImport(ctx);
        unreachableCode(ctx);
        todoComment(ctx);
        placeholderImplementation(ctx);
        noneComparison(ctx);
        stringReferenceComparison(ctx);
        duplicateCode(ctx);
    }

    /** catch/except 블록이 비어 있다: 오류가 조용히 사라진다. */
    static void emptyCatch(RuleContext ctx) {
        for (Node node : ctx.nodes) {
            if (node.kind() != NodeKind.CATCH) {
                continue;
            }
            Node block = node.lastChild();
            if (block == null || !isEffectivelyEmpty(block)) {
                continue;
            }
            boolean explained = ctx.hasCommentBetween(node.line(), node.endLine());
            // 특정 예외만 무시하는 것(except ImportError: pass)은 흔한 의도적 패턴이라 LOW
            boolean broad = isBroadCatch(node);
            ctx.report("empty-catch", Category.QUALITY, broad && !explained ? Severity.MEDIUM : Severity.LOW,
                    "CWE-390", node, "Exception is caught and silently ignored"
                            + (explained ? " (a comment explains it)" : "")
                            + "; failures become invisible. Handle, log, or re-raise it.");
        }
    }

    /** 타입 없는 except, 또는 Exception/BaseException/Throwable/RuntimeException 을 잡는다. */
    static boolean isBroadCatch(Node catchNode) {
        Node type = catchNode.firstChild() == catchNode.lastChild() ? null : catchNode.firstChild();
        if (type == null) {
            return true;
        }
        String name = type.kind() == NodeKind.TYPE ? type.text() : type.qualifiedName();
        if (name == null) {
            return false;
        }
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple.equals("Exception") || simple.equals("BaseException") || simple.equals("Throwable")
                || simple.equals("RuntimeException");
    }

    private static boolean isEffectivelyEmpty(Node block) {
        for (Node statement : block.children()) {
            if (statement.kind() == NodeKind.PASS) {
                continue;
            }
            if (statement.kind() == NodeKind.EXPRESSION_STATEMENT && RuleContext.isLiteral(statement.firstChild(), "...")) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 모든 예외를 잡는다: 시스템 종료 신호나 프로그래밍 오류까지 삼킨다. */
    static void broadException(RuleContext ctx) {
        for (Node node : ctx.nodes) {
            if (node.kind() != NodeKind.CATCH) {
                continue;
            }
            Node type = node.firstChild() == node.lastChild() ? null : node.firstChild();
            if (ctx.language == Language.PYTHON) {
                if (type == null) {
                    ctx.report("broad-exception", Category.QUALITY, Severity.MEDIUM, "CWE-396", node,
                            "Bare 'except:' also catches KeyboardInterrupt and SystemExit; catch specific exceptions.");
                } else if (type.is(NodeKind.NAME, "BaseException")) {
                    ctx.report("broad-exception", Category.QUALITY, Severity.MEDIUM, "CWE-396", node,
                            "Catching BaseException also catches KeyboardInterrupt and SystemExit.");
                }
            } else if (type != null && (type.text().equals("Throwable") || type.text().equals("java.lang.Throwable"))) {
                ctx.report("broad-exception", Category.QUALITY, Severity.MEDIUM, "CWE-396", node,
                        "Catching Throwable also catches Errors such as OutOfMemoryError; catch specific exceptions.");
            }
        }
    }

    /** 너무 길거나 복잡하거나 깊이 중첩된 함수. 지표는 MetricsCalculator 결과를 쓴다. */
    static void functionSize(RuleContext ctx) {
        for (FunctionMetrics f : ctx.metrics.functions()) {
            if (f.lineCount() > LONG_FUNCTION_LINES) {
                ctx.report("long-function", Category.QUALITY,
                        f.lineCount() > VERY_LONG_FUNCTION_LINES ? Severity.MEDIUM : Severity.LOW, null,
                        f.startLine(), 1, "Function '" + f.name() + "' is " + f.lineCount()
                                + " lines long; consider splitting it (limit " + LONG_FUNCTION_LINES + ").");
            }
            if (f.cyclomaticComplexity() > COMPLEX_FUNCTION) {
                ctx.report("high-complexity", Category.QUALITY,
                        f.cyclomaticComplexity() > VERY_COMPLEX_FUNCTION ? Severity.HIGH : Severity.MEDIUM, null,
                        f.startLine(), 1, "Function '" + f.name() + "' has cyclomatic complexity "
                                + f.cyclomaticComplexity() + " (limit " + COMPLEX_FUNCTION + "); it is hard to test fully.");
            }
            if (f.maxNestingDepth() > MAX_NESTING) {
                ctx.report("deep-nesting", Category.QUALITY, Severity.MEDIUM, null, f.startLine(), 1,
                        "Function '" + f.name() + "' nests blocks " + f.maxNestingDepth()
                                + " levels deep (limit " + MAX_NESTING + "); use early returns or extract helpers.");
            }
        }
    }

    static void tooManyParameters(RuleContext ctx) {
        for (Node function : ctx.nodes) {
            if (function.kind() != NodeKind.FUNCTION) {
                continue;
            }
            int count = 0;
            for (Node param : function.childrenOfKind(NodeKind.PARAMETER)) {
                if (!param.text().equals("self") && !param.text().equals("cls")) {
                    count++;
                }
            }
            if (count > MAX_PARAMETERS) {
                ctx.report("too-many-parameters", Category.QUALITY, Severity.LOW, null, function,
                        "Function '" + function.text() + "' takes " + count + " parameters (limit "
                                + MAX_PARAMETERS + "); group related values into an object.");
            }
        }
    }

    /** Python: def f(x=[]) — 기본값 객체가 호출 사이에 공유된다. */
    static void mutableDefaultArgument(RuleContext ctx) {
        if (ctx.language != Language.PYTHON) {
            return;
        }
        for (Node param : ctx.nodes) {
            if (param.kind() != NodeKind.PARAMETER) {
                continue;
            }
            Node value = param.lastChild();
            if (value == null || value.kind() == NodeKind.TYPE) {
                continue;
            }
            boolean mutable = (value.kind() == NodeKind.COLLECTION && !value.text().equals("("))
                    || value.kind() == NodeKind.COMPREHENSION;
            if (value.kind() == NodeKind.CALL) {
                String name = ctx.callName(value);
                mutable = "list".equals(name) || "dict".equals(name) || "set".equals(name)
                        || "collections.defaultdict".equals(name) || "collections.OrderedDict".equals(name);
            }
            if (mutable) {
                ctx.report("mutable-default-argument", Category.QUALITY, Severity.MEDIUM, null, param,
                        "Parameter '" + param.text() + "' has a mutable default value that is shared between calls; "
                                + "use None and create it inside the function.");
            }
        }
    }

    static void unusedImport(RuleContext ctx) {
        String fileName = ctx.file.name().replace('\\', '/');
        if (fileName.endsWith("__init__.py")) {
            return; // 재수출용 import가 많다
        }
        for (Node imp : ctx.nodes) {
            if (imp.kind() != NodeKind.IMPORT || imp.text().equals("__future__")) {
                continue;
            }
            for (Node alias : imp.childrenOfKind(NodeKind.VARIABLE)) {
                String name = alias.text();
                if (ctx.identifierUses(name, imp.line(), imp.endLine()) > 0 || mentionedInStrings(ctx, name)
                        || mentionedInComments(ctx, name)) {
                    continue;
                }
                ctx.report("unused-import", Category.QUALITY, Severity.LOW, null, alias,
                        "Import '" + name + "' is never used.");
            }
        }
    }

    /** Python __all__ = ['name'] 같은 재수출. */
    private static boolean mentionedInStrings(RuleContext ctx, String name) {
        if (ctx.language != Language.PYTHON) {
            return false;
        }
        for (Token token : ctx.tokens) {
            if (token.kind() == TokenKind.STRING_LITERAL
                    && (token.text().contains("'" + name + "'") || token.text().contains("\"" + name + "\""))) {
                return true;
            }
        }
        return false;
    }

    /** Java: Javadoc의 {@link Name} 참조. */
    private static boolean mentionedInComments(RuleContext ctx, String name) {
        if (ctx.language != Language.JAVA) {
            return false;
        }
        Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        for (Token comment : ctx.comments) {
            if (comment.text().startsWith("/**") && word.matcher(comment.text()).find()) {
                return true;
            }
        }
        return false;
    }

    /** return/throw/break/continue 뒤에 같은 블록의 문장이 더 있다. */
    static void unreachableCode(RuleContext ctx) {
        for (Node block : ctx.nodes) {
            if (block.kind() != NodeKind.BLOCK && block.kind() != NodeKind.CASE) {
                continue;
            }
            List<Node> statements = block.children();
            for (int i = 0; i < statements.size() - 1; i++) {
                NodeKind kind = statements.get(i).kind();
                if (kind == NodeKind.RETURN || kind == NodeKind.THROW || kind == NodeKind.BREAK
                        || kind == NodeKind.CONTINUE) {
                    Node next = statements.get(i + 1);
                    if (next.kind() == NodeKind.PASS || next.kind() == NodeKind.ERROR || next.kind() == NodeKind.CLASS
                            || next.kind() == NodeKind.FUNCTION || isYield(next)) {
                        continue; // 'return' 뒤의 'yield'는 제너레이터로 만드는 관용구
                    }
                    ctx.report("unreachable-code", Category.QUALITY, Severity.MEDIUM, "CWE-561", next,
                            "This code can never run: it follows a " + kind.name().toLowerCase(Locale.ROOT)
                                    + " statement in the same block.");
                    break;
                }
            }
        }
    }

    private static boolean isYield(Node statement) {
        Node value = statement.kind() == NodeKind.EXPRESSION_STATEMENT ? statement.firstChild() : null;
        return value != null && value.kind() == NodeKind.UNARY && value.text().startsWith("yield");
    }

    static void todoComment(RuleContext ctx) {
        for (Token comment : ctx.comments) {
            Matcher m = TODO.matcher(comment.text());
            if (m.find()) {
                ctx.report("todo-comment", Category.QUALITY, Severity.INFO, null, comment.line(), comment.column(),
                        m.group(1) + " comment left in code: " + abbreviate(comment.text().trim()));
            }
        }
    }

    /** 본문이 pass / ... / NotImplementedError 뿐인 함수: 생성된 코드가 미완성일 수 있다. */
    static void placeholderImplementation(RuleContext ctx) {
        for (Node function : ctx.nodes) {
            if (function.kind() != NodeKind.FUNCTION) {
                continue;
            }
            Node body = function.lastChild();
            if (body == null || body.kind() != NodeKind.BLOCK || isAbstractOrOverload(function)) {
                continue;
            }
            List<Node> statements = body.children();
            int first = 0;
            if (!statements.isEmpty() && isDocstring(statements.get(0))) {
                first = 1; // 독스트링
            }
            if (statements.size() - first != 1) {
                if (statements.size() == first && ctx.language == Language.JAVA
                        && ctx.hasCommentBetween(body.line(), body.endLine())
                        && commentInRangeMatches(ctx, body, NOT_IMPLEMENTED)) {
                    report(ctx, function);
                }
                continue;
            }
            Node only = statements.get(first);
            boolean placeholder;
            if (ctx.language == Language.PYTHON) {
                boolean stub = only.kind() == NodeKind.PASS
                        || (only.kind() == NodeKind.EXPRESSION_STATEMENT && RuleContext.isLiteral(only.firstChild(), "..."))
                        || (only.kind() == NodeKind.THROW && raises(only, "NotImplementedError"));
                // 메서드의 pass/NotImplementedError는 추상 메서드나 훅인 경우가 많다:
                // TODO/미구현 표시가 있거나 모듈 수준 함수일 때만 보고한다.
                boolean marked = commentInRangeMatches(ctx, function, TODO)
                        || commentInRangeMatches(ctx, function, NOT_IMPLEMENTED) || raisesWithMessage(only);
                placeholder = stub && (marked || !isMethod(ctx, function));
                if (placeholder && !marked) {
                    // 표시 없는 모듈 수준 스텁은 테스트 픽스처일 수도 있어 INFO
                    ctx.report("placeholder-implementation", Category.QUALITY, Severity.INFO, null, function,
                            "Function '" + function.text() + "' has an empty body; the implementation may be incomplete.");
                    continue;
                }
            } else {
                placeholder = only.kind() == NodeKind.THROW && only.firstChild() != null
                        && only.firstChild().kind() == NodeKind.NEW
                        && only.firstChild().text().endsWith("UnsupportedOperationException")
                        && messageMatches(only.firstChild(), NOT_IMPLEMENTED);
                placeholder |= only.kind() == NodeKind.RETURN && commentInRangeMatches(ctx, body, TODO);
            }
            if (placeholder) {
                report(ctx, function);
            }
        }
    }

    private static void report(RuleContext ctx, Node function) {
        ctx.report("placeholder-implementation", Category.QUALITY, Severity.LOW, null, function,
                "Function '" + function.text() + "' has a placeholder body; the implementation may be incomplete.");
    }

    private static boolean isDocstring(Node statement) {
        return statement.kind() == NodeKind.EXPRESSION_STATEMENT && statement.firstChild().isStringLiteral();
    }

    private static boolean isAbstractOrOverload(Node function) {
        for (Node decorator : function.childrenOfKind(NodeKind.DECORATOR)) {
            String name = decorator.text();
            if (name.endsWith("abstractmethod") || name.endsWith("overload") || name.equals("Override")
                    || name.endsWith("abstractproperty")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMethod(RuleContext ctx, Node function) {
        Node parent = ctx.parent(function);
        Node cls = parent == null ? null : ctx.parent(parent);
        return cls != null && cls.kind() == NodeKind.CLASS;
    }

    /** raise NotImplementedError("TODO: ...") 처럼 메시지가 미구현을 말한다. */
    private static boolean raisesWithMessage(Node statement) {
        if (statement.kind() != NodeKind.THROW || statement.firstChild() == null
                || statement.firstChild().kind() != NodeKind.CALL) {
            return false;
        }
        return messageMatches(statement.firstChild(), NOT_IMPLEMENTED) || messageMatches(statement.firstChild(), TODO);
    }

    private static boolean raises(Node raise, String exceptionName) {
        Node exception = raise.firstChild();
        if (exception == null) {
            return false;
        }
        if (exception.kind() == NodeKind.CALL) {
            exception = exception.firstChild();
        }
        String name = exception.qualifiedName();
        return name != null && name.endsWith(exceptionName);
    }

    private static boolean messageMatches(Node newNode, Pattern pattern) {
        for (Node arg : newNode.children()) {
            String value = RuleContext.stringValue(arg);
            if (value != null && pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean commentInRangeMatches(RuleContext ctx, Node range, Pattern pattern) {
        for (Token comment : ctx.comments) {
            if (comment.line() >= range.line() && comment.line() <= range.endLine()
                    && pattern.matcher(comment.text()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Python: x == None 은 x is None 으로. */
    static void noneComparison(RuleContext ctx) {
        if (ctx.language != Language.PYTHON) {
            return;
        }
        for (Node node : ctx.nodes) {
            if (node.kind() == NodeKind.BINARY && (node.text().equals("==") || node.text().equals("!="))
                    && (isNone(node.child(0)) || isNone(node.child(1)))) {
                ctx.report("none-comparison", Category.QUALITY, Severity.LOW, null, node,
                        "Compare with None using 'is' / 'is not' instead of '" + node.text() + "'.");
            }
        }
    }

    private static boolean isNone(Node node) {
        return node.kind() == NodeKind.LITERAL && node.literalKind() == TokenKind.NULL_LITERAL;
    }

    /** Java: 문자열을 == 로 비교한다 (내용이 아니라 참조를 비교). */
    static void stringReferenceComparison(RuleContext ctx) {
        if (ctx.language != Language.JAVA) {
            return;
        }
        for (Node node : ctx.nodes) {
            if (node.kind() == NodeKind.BINARY && (node.text().equals("==") || node.text().equals("!="))
                    && (node.child(0).isStringLiteral() || node.child(1).isStringLiteral())) {
                ctx.report("string-reference-comparison", Category.QUALITY, Severity.MEDIUM, "CWE-597", node,
                        "Strings compared with '" + node.text() + "' compare references, not contents; use equals().");
            }
        }
    }

    /** 같은 파일 안의 큰 복사-붙여넣기 구간. */
    static void duplicateCode(RuleContext ctx) {
        SourceFile single = new SourceFile(ctx.file.name(), ctx.language, ctx.file.content());
        List<ClonePair> clones = new CloneDetector(DUPLICATE_MIN_TOKENS).detect(Collections.singletonList(single));
        BitSet covered = new BitSet();
        for (ClonePair pair : clones) {
            // 이미 보고한 구간과 겹치는 쌍(같은 코드가 3번 이상 반복될 때 생김)은 건너뛴다
            int from = pair.second().startLine();
            int to = pair.second().endLine();
            if (!covered.get(from, to + 1).isEmpty()) {
                continue;
            }
            covered.set(from, to + 1);
            ctx.report("duplicate-code", Category.QUALITY, Severity.LOW, null, pair.second().startLine(),
                    pair.second().startColumn(), "Lines " + pair.second().startLine() + "-" + pair.second().endLine()
                            + " duplicate lines " + pair.first().startLine() + "-" + pair.first().endLine()
                            + " (" + pair.tokenCount() + " tokens, " + pair.type() + "); extract a shared function.");
        }
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
    }
}
