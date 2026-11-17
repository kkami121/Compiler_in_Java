package io.github.kkami121.codeanalyzer.lexer;

/**
 * 소스 문자열을 토큰으로 나누는 어휘분석기.
 * 구현체는 상태를 갖지 않으므로 여러 스레드에서 같이 써도 된다.
 */
public interface Lexer {

    LexResult tokenize(String source);
}
