package io.github.kkami121.codeanalyzer;

import io.github.kkami121.codeanalyzer.lexer.DecafLexer;
import io.github.kkami121.codeanalyzer.lexer.JavaLexer;
import io.github.kkami121.codeanalyzer.lexer.Lexer;
import io.github.kkami121.codeanalyzer.lexer.PythonLexer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 지원하는 언어. */
public enum Language {
    JAVA("Java", new JavaLexer(), "java"),
    PYTHON("Python", new PythonLexer(), "py", "pyw"),
    DECAF("Decaf", new DecafLexer(), "decaf", "frag");

    private final String displayName;
    private final Lexer lexer;
    private final List<String> extensions;

    Language(String displayName, Lexer lexer, String... extensions) {
        this.displayName = displayName;
        this.lexer = lexer;
        this.extensions = Collections.unmodifiableList(Arrays.asList(extensions));
    }

    public String displayName() {
        return displayName;
    }

    /** 이 언어의 렉서. 상태가 없어 공유해도 된다. */
    public Lexer lexer() {
        return lexer;
    }

    /** 점 없는 소문자 확장자 목록. */
    public List<String> extensions() {
        return extensions;
    }

    /**
     * 이름이나 확장자로 언어를 찾는다. 대소문자를 구분하지 않는다. 예: {@code "java"}, {@code "Python"}, {@code "py"}.
     *
     * @throws IllegalArgumentException 지원하지 않는 언어일 때
     */
    public static Language fromName(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        for (Language language : values()) {
            if (language.name().toLowerCase(Locale.ROOT).equals(key) || language.extensions.contains(key)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unsupported language: " + name + " (supported: java, python, decaf)");
    }

    /**
     * 파일 이름의 확장자로 언어를 찾는다.
     *
     * @throws IllegalArgumentException 확장자가 없거나 지원하지 않을 때
     */
    public static Language fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new IllegalArgumentException("Cannot detect language of file without extension: " + fileName);
        }
        return fromName(fileName.substring(dot + 1));
    }
}
