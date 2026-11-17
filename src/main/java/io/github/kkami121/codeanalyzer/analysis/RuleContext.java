package io.github.kkami121.codeanalyzer.analysis;

import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.SourceFile;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;
import io.github.kkami121.codeanalyzer.metrics.CodeMetrics;
import io.github.kkami121.codeanalyzer.parser.Node;
import io.github.kkami121.codeanalyzer.parser.NodeKind;
import io.github.kkami121.codeanalyzer.parser.SyntaxTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 규칙이 공유하는 분석 정보: 트리, 부모 관계, import 별칭, 간단한 데이터 흐름. */
final class RuleContext {

    final SourceFile file;
    final Language language;
    final SyntaxTree tree;
    final CodeMetrics metrics;
    final List<Token> tokens;
    final List<Token> comments;
    final List<Node> nodes;
    final List<Finding> findings = new ArrayList<>();

    private final Map<Node, Node> parents = new IdentityHashMap<>();
    /** 바인딩 이름 → 전체 이름. Python: np → numpy, run → subprocess.run. Java: List → java.util.List. */
    private final Map<String, String> aliases = new HashMap<>();
    /** 범위(FUNCTION 또는 MODULE) → 변수 이름 → 대입된 값들. */
    private final Map<Node, Map<String, List<Node>>> assignments = new IdentityHashMap<>();

    RuleContext(SourceFile file, SyntaxTree tree, CodeMetrics metrics) {
        this.file = file;
        this.language = file.language();
        this.tree = tree;
        this.metrics = metrics;
        this.tokens = tree.lexResult().tokens();
        this.comments = tree.lexResult().comments();
        this.nodes = tree.root().descendants();
        indexParents(tree.root());
        for (Node node : nodes) {
            if (node.kind() == NodeKind.IMPORT) {
                for (Node alias : node.childrenOfKind(NodeKind.VARIABLE)) {
                    String bound = alias.text();
                    String original = alias.firstChild().text();
                    String module = node.text();
                    String full;
                    if (module.isEmpty()) {
                        // Python: import a.b → a, import a.b as c → a.b / Java: import java.util.List → 전체 이름
                        full = original.startsWith(bound + ".") ? bound : original;
                    } else {
                        // Python: from x.y import z as w → x.y.z
                        full = module.endsWith(".") ? module + original : module + "." + original;
                    }
                    aliases.put(bound, full);
                }
            } else if (node.kind() == NodeKind.ASSIGN && node.children().size() >= 2) {
                Node value = node.lastChild();
                for (int i = 0; i < node.children().size() - 1; i++) {
                    Node target = node.child(i);
                    if (target.kind() == NodeKind.NAME && target != value) {
                        record(scopeOf(node), target.text(), value);
                    }
                }
            } else if (node.kind() == NodeKind.VARIABLE && node.lastChild() != null
                    && node.lastChild().kind() != NodeKind.TYPE) {
                record(scopeOf(node), node.text(), node.lastChild());
            }
        }
    }

    private void indexParents(Node node) {
        for (Node child : node.children()) {
            parents.put(child, node);
            indexParents(child);
        }
    }

    private void record(Node scope, String name, Node value) {
        Map<String, List<Node>> byName = assignments.get(scope);
        if (byName == null) {
            byName = new HashMap<>();
            assignments.put(scope, byName);
        }
        List<Node> values = byName.get(name);
        if (values == null) {
            values = new ArrayList<>();
            byName.put(name, values);
        }
        values.add(value);
    }

    Node parent(Node node) {
        return parents.get(node);
    }

    /** 가장 가까운 FUNCTION/LAMBDA 또는 MODULE. */
    Node scopeOf(Node node) {
        Node current = parents.get(node);
        while (current != null && current.kind() != NodeKind.FUNCTION && current.kind() != NodeKind.MODULE) {
            current = parents.get(current);
        }
        return current == null ? tree.root() : current;
    }

    /** 가장 가까운 FUNCTION. 없으면 null. */
    Node enclosingFunction(Node node) {
        Node current = parents.get(node);
        while (current != null && current.kind() != NodeKind.FUNCTION) {
            current = parents.get(current);
        }
        return current;
    }

    /** 같은 범위(없으면 모듈)에서 이 이름에 대입된 값들. */
    List<Node> valuesOf(Node nameNode) {
        List<Node> result = new ArrayList<>();
        Node scope = scopeOf(nameNode);
        while (scope != null) {
            Map<String, List<Node>> byName = assignments.get(scope);
            if (byName != null && byName.containsKey(nameNode.text())) {
                result.addAll(byName.get(nameNode.text()));
                return result;
            }
            if (scope == tree.root()) {
                break;
            }
            scope = scopeOf(scope);
        }
        return result;
    }

    /** import 별칭을 풀어 쓴 이름. 예: {@code sp.run} → {@code subprocess.run}. */
    String resolve(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        int dot = qualifiedName.indexOf('.');
        String head = dot < 0 ? qualifiedName : qualifiedName.substring(0, dot);
        String full = aliases.get(head);
        if (full == null) {
            return qualifiedName;
        }
        return dot < 0 ? full : full + qualifiedName.substring(dot);
    }

    /** CALL 노드의 대상 이름 (별칭 해석). 이름으로 표현할 수 없으면 null. */
    String callName(Node call) {
        return resolve(call.firstChild().qualifiedName());
    }

    /** CALL의 마지막 이름 조각 (메서드 이름). */
    static String methodName(Node call) {
        Node callee = call.firstChild();
        if (callee.kind() == NodeKind.ATTRIBUTE || callee.kind() == NodeKind.NAME) {
            return callee.text();
        }
        return null;
    }

