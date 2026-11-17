# Code Analyzer

Java, Python, Decaf 소스 코드를 분석하는 Java 라이브러리입니다.

- **토큰 분리**: 키워드, 식별자, 리터럴, 연산자, 주석으로 나누고 줄/열 위치를 붙입니다. Python은 `INDENT`/`DEDENT`도 만듭니다.
- **난이도 측정**: 순환 복잡도(McCabe), Halstead 지표, 중첩 깊이, 줄 수를 계산하고 `EASY`~`VERY_HARD` 등급을 판정합니다.
- **코드 클론 탐지**: 복사한 코드를 찾습니다. 변수 이름이나 상수만 바꾼 경우(Type-2)도 찾습니다. 두 소스의 구조적 유사도(0~1)도 계산합니다.

런타임 의존성이 없고 Java 8 이상에서 동작합니다.

## 설치 (JitPack)

**Maven**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.kkami121</groupId>
    <artifactId>Compiler_in_Java</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Gradle**

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.kkami121:Compiler_in_Java:0.1.0' }
```

## 사용법

```java
import io.github.kkami121.codeanalyzer.*;

// 1. 토큰 분리
LexResult result = CodeAnalyzer.tokenize("int x = 0x1F;", Language.JAVA);
for (Token t : result.codeTokens()) {
    System.out.println(t.line() + ":" + t.column() + " " + t.kind() + " " + t.text());
}

// 2. 난이도 측정
CodeMetrics m = CodeAnalyzer.measure(pythonSource, Language.PYTHON);
m.difficulty();                 // EASY, MODERATE, HARD, VERY_HARD
m.difficultyReasons();          // 판정 근거
m.maxCyclomaticComplexity();
m.halstead().difficulty();
m.functions();                  // 함수별 복잡도, 중첩 깊이, 위치

// 3. 클론 탐지
SourceFile a = SourceFile.read(Paths.get("A.java"));      // 확장자로 언어 판별
SourceFile b = new SourceFile("B.java", sourceOfB);
List<ClonePair> clones = CodeAnalyzer.findClones(Arrays.asList(a, b));      // 기본 최소 30토큰
List<ClonePair> small  = CodeAnalyzer.findClones(Arrays.asList(a, b), 15);
double similarity = CodeAnalyzer.similarity(a, b);        // 0.0 ~ 1.0
```

모든 메서드는 상태가 없어 여러 스레드에서 동시에 호출해도 됩니다.

## 난이도 판정 기준

아래 세 항목 중 가장 높은 등급을 전체 난이도로 판정합니다.

| 항목 | EASY | MODERATE | HARD | VERY_HARD |
|---|---|---|---|---|
| 최대 순환 복잡도 | ≤ 10 | 11–20 | 21–50 | > 50 |
| 최대 중첩 깊이 | ≤ 2 | 3–4 | 5–6 | > 6 |
| 함수별 최대 Halstead 난이도 | ≤ 15 | ≤ 30 | ≤ 60 | > 60 |

순환 복잡도 기준은 SEI 분류를 따릅니다. 중첩 깊이와 Halstead 기준은 경험적으로 정했고, JDK와 CPython 표준 라이브러리 코드의 분포를 참고했습니다.

## 명령줄 도구

```bash
java -jar code-analyzer-0.1.0.jar metrics Foo.py
java -jar code-analyzer-0.1.0.jar tokens Foo.java --format json
java -jar code-analyzer-0.1.0.jar clones src/*.java --min-tokens 50
java -jar code-analyzer-0.1.0.jar similarity a.py b.py
java -jar code-analyzer-0.1.0.jar tokens program.decaf --format decaf   # Decaf 과제(PA1) 출력 형식
```

## 로컬 REST API

```bash
java -jar code-analyzer-0.1.0.jar serve --port 8080
curl -X POST localhost:8080/metrics -d '{"language": "python", "code": "def f(x):\n    return x\n"}'
```

| 메서드 | 경로 | 본문 |
|---|---|---|
| GET | `/health`, `/languages` | |
| POST | `/tokenize`, `/metrics` | `{"language": "java", "code": "..."}` (`language` 대신 `"name": "A.java"`도 가능) |
| POST | `/clones` | `{"files": [{"name": "a.py", "code": "..."}, ...], "minTokens": 30}` |
| POST | `/similarity` | `{"first": {...}, "second": {...}}` |

요청 본문은 1 MiB까지 받습니다. 기본으로 `127.0.0.1`에만 바인딩합니다. 인증과 요청 수 제한이 없으니 그대로 인터넷에 공개하지 마세요.

## 한계

- 렉서는 직접 구현했습니다. JDK 17 소스 14,215개 파일과 CPython 3.14 표준 라이브러리 3,602개 파일로 검증했습니다.
- 함수 경계는 파서 없이 토큰 패턴으로 찾습니다. 중괄호 없는 `if (x) y;`는 중첩 깊이에 넣지 않고, Python `match`/`case`는 순환 복잡도에 넣지 않습니다.
- 클론 탐지는 Type-1/Type-2 정확 일치를 찾습니다. 문장이 추가되거나 빠진 Type-3 클론은 `similarity` 점수로만 드러납니다.

## 개발

```bash
mvn test       # 테스트 실행 (Decaf 과제 샘플 16개 포함)
mvn install    # 로컬 Maven 저장소에 설치
```

Decaf 렉서는 컴파일러 수업의 어휘분석기 과제에서 시작했습니다. 과제 샘플과 기대 출력은 `src/test/resources/decaf`에 있습니다.
