package io.github.kkami121.codeanalyzer.lexer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class LexerTestSupport {

    private LexerTestSupport() {
    }

    /** 토큰을 "KIND text" 문자열 목록으로. 레이아웃 토큰은 KIND만. */
    static List<String> describe(LexResult result) {
        List<String> out = new ArrayList<>();
        for (Token token : result.tokens()) {
            out.add(token.kind().isLayout() ? token.kind().name() : token.kind().name() + " " + token.text());
        }
        return out;
    }

    static List<String> list(String... items) {
        return Arrays.asList(items);
    }
}
