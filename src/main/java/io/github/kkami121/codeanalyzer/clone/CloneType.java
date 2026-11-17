package io.github.kkami121.codeanalyzer.clone;

/** 클론 종류 (Roy &amp; Cordy 분류). */
public enum CloneType {
    /** 공백, 주석, 줄바꿈만 다르고 토큰이 똑같다. */
    TYPE_1,
    /** 식별자 이름이나 리터럴 값만 다르고 구조가 똑같다. */
    TYPE_2
}
