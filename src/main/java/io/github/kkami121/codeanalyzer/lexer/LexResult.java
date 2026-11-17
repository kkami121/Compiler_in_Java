package io.github.kkami121.codeanalyzer.lexer;

import io.github.kkami121.codeanalyzer.Language;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 어휘분석 결과. 토큰은 소스에 나온 순서대로 들어 있다. */
public final class LexResult {

    private final Language language;
    private final List<Token> tokens;
    private final List<LexError> errors;

    public LexResult(Language language, List<Token> tokens, List<LexError> errors) {
        this.language = language;
        this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public Language language() {
        return language;
    }

    /** 주석과 레이아웃(NEWLINE/INDENT/DEDENT)을 포함한 모든 토큰. */
    public List<Token> tokens() {
        return tokens;
    }

    /** 주석과 레이아웃을 뺀 코드 토큰. */
    public List<Token> codeTokens() {
        List<Token> result = new ArrayList<>();
        for (Token token : tokens) {
            if (token.kind().isCode()) {
                result.add(token);
            }
        }
        return result;
    }

    public List<Token> comments() {
        List<Token> result = new ArrayList<>();
        for (Token token : tokens) {
            if (token.kind() == TokenKind.COMMENT) {
                result.add(token);
            }
        }
        return result;
    }

    public List<LexError> errors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
