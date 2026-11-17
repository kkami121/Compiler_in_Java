package io.github.kkami121.codeanalyzer.analysis;

/** 발견 사항 분류. */
public enum Category {
    /** 유지보수성, 버그 가능성, 미완성 코드. */
    QUALITY,
    /** 보안 취약점 (CWE 번호와 함께). */
    SECURITY
}
