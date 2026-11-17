# Code Analyzer

Java와 Python 소스 코드를 정적 분석하는 Java 라이브러리이자 CLI이자 Docker 이미지입니다. Decaf는 토큰 분리와 지표만 지원합니다. 사람이 쓴 코드와 AI가 생성한 코드를 리뷰할 때 쓰도록 만들었습니다.

| 기능 | 내용 |
|---|---|
| **토큰 분리** | 직접 구현한 렉서입니다. Python의 `INDENT`/`DEDENT`, f-string(PEP 701), Java 텍스트 블록을 처리합니다. |
| **구문분석** | 재귀 하강 파서가 두 언어 공통 형태의 AST를 만듭니다. 오류가 있어도 다음 문장부터 분석을 계속합니다. |
| **난이도 측정** | 순환 복잡도, Halstead, 중첩 깊이, 줄 수를 계산해 `EASY`~`VERY_HARD` 등급을 매깁니다. |
| **품질 규칙** | 14개. 빈 catch, 도달할 수 없는 코드, 가변 기본 인자, 쓰지 않는 import, 미구현 placeholder, 중복 코드 등. |
| **보안 규칙** | 15개, CWE 번호 포함. SQL·명령어·코드 주입, 하드코딩된 비밀값, 안전하지 않은 역직렬화, 취약한 해시와 암호, TLS 검증 끄기, XXE 등. |
| **클론 탐지** | 토큰을 정규화해 복사 코드(Type-1/2)를 찾고, 두 소스의 유사도를 계산합니다. |
| **스타일 지표** | 문서화 비율, 타입 힌트 비율, 이름 규칙 일관성. ⚠️ 작성자(사람/AI)를 판정하는 기능이 **아닙니다**. |

런타임 의존성이 없고 Java 8 이상에서 동작합니다.

## 검증 결과

| 대상 | 규모 | 렉서 오류 | 파서 오류 | 크래시 | 분석 속도 |
|---|---|---|---|---|---|
| JDK 17 소스 전체 | 14,215개 파일, 470만 줄 | 0 | 0 | 0 | 약 28만 줄/초 |
| CPython 3.14 표준 라이브러리 | 3,602개 파일, 99만 줄 | 1 (CPython이 일부러 넣은 잘못된 코드) | 0 | 0 | 약 16만 줄/초 |

보안 규칙은 이 두 코드베이스에 돌려 오탐을 줄였습니다. JDK에서 하드코딩 비밀값 오탐은 0건입니다.

## 설치 (JitPack)

```xml
<repositories>
    <repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
</repositories>
<dependency>
    <groupId>com.github.kkami121</groupId>
    <artifactId>Compiler_in_Java</artifactId>
    <version>0.2.0</version>
</dependency>
```

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.kkami121:Compiler_in_Java:0.2.0' }
```

## 라이브러리 사용법

```java
// 종합 분석: 지표 + 품질/보안 발견 사항 + 스타일
AnalysisReport report = CodeAnalyzer.analyze(SourceFile.read(Paths.get("app.py")));
for (Finding f : report.findings()) {
    System.out.println(f.line() + " " + f.severity() + " " + f.ruleId() + " " + f.cwe() + " " + f.message());
}
report.highestSeverity();          // CI 게이트에 쓸 수 있다
report.metrics().difficulty();     // EASY ~ VERY_HARD

// 개별 기능
SyntaxTree tree  = CodeAnalyzer.parse(source, Language.JAVA);       // AST
LexResult tokens = CodeAnalyzer.tokenize(source, Language.PYTHON);
CodeMetrics m    = CodeAnalyzer.measure(source, Language.PYTHON);
List<ClonePair> clones = CodeAnalyzer.findClones(Arrays.asList(a, b));
double similarity      = CodeAnalyzer.similarity(a, b);              // 0.0 ~ 1.0
```

## 명령줄과 Docker

호스트에 설치된 Java 버전과 상관없이 Docker로 빌드하고 실행할 수 있습니다. 이미지를 빌드할 때 테스트도 함께 실행됩니다.

```bash
docker build -t code-analyzer:0.2.0 .

# 파일 분석 (읽기 전용 마운트, 네트워크 없음). HIGH 이상이 있으면 종료 코드 1
docker run --rm --network none -v "$PWD:/work:ro" code-analyzer:0.2.0 analyze app.py --fail-on high
docker run --rm -v "$PWD:/work:ro" code-analyzer:0.2.0 analyze src/Main.java --format json

