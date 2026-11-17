package io.github.kkami121.codeanalyzer.parser;

import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 구문 트리 노드. 불변이다. 위치는 첫 토큰부터 마지막 토큰까지. */
public final class Node {

    private final NodeKind kind;
    private final String text;
    private final TokenKind literalKind;
    private final List<Node> children;
    private final int line;
    private final int column;
    private final int endLine;
    private final int endColumn;

    public Node(NodeKind kind, String text, TokenKind literalKind, List<Node> children,
                int line, int column, int endLine, int endColumn) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = text == null ? "" : text;
        this.literalKind = literalKind;
        this.children = children == null || children.isEmpty()
                ? Collections.<Node>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(children));
        this.line = line;
        this.column = column;
        this.endLine = endLine;
        this.endColumn = endColumn;
    }

    public NodeKind kind() {
        return kind;
    }

    public String text() {
        return text;
    }

    /** LITERAL 노드의 토큰 종류 (문자열/정수/...). 그 외에는 null. */
    public TokenKind literalKind() {
        return literalKind;
    }

    public List<Node> children() {
        return children;
    }

    public Node child(int index) {
        return children.get(index);
    }

    public Node firstChild() {
        return children.isEmpty() ? null : children.get(0);
    }

    public Node lastChild() {
        return children.isEmpty() ? null : children.get(children.size() - 1);
    }

    /** 주어진 종류의 첫 번째 자식. 없으면 null. */
    public Node childOfKind(NodeKind childKind) {
        for (Node child : children) {
            if (child.kind == childKind) {
                return child;
            }
        }
        return null;
    }

    public List<Node> childrenOfKind(NodeKind childKind) {
        List<Node> result = new ArrayList<>();
        for (Node child : children) {
            if (child.kind == childKind) {
                result.add(child);
            }
        }
        return result;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public int endLine() {
        return endLine;
    }

    public int endColumn() {
        return endColumn;
    }

    public boolean is(NodeKind kind, String text) {
        return this.kind == kind && this.text.equals(text);
    }

    public boolean isStringLiteral() {
        return kind == NodeKind.LITERAL && literalKind == TokenKind.STRING_LITERAL;
    }

    /** 자신을 포함한 모든 자손 중 주어진 종류 (전위 순회 순서). */
    public List<Node> findAll(NodeKind target) {
        List<Node> result = new ArrayList<>();
        collect(this, target, result);
        return result;
    }

    private static void collect(Node node, NodeKind target, List<Node> out) {
        if (node.kind == target) {
            out.add(node);
        }
        for (Node child : node.children) {
            collect(child, target, out);
        }
    }

    /** 자신을 포함한 모든 자손 (전위 순회). */
    public List<Node> descendants() {
        List<Node> result = new ArrayList<>();
        addAll(this, result);
        return result;
    }

    private static void addAll(Node node, List<Node> out) {
        out.add(node);
        for (Node child : node.children) {
            addAll(child, out);
        }
    }

    /**
     * 이름/속성/호출 체인을 점 표기로 바꾼다. 예: {@code os.path.join}, {@code Runtime.getRuntime().exec}.
     * 그 외 식이면 null.
     */
    public String qualifiedName() {
        switch (kind) {
            case NAME:
                return text;
            case ATTRIBUTE: {
                String owner = children.isEmpty() ? null : children.get(0).qualifiedName();
                return owner == null ? null : owner + "." + text;
            }
            case CALL: {
                String callee = children.isEmpty() ? null : children.get(0).qualifiedName();
                return callee == null ? null : callee + "()";
            }
            case NEW:
                return "new " + text + "()";
            default:
                return null;
        }
    }

    /** 디버깅/테스트용 S-식 표현. 예: {@code (CALL (NAME print) (LITERAL 1))}. */
    public String toSExpression() {
        StringBuilder sb = new StringBuilder();
        writeSExpression(sb);
        return sb.toString();
    }

    private void writeSExpression(StringBuilder sb) {
        sb.append('(').append(kind);
        if (!text.isEmpty()) {
            sb.append(' ').append(text.replace("\n", "\\n"));
        }
        for (Node child : children) {
            sb.append(' ');
            child.writeSExpression(sb);
        }
        sb.append(')');
    }

    @Override
    public String toString() {
        return kind + (text.isEmpty() ? "" : " " + text) + " @" + line + ":" + column;
    }
}
