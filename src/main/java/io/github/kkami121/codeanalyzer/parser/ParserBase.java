package io.github.kkami121.codeanalyzer.parser;

import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 재귀 하강 파서 공통 도구: 토큰 커서, 오류 처리, 노드 생성. 한 번의 분석에만 쓴다. */
abstract class ParserBase {

    /** 병적인 입력에서 스택이 넘치지 않도록 재귀 깊이를 제한한다. */
    private static final int MAX_DEPTH = 400;

    /** 내부용: 문장 단위 복구를 위해 던지는 예외. */
    static final class ParseFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Token token;

        ParseFailure(String message, Token token) {
            super(message, null, false, false);
            this.token = token;
        }
    }

    protected final List<Token> tokens;
    protected final List<ParseError> errors = new ArrayList<>();
    protected int pos = 0;
    private final Token eof;
    private int depth = 0;

    ParserBase(List<Token> source) {
        List<Token> filtered = new ArrayList<>();
        for (Token token : source) {
            if (token.kind() != TokenKind.COMMENT) {
                filtered.add(token);
            }
        }
        this.tokens = filtered;
        Token last = filtered.isEmpty() ? null : filtered.get(filtered.size() - 1);
        int line = last == null ? 1 : last.endLine();
        int column = last == null ? 1 : last.endColumn() + 1;
        this.eof = new Token(TokenKind.NEWLINE, "<EOF>", line, column, line, column);
    }

    final boolean atEnd() {
        return pos >= tokens.size();
    }

    final Token peek() {
        return peek(0);
    }

    final Token peek(int offset) {
        int index = pos + offset;
        return index < tokens.size() ? tokens.get(index) : eof;
    }

    final Token previous() {
        return pos > 0 ? tokens.get(pos - 1) : peek();
    }

    final Token advance() {
        Token token = peek();
        if (!atEnd()) {
            pos++;
        }
        return token;
    }

    /** 연산자 또는 키워드이면서 텍스트가 같은지. */
    final boolean check(String text) {
        return checkAt(0, text);
    }

    final boolean checkAt(int offset, String text) {
        Token t = peek(offset);
        return (t.kind() == TokenKind.OPERATOR || t.kind() == TokenKind.KEYWORD) && t.text().equals(text)
                && offset + pos < tokens.size();
    }

    final boolean checkKind(TokenKind kind) {
        return !atEnd() && peek().kind() == kind;
    }

    final boolean checkIdentifier(String text) {
        return checkKind(TokenKind.IDENTIFIER) && peek().text().equals(text);
    }

    final boolean match(String text) {
        if (check(text)) {
            advance();
            return true;
        }
        return false;
    }

    final Token expect(String text) {
        if (!check(text)) {
            throw fail("Expected '" + text + "'");
        }
        return advance();
    }

    final Token expectIdentifier() {
        if (!checkKind(TokenKind.IDENTIFIER)) {
            throw fail("Expected identifier");
        }
        return advance();
    }

    final ParseFailure fail(String message) {
        Token t = peek();
        String found = atEnd() ? "end of input" : "'" + abbreviate(t.text()) + "'";
        return new ParseFailure(message + " but found " + found, t);
    }

    final void recordError(ParseFailure failure) {
        Token t = failure.token;
        errors.add(new ParseError(t.line(), t.column(), failure.getMessage()));
    }

    final void enter() {
        if (++depth > MAX_DEPTH) {
            depth = 0;
            throw fail("Nesting too deep");
        }
    }

    final void exit() {
        depth--;
    }

    /** start 토큰부터 마지막으로 읽은 토큰까지를 범위로 하는 노드. */
    final Node node(NodeKind kind, String text, Token start, List<Node> children) {
        Token end = previous();
        if (end.line() < start.line() || (end.line() == start.line() && end.endColumn() < start.column())) {
            end = start;
        }
        return new Node(kind, text, null, children, start.line(), start.column(), end.endLine(), end.endColumn());
    }

    final Node node(NodeKind kind, String text, Token start, Node... children) {
        List<Node> list = new ArrayList<>();
        for (Node child : children) {
            if (child != null) {
                list.add(child);
            }
        }
        return node(kind, text, start, list);
    }

    final Node leaf(NodeKind kind, Token token) {
        return new Node(kind, token.text(), kind == NodeKind.LITERAL ? token.kind() : null,
                Collections.<Node>emptyList(), token.line(), token.column(), token.endLine(), token.endColumn());
    }

    final Node errorNode(ParseFailure failure, Token start) {
        return node(NodeKind.ERROR, failure.getMessage(), start, Collections.<Node>emptyList());
    }

    static boolean isLiteral(Token token) {
        return token.kind().isLiteral();
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace("\n", "\\n");
        return oneLine.length() > 20 ? oneLine.substring(0, 20) + "..." : oneLine;
    }
}
