package io.github.kkami121.codeanalyzer.lexer;

import io.github.kkami121.codeanalyzer.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 렉서 구현을 위한 문자 단위 읽기 도구. 한 번의 분석에만 쓰고 버린다. */
abstract class SourceScanner {

    protected final String source;
    protected final List<Token> tokens = new ArrayList<>();
    protected final List<LexError> errors = new ArrayList<>();

    protected int pos = 0;
    protected int line = 1;
    protected int column = 1;      // 다음에 읽을 문자의 위치

    private int lastLine = 1;      // 마지막으로 읽은 문자의 위치
    private int lastColumn = 0;

    private int markPos;
    private int markLine;
    private int markColumn;

    SourceScanner(String source) {
        this.source = Objects.requireNonNull(source, "source");
        if (source.startsWith("﻿")) {
            pos = 1; // UTF-8 BOM은 건너뛴다 (열 번호에는 세지 않음)
        }
    }

    /** 현재 위치의 유니코드 코드 포인트 (보조 평면 문자는 두 char). 끝이면 0. */
    final int peekCodePoint() {
        return atEnd() ? 0 : source.codePointAt(pos);
    }

    /** 코드 포인트 하나를 읽는다. */
    final void advanceCodePoint() {
        advance(Character.charCount(peekCodePoint()));
    }

    final boolean atEnd() {
        return pos >= source.length();
    }

    /** 범위를 벗어나면 '\0'. */
    final char peek(int offset) {
        int index = pos + offset;
        return index >= 0 && index < source.length() ? source.charAt(index) : '\0';
    }

    final char peek() {
        return peek(0);
    }

    final boolean lookingAt(String text) {
        return source.startsWith(text, pos);
    }

    final char advance() {
        char c = source.charAt(pos++);
        lastLine = line;
        lastColumn = column;
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    final void advance(int count) {
        for (int i = 0; i < count && !atEnd(); i++) {
            advance();
        }
    }

    /** 지금 위치를 토큰 시작으로 표시한다. */
    final void mark() {
        markPos = pos;
        markLine = line;
        markColumn = column;
    }

    final String markedText() {
        return source.substring(markPos, pos);
    }

    final int markLine() {
        return markLine;
    }

    final int markColumn() {
        return markColumn;
    }

    /** {@link #mark()} 이후 읽은 문자들로 토큰을 만든다. */
    final Token emit(TokenKind kind) {
        Token token = new Token(kind, markedText(), markLine, markColumn, lastLine, lastColumn);
        tokens.add(token);
        return token;
    }

    /** 현재 위치에 길이 0인 가상 토큰을 만든다. */
    final void emitVirtual(TokenKind kind) {
        tokens.add(new Token(kind, "", line, column, line, column));
    }

    final void error(int errorLine, int errorColumn, String message) {
        errors.add(new LexError(errorLine, errorColumn, message));
    }

    final void errorAtMark(String message) {
        error(markLine, markColumn, message);
    }

    /** 가장 긴 연산자부터 맞춰 본다. {@code operators}는 길이 내림차순이어야 한다. */
    final boolean scanOperator(String[] operators) {
        for (String operator : operators) {
            if (lookingAt(operator)) {
                mark();
                advance(operator.length());
                emit(TokenKind.OPERATOR);
                return true;
            }
        }
        return false;
    }

    /** {@code //} 또는 {@code /* *}{@code /} 주석을 읽는다. 주석이 아니면 false. */
    final boolean scanCStyleComment(String unterminatedMessage) {
        if (lookingAt("//")) {
            mark();
            while (!atEnd() && peek() != '\n' && peek() != '\r') {
                advance();
            }
            emit(TokenKind.COMMENT);
            return true;
        }
        if (lookingAt("/*")) {
            mark();
            advance(2);
            while (!atEnd() && !lookingAt("*/")) {
                advance();
            }
            if (atEnd()) {
                error(line, column, unterminatedMessage);
            } else {
                advance(2);
            }
            emit(TokenKind.COMMENT);
            return true;
        }
        return false;
    }

    final LexResult result(Language language) {
        return new LexResult(language, tokens, errors);
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == '\r';
    }
}