    static List<Node> positionalArguments(Node call) {
        List<Node> result = new ArrayList<>();
        for (int i = 1; i < call.children().size(); i++) {
            Node arg = call.child(i);
            if (arg.kind() != NodeKind.ARGUMENT && !(arg.kind() == NodeKind.UNARY && arg.text().equals("**"))) {
                result.add(arg);
            }
        }
        return result;
    }

    static Node keywordArgument(Node call, String name) {
        for (int i = 1; i < call.children().size(); i++) {
            Node arg = call.child(i);
            if (arg.kind() == NodeKind.ARGUMENT && arg.text().equals(name)) {
                return arg.firstChild();
            }
        }
        return null;
    }

    /** 첫 위치 인자 또는 지정한 키워드 인자. */
    static Node argument(Node call, int index, String keyword) {
        List<Node> positional = positionalArguments(call);
        if (index < positional.size()) {
            return positional.get(index);
        }
        return keyword == null ? null : keywordArgument(call, keyword);
    }

    static boolean isLiteral(Node node, String text) {
        return node != null && node.kind() == NodeKind.LITERAL && node.text().equals(text);
    }

    /** 문자열 리터럴의 따옴표와 접두사를 뗀 내용. 문자열이 아니면 null. */
    static String stringValue(Node node) {
        if (node == null || !node.isStringLiteral()) {
            return null;
        }
        String text = node.text();
        int quote = 0;
        while (quote < text.length() && text.charAt(quote) != '"' && text.charAt(quote) != '\'') {
            quote++;
        }
        if (quote >= text.length()) {
            return text;
        }
        char q = text.charAt(quote);
        String triple = new String(new char[]{q, q, q});
        int width = text.startsWith(triple, quote) && text.length() - quote >= 6 ? 3 : 1;
        int begin = quote + width;
        int end = text.length() - width;
        return end >= begin ? text.substring(begin, end) : "";
    }

    static boolean isFormattedString(Node node) {
        if (node == null || !node.isStringLiteral()) {
            return false;
        }
        String text = node.text();
        int quote = 0;
        while (quote < text.length() && Character.isLetter(text.charAt(quote))) {
            quote++;
        }
        String prefix = text.substring(0, quote).toLowerCase();
        return (prefix.contains("f") || prefix.contains("t")) && text.indexOf('{') >= 0;
    }

    /**
     * 실행 시점에 문자열을 조립하는 식인지: 연결(+), 포맷팅(%, format, f-string, String.format),
     * 또는 같은 범위에서 그런 값이 대입된 변수.
     */
    boolean isBuiltString(Node node) {
        return isBuiltString(node, 0);
    }

    private boolean isBuiltString(Node node, int depth) {
        if (node == null || depth > 4) {
            return false;
        }
        switch (node.kind()) {
            case LITERAL:
                return isFormattedString(node);
            case BINARY:
                if (node.text().equals("+")) {
                    Node left = node.child(0);
                    Node right = node.child(1);
                    boolean stringy = left.isStringLiteral() || right.isStringLiteral()
                            || isBuiltString(left, depth + 1) || isBuiltString(right, depth + 1);
                    boolean dynamic = !(left.kind() == NodeKind.LITERAL && right.kind() == NodeKind.LITERAL);
                    return stringy && dynamic;
                }
                return node.text().equals("%") && node.child(0).isStringLiteral();
            case CALL: {
                Node callee = node.firstChild();
                if (callee.kind() == NodeKind.ATTRIBUTE && (callee.text().equals("format")
                        || callee.text().equals("formatted") || callee.text().equals("concat"))) {
                    return true;
                }
                String name = callName(node);
                return "String.format".equals(name) || "MessageFormat.format".equals(name);
            }
            case NAME:
                for (Node value : valuesOf(node)) {
                    if (value != node && isBuiltString(value, depth + 1)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    /** 문자열 리터럴(또는 리터럴만 담은 목록)이 아닌 값. */
    static boolean isNonConstant(Node node) {
        if (node == null) {
            return false;
        }
        if (node.kind() == NodeKind.LITERAL) {
            return isFormattedString(node);
        }
        if (node.kind() == NodeKind.COLLECTION) {
            for (Node child : node.children()) {
                if (isNonConstant(child)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** 주석이 [fromLine, toLine] 안에 있는지. */
    boolean hasCommentBetween(int fromLine, int toLine) {
        for (Token comment : comments) {
            if (comment.line() >= fromLine && comment.line() <= toLine) {
                return true;
            }
        }
        return false;
    }

    /** IDENTIFIER 토큰 중 주어진 이름이 [excludeFrom, excludeTo] 줄 밖에서 쓰인 횟수. */
    int identifierUses(String name, int excludeFrom, int excludeTo) {
        int count = 0;
        for (Token token : tokens) {
            if (token.kind() == TokenKind.IDENTIFIER && token.text().equals(name)
                    && (token.line() < excludeFrom || token.line() > excludeTo)) {
                count++;
            }
        }
        return count;
    }

    void report(String ruleId, Category category, Severity severity, String cwe, Node at, String message) {
        findings.add(new Finding(ruleId, category, severity, cwe, message, at.line(), at.column(), at.endLine()));
    }

    void report(String ruleId, Category category, Severity severity, String cwe, int line, int column, String message) {
        findings.add(new Finding(ruleId, category, severity, cwe, message, line, column, line));
    }
}
