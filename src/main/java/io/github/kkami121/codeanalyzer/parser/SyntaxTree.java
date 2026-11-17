package io.github.kkami121.codeanalyzer.parser;

import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.lexer.LexResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 구문분석 결과: 루트 노드, 구문 오류, 원본 어휘분석 결과. */
public final class SyntaxTree {

    private final LexResult lexResult;
    private final Node root;
    private final List<ParseError> errors;

    public SyntaxTree(LexResult lexResult, Node root, List<ParseError> errors) {
        this.lexResult = lexResult;
        this.root = root;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    /**
     * 어휘분석 결과를 구문분석한다.
     *
     * @throws UnsupportedOperationException 구문분석을 지원하지 않는 언어 (Decaf)
     */
    public static SyntaxTree parse(LexResult lexResult) {
        switch (lexResult.language()) {
            case JAVA:
                return JavaSyntaxParser.parse(lexResult);
            case PYTHON:
                return PythonSyntaxParser.parse(lexResult);
            default:
                throw new UnsupportedOperationException("Parsing is not supported for " + lexResult.language());
        }
    }

    public static boolean isSupported(Language language) {
        return language == Language.JAVA || language == Language.PYTHON;
    }

    public Language language() {
        return lexResult.language();
    }

    public LexResult lexResult() {
        return lexResult;
    }

    public Node root() {
        return root;
    }

    public List<ParseError> errors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