# REST API (로컬에서만 열기)
docker run --rm -p 127.0.0.1:8080:8080 code-analyzer:0.2.0
# 또는
docker compose up -d api
docker compose run --rm cli analyze app.py
```

Java가 설치돼 있다면 jar로 바로 실행해도 됩니다: `java -jar code-analyzer-0.2.0.jar analyze app.py`

| 명령 | 설명 |
|---|---|
| `analyze <file>... [--format text\|json] [--fail-on low\|medium\|high\|critical]` | 종합 분석 |
| `metrics <file>` | 지표와 난이도 |
| `tokens <file> [--format text\|json\|decaf]` | 토큰 (`decaf`는 컴파일러 과제 PA1 출력 형식) |
| `clones <file>... [--min-tokens N]` / `similarity <a> <b>` | 클론과 유사도 |
| `serve [--port 8080] [--host 127.0.0.1]` | REST API 서버 |

**REST API**: `POST /analyze`, `/metrics`, `/tokenize`는 `{"language": "python", "code": "..."}` 또는 `{"name": "app.py", "code": "..."}` 형식을 받습니다. `POST /clones`는 `{"files": [...], "minTokens": 30}`, `POST /similarity`는 `{"first": {...}, "second": {...}}`를 받습니다. `GET /health`와 `/languages`도 있습니다. 요청 본문은 1 MiB까지 받습니다. 인증과 요청 수 제한이 없으니 인터넷에 그대로 공개하지 마세요.

## 규칙

**보안** (규칙 이름 · CWE)

| 규칙 | CWE | 탐지 내용 |
|---|---|---|
| sql-injection | 89 | 연결이나 포맷팅으로 만든 문자열을 `execute`, `executeQuery` 등에 넘김. 함수 안의 변수 대입까지 추적 |
| command-injection | 78 | `os.system`, `shell=True`, `Runtime.exec`에 조립한 명령어를 넘김 |
| code-injection | 95 | `eval`/`exec`, `ScriptEngine.eval` |
| hardcoded-secret | 798 | 비밀값 이름에 문자열을 대입, AWS·GitHub·Slack·OpenAI 키 형식, URL 안의 비밀번호 |
| insecure-deserialization | 502 | pickle, marshal, `yaml.load`, `ObjectInputStream`, `XMLDecoder` |
| weak-hash / weak-cipher | 328 / 327 | MD5, SHA-1, DES, RC4, ECB 모드 |
| insecure-random | 330 | 토큰이나 비밀번호를 `random` 또는 `java.util.Random`으로 생성 |
| tls-verification-disabled | 295 | `verify=False`, `CERT_NONE`, 모든 인증서를 믿는 TrustManager/HostnameVerifier |
| xml-external-entities | 611 | 보안 설정 없는 XML 파서 팩토리 |
| debug-mode-enabled, insecure-temp-file, bind-all-interfaces, missing-timeout, stack-trace-exposure | 489, 377, 1327, 400, 209 | |

**품질**: empty-catch, broad-exception, long-function, high-complexity, deep-nesting, too-many-parameters, mutable-default-argument, unused-import, unreachable-code, placeholder-implementation, todo-comment, none-comparison, string-reference-comparison, duplicate-code

**억제**: 그 줄에 `# noqa`, `# nosec`, `// NOSONAR`, `codeanalyzer: ignore` 주석을 달면 그 줄의 발견 사항을 보고하지 않습니다.

## 한계

- **AI 작성 여부는 판정하지 않습니다.** 코드만으로 작성자를 신뢰성 있게 가려내는 방법은 없고, 오탐은 사람을 잘못 의심하게 만듭니다. 스타일 지표는 리뷰를 돕는 참고용입니다.
- 보안 분석은 규칙 기반입니다. 데이터 흐름은 **함수 안의 변수 대입까지만** 따라가고, 함수나 파일을 넘나드는 taint 분석은 하지 않습니다. 그래서 놓치는 취약점이 있을 수 있습니다.
- 파서는 의미 분석(타입 검사, 이름 해석)을 하지 않습니다. switch/match의 패턴은 세부 구조 없이 건너뜁니다.
- Python에서 괄호가 닫히지 않은 오류가 있으면 CPython과 마찬가지로 그 뒤 전체가 하나의 논리적 줄이 되어 분석 범위가 줄어듭니다.

## 개발

```bash
mvn test                              # 테스트 95개 (Decaf 과제 샘플 16개 포함)
docker build -t code-analyzer:dev .   # JDK 17 + Maven 3.9 고정 환경에서 빌드와 테스트
```

Decaf 렉서는 컴파일러 수업의 어휘분석기 과제에서 시작했습니다. 과제 샘플과 기대 출력은 `src/test/resources/decaf`에 있습니다.
