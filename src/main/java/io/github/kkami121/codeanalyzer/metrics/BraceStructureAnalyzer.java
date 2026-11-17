package io.github.kkami121.codeanalyzer.metrics;

import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 중괄호 언어(Java, Decaf)의 함수와 블록 중첩을 찾는다.
 *
 * <p>{@code 이름(...) [throws ...] {} 형태를 메서드/생성자 본문으로 본다. 클래스 본문과 배열 초기화
 * 중괄호는 중첩으로 세지 않는다. 중괄호 없는 {@code if (x) y;}는 중첩으로 세지 않는다.
 */
final class BraceStructureAnalyzer {

    private enum FrameKind { FUNCTION, TYPE, BLOCK, INITIALIZER }

    private static final class Frame {
        final FrameKind kind;
        final CodeStructure.FunctionSpan function;

        Frame(FrameKind kind, CodeStructure.FunctionSpan function) {
            this.kind = kind;
            this.function = function;
        }
    }

    private BraceStructureAnalyzer() {
    }

    /** @param tokens 주석을 뺀 코드 토큰 */
    static CodeStructure analyze(List<Token> tokens) {
        CodeStructure structure = new CodeStructure();
        Deque<Frame> stack = new ArrayDeque<>();

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isOperator("{")) {
                CodeStructure.FunctionSpan function = functionAt(tokens, i);
                if (function != null) {
                    structure.functions.add(function);
                    stack.push(new Frame(FrameKind.FUNCTION, function));
                    continue;
                }
                FrameKind kind = classify(tokens, i, stack.peek());
                stack.push(new Frame(kind, null));
                if (kind == FrameKind.BLOCK) {
                    recordDepth(structure, stack);
                }
            } else if (token.isOperator("}") && !stack.isEmpty()) {
                Frame frame = stack.pop();
                if (frame.kind == FrameKind.FUNCTION) {
                    frame.function.end = i;
                }
            }
        }
        for (CodeStructure.FunctionSpan function : structure.functions) {
            if (function.end < 0) {
                function.end = tokens.size() - 1; // 닫히지 않은 함수
            }
        }
        return structure;
    }

    /** 가장 안쪽 함수 위로 쌓인 BLOCK 수를 그 함수의 중첩 깊이로 기록한다. */
    private static void recordDepth(CodeStructure structure, Deque<Frame> stack) {
        int depth = 0;
        CodeStructure.FunctionSpan owner = null;
        Iterator<Frame> it = stack.iterator(); // 맨 위부터
        while (it.hasNext()) {
            Frame frame = it.next();
            if (frame.kind == FrameKind.FUNCTION) {
                owner = frame.function;
                break;
            }
            if (frame.kind == FrameKind.BLOCK) {
                depth++;
            }
        }
        structure.recordNesting(owner, depth);
    }

    /** i 위치의 '{'가 메서드/생성자 본문이면 그 함수, 아니면 null. */
    private static CodeStructure.FunctionSpan functionAt(List<Token> tokens, int brace) {
        int j = brace - 1;
        // throws A, B.C 건너뛰기
        int k = j;
        while (k >= 0 && (tokens.get(k).kind() == TokenKind.IDENTIFIER || tokens.get(k).isOperator(".")
                || tokens.get(k).isOperator(","))) {
            k--;
        }
        if (k >= 0 && k < j && tokens.get(k).isKeyword("throws")) {
            j = k - 1;
        }
        if (j < 0 || !tokens.get(j).isOperator(")")) {
            return null;
        }
        int open = matchingOpenParen(tokens, j);
        if (open < 1) {
            return null;
        }
        Token name = tokens.get(open - 1);
        if (name.kind() != TokenKind.IDENTIFIER) {
            return null; // if (...) {, while (...) { 등은 키워드
        }
        if (open >= 2) {
            Token before = tokens.get(open - 2);
            if (before.kind() == TokenKind.OPERATOR && !isDeclarationBoundary(before.text())) {
                return null; // a.b() {, x = f() { 등
            }
            if (before.isKeyword("new") || (before.kind() == TokenKind.IDENTIFIER && before.text().equals("record"))) {
                return null; // 익명 클래스, record 선언
            }
        }
        return new CodeStructure.FunctionSpan(name.text(), open - 1);
    }

    /** 반환형/수식어 뒤({@code int f(}, {@code List<X> f(}, {@code int[] f(}) 또는 문장 경계 뒤(생성자). */
    private static boolean isDeclarationBoundary(String operator) {
        return operator.equals(">") || operator.equals(">>") || operator.equals(">>>") || operator.equals("]")
                || operator.equals(";") || operator.equals("}") || operator.equals("{");
    }

    private static int matchingOpenParen(List<Token> tokens, int close) {
        int depth = 0;
        for (int k = close; k >= 0; k--) {
            Token t = tokens.get(k);
            if (t.isOperator(")")) {
                depth++;
            } else if (t.isOperator("(")) {
                if (--depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    private static FrameKind classify(List<Token> tokens, int brace, Frame parent) {
        if (parent != null && parent.kind == FrameKind.INITIALIZER) {
            return FrameKind.INITIALIZER;
        }
        if (brace > 0) {
            Token prev = tokens.get(brace - 1);
            if (prev.isOperator("=") || prev.isOperator("]") || prev.isOperator("(") || prev.isOperator(",")) {
                return FrameKind.INITIALIZER; // int[] a = {1, 2}; new int[] {..}; @A({..})
            }
        }
        // 문장 시작까지 거슬러 올라가며 class/interface/enum/record/new 를 찾는다
        int parenDepth = 0;
        for (int k = brace - 1; k >= 0; k--) {
            Token t = tokens.get(k);
            if (t.isOperator(")")) {
                parenDepth++;
            } else if (t.isOperator("(")) {
                parenDepth--;
            } else if (parenDepth == 0) {
                if (t.isOperator(";") || t.isOperator("{") || t.isOperator("}") || t.isOperator("->")) {
                    break;
                }
                if (t.isKeyword("class") || t.isKeyword("interface") || t.isKeyword("enum") || t.isKeyword("new")
                        || (t.kind() == TokenKind.IDENTIFIER && t.text().equals("record")
                        && k + 1 < brace && tokens.get(k + 1).kind() == TokenKind.IDENTIFIER)) {
                    return FrameKind.TYPE;
                }
            }
        }
        return FrameKind.BLOCK;
    }
}
