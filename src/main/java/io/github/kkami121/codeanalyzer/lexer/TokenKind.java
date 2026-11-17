package io.github.kkami121.codeanalyzer.lexer;

/** 언어와 무관한 토큰 분류. */
public enum TokenKind {
    KEYWORD,
    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    CHAR_LITERAL,
    BOOLEAN_LITERAL,
    NULL_LITERAL,
    /** 연산자와 구분자 ({@code + == ( ; .} 등). */
    OPERATOR,
    COMMENT,
    /** Python: 논리적 줄의 끝. */
    NEWLINE,
    /** Python: 들여쓰기 증가 (길이 0인 가상 토큰). */
    INDENT,
    /** Python: 들여쓰기 감소 (길이 0인 가상 토큰). */
    DEDENT;

    public boolean isLiteral() {
        switch (this) {
            case INTEGER_LITERAL:
            case FLOAT_LITERAL:
            case STRING_LITERAL:
            case CHAR_LITERAL:
            case BOOLEAN_LITERAL:
            case NULL_LITERAL:
                return true;
            default:
                return false;
        }
    }

    /** NEWLINE / INDENT / DEDENT. */
    public boolean isLayout() {
        return this == NEWLINE || this == INDENT || this == DEDENT;
    }

    /** 주석과 레이아웃을 뺀, 실제 코드를 이루는 토큰인지. */
    public boolean isCode() {
        return this != COMMENT && !isLayout();
    }
}
