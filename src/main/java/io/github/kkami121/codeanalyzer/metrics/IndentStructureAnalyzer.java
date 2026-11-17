package io.github.kkami121.codeanalyzer.metrics;

import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 들여쓰기 언어(Python)의 함수와 블록 중첩을 찾는다.
 * INDENT 직전 논리적 줄이 {@code def}로 시작하면 함수 본문, {@code class}면 클래스 본문, 그 외는 제어 블록이다.
 * 한 줄짜리 함수({@code def f(): return 1})도 함수로 센다.
 */
final class IndentStructureAnalyzer {

    private enum FrameKind { FUNCTION, TYPE, BLOCK }

    private static final class Frame {
        final FrameKind kind;
        final CodeStructure.FunctionSpan function;

        Frame(FrameKind kind, CodeStructure.FunctionSpan function) {
            this.kind = kind;
            this.function = function;
        }
    }

    private IndentStructureAnalyzer() {
    }

    /** @param tokens 주석을 뺀 토큰 (NEWLINE/INDENT/DEDENT 포함) */
    static CodeStructure analyze(List<Token> tokens) {
        CodeStructure structure = new CodeStructure();
        Deque<Frame> stack = new ArrayDeque<>();
        int lineStart = 0;    // 지금 논리적 줄의 첫 토큰
        int headerStart = 0;  // 바로 앞 논리적 줄의 첫 토큰

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            switch (token.kind()) {
                case NEWLINE:
                    headerStart = lineStart;
                    lineStart = i + 1;
                    boolean bodyFollows = i + 1 < tokens.size() && tokens.get(i + 1).kind() == TokenKind.INDENT;
                    if (isDef(tokens, headerStart) && !bodyFollows) {
                        CodeStructure.FunctionSpan oneLiner = newFunction(tokens, headerStart);
                        oneLiner.end = i - 1;
                        structure.functions.add(oneLiner);
                    }
                    break;
                case INDENT:
                    lineStart = i + 1;
                    if (isDef(tokens, headerStart)) {
                        CodeStructure.FunctionSpan function = newFunction(tokens, headerStart);
                        structure.functions.add(function);
                        stack.push(new Frame(FrameKind.FUNCTION, function));
                    } else if (startsWithKeyword(tokens, headerStart, "class")) {
                        stack.push(new Frame(FrameKind.TYPE, null));
                    } else {
                        stack.push(new Frame(FrameKind.BLOCK, null));
                        recordDepth(structure, stack);
                    }
                    break;
                case DEDENT:
                    lineStart = i + 1;
                    if (!stack.isEmpty()) {
                        Frame frame = stack.pop();
                        if (frame.kind == FrameKind.FUNCTION) {
                            frame.function.end = lastCodeIndex(tokens, i);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        for (CodeStructure.FunctionSpan function : structure.functions) {
            if (function.end < 0) {
                function.end = lastCodeIndex(tokens, tokens.size());
            }
        }
        return structure;
    }

    private static void recordDepth(CodeStructure structure, Deque<Frame> stack) {
        int depth = 0;
        CodeStructure.FunctionSpan owner = null;
        Iterator<Frame> it = stack.iterator();
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

    private static boolean isDef(List<Token> tokens, int index) {
        if (index < tokens.size() && tokens.get(index).isKeyword("async")) {
            index++;
        }
        return startsWithKeyword(tokens, index, "def");
    }

    private static boolean startsWithKeyword(List<Token> tokens, int index, String keyword) {
        return index < tokens.size() && tokens.get(index).isKeyword(keyword);
    }

    private static CodeStructure.FunctionSpan newFunction(List<Token> tokens, int start) {
        int def = tokens.get(start).isKeyword("async") ? start + 1 : start;
        String name = def + 1 < tokens.size() && tokens.get(def + 1).kind() == TokenKind.IDENTIFIER
                ? tokens.get(def + 1).text() : "<anonymous>";
        return new CodeStructure.FunctionSpan(name, start);
    }

    /** before 앞쪽에서 레이아웃이 아닌 마지막 토큰 인덱스. */
    private static int lastCodeIndex(List<Token> tokens, int before) {
        int j = before - 1;
        while (j > 0 && tokens.get(j).kind().isLayout()) {
            j--;
        }
        return Math.max(j, 0);
    }
}
