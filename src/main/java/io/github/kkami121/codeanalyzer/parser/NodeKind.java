package io.github.kkami121.codeanalyzer.parser;

/**
 * 언어 공통 구문 트리 노드 종류. 자식 순서는 각 상수 설명을 따른다.
 * "?"는 없을 수도 있는 자식이다.
 */
public enum NodeKind {
    /** 파일 전체. 자식: 최상위 선언/문장. */
    MODULE,
    /** import. text: 모듈 경로(Python from-import) 또는 "". 자식: VARIABLE(text=바인딩 이름, 자식 NAME=원래 이름). */
    IMPORT,
    /** 클래스/인터페이스/enum/record. text: 이름. 자식: DECORATOR..., 부모 타입 식..., BLOCK(멤버). */
    CLASS,
    /** 함수/메서드/생성자. text: 이름. 자식: DECORATOR..., PARAMETER..., TYPE(반환형)?, BLOCK?(본문). */
    FUNCTION,
    /** 매개변수. text: 이름. 자식: TYPE?, 기본값 식?. */
    PARAMETER,
    /** 데코레이터/어노테이션. text: 이름. 자식: 인자. */
    DECORATOR,
    /** 블록. 자식: 문장. */
    BLOCK,
    /** if. 자식: 조건, then, else?(BLOCK 또는 IF 또는 문장). */
    IF,
    /** 반복문. text: for/while/do. 마지막 자식이 본문 (Python은 else 블록이 뒤에 올 수 있음: 자식 [대상, 반복식, BLOCK, BLOCK?]). */
    LOOP,
    /** try. 자식: 자원(VARIABLE)..., BLOCK, CATCH..., BLOCK(else)?, FINALLY?. */
    TRY,
    /** catch/except. text: 예외 변수 이름 또는 "". 자식: 예외 타입?, BLOCK. */
    CATCH,
    /** finally. 자식: BLOCK. */
    FINALLY,
    /** switch/match. 자식: 대상 식, CASE.... */
    SWITCH,
    /** case/default. text: "case" 또는 "default". 자식: 문장. */
    CASE,
    /** with. 자식: 자원 식..., BLOCK. */
    WITH,
    RETURN,
    /** throw/raise. */
    THROW,
    BREAK,
    CONTINUE,
    /** Python pass, Java 빈 문장. */
    PASS,
    ASSERT,
    /** 변수 선언. text: 이름. 자식: TYPE?, 초기값?. */
    VARIABLE,
    /** 대입. text: 연산자(=, +=, :=, Python 주석 대입은 ":"). 자식: 대상, (중간 대상...), 값. */
    ASSIGN,
    /** 식 문장. 자식: 식. */
    EXPRESSION_STATEMENT,
    /** 호출. 자식: 호출 대상, 인자.... */
    CALL,
    /** 키워드 인자 (Python f(x=1), Java 어노테이션 a = 1). text: 이름. 자식: 값. */
    ARGUMENT,
    /** 이름. text: 이름. */
    NAME,
    /** 속성 접근 a.b. text: 속성 이름. 자식: 대상. */
    ATTRIBUTE,
    /** 첨자 a[i]. 자식: 대상, 첨자.... */
    INDEX,
    /** 리터럴. text: 소스 그대로 (문자열은 따옴표/접두사 포함). */
    LITERAL,
    /** 이항 연산. text: 연산자. 자식: 왼쪽, 오른쪽. */
    BINARY,
    /** 단항 연산. text: 연산자. 자식: 피연산자. */
    UNARY,
    /** 조건식. 자식: 조건, 참일 때, 거짓일 때. */
    TERNARY,
    /** 람다. 자식: PARAMETER..., 본문. */
    LAMBDA,
    /** 객체/배열 생성 (Java new). text: 타입. 자식: 인자... (익명 클래스면 CLASS). */
    NEW,
    /** 형 변환 (Java). text: 타입. 자식: 식. */
    CAST,
    /** 목록/튜플/집합/사전/배열 초기화. text: 여는 괄호. 자식: 원소 (사전 원소는 BINARY ":"). */
    COLLECTION,
    /** 컴프리헨션. 자식: 원소, (대상, 반복식, 조건...)... */
    COMPREHENSION,
    /** 타입 표기. text: 타입 문자열. Python 타입 힌트는 자식으로 식을 가진다. */
    TYPE,
    /** 그 밖의 구문 (global, del, synchronized, 레이블 등). text: 설명. */
    OTHER,
    /** 구문 오류로 건너뛴 부분. text: 오류 메시지. */
    ERROR
}
