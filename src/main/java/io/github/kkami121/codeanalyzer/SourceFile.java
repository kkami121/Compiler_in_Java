package io.github.kkami121.codeanalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 분석할 소스 하나: 이름(보통 파일 경로), 언어, 내용. */
public final class SourceFile {

    private final String name;
    private final Language language;
    private final String content;

    public SourceFile(String name, Language language, String content) {
        this.name = Objects.requireNonNull(name, "name");
        this.language = Objects.requireNonNull(language, "language");
        this.content = Objects.requireNonNull(content, "content");
    }

    /** 이름의 확장자로 언어를 정한다. */
    public SourceFile(String name, String content) {
        this(name, Language.fromFileName(name), content);
    }

    /** 파일(UTF-8)을 읽는다. 언어는 확장자로 정한다. */
    public static SourceFile read(Path path) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return new SourceFile(path.toString(), content);
    }

    public String name() {
        return name;
    }

    public Language language() {
        return language;
    }

    public String content() {
        return content;
    }
}
