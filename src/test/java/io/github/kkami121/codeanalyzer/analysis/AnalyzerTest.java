package io.github.kkami121.codeanalyzer.analysis;

import io.github.kkami121.codeanalyzer.CodeAnalyzer;
import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {

    private static List<Finding> python(String source) {
        return CodeAnalyzer.analyze(source, Language.PYTHON).findings();
    }

    private static List<Finding> java(String source) {
        return CodeAnalyzer.analyze(source, Language.JAVA).findings();
    }

    private static List<String> rules(List<Finding> findings) {
        return findings.stream().map(f -> f.ruleId() + ":" + f.severity()).collect(Collectors.toList());
    }

    private static void assertHas(List<Finding> findings, String rule, Severity severity) {
        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals(rule) && f.severity() == severity),
                () -> "expected " + rule + ":" + severity + " in " + rules(findings));
    }

    private static void assertNone(List<Finding> findings, String rule) {
        assertTrue(findings.stream().noneMatch(f -> f.ruleId().equals(rule)), () -> "unexpected " + rule + " in " + findings);
    }

    // ------------------------------------------------------------ 보안: Python

    @Test
    void sqlInjectionFollowsVariablesInsideFunction() {
        List<Finding> findings = python("def f(conn, name):\n"
                + "    q = \"SELECT * FROM t WHERE n = '\" + name + \"'\"\n"
                + "    conn.execute(q)\n"
                + "    conn.execute(f\"DELETE FROM t WHERE id = {name}\")\n"
                + "    conn.execute(\"SELECT * FROM t WHERE n = ?\", (name,))\n"
                + "    conn.execute(\"SELECT 1\")\n");
        assertEquals(2, findings.stream().filter(f -> f.ruleId().equals("sql-injection")).count(), rules(findings)::toString);
        assertEquals("CWE-89", findings.get(0).cwe());
        assertEquals(3, findings.get(0).line());
    }

    @Test
    void commandInjectionResolvesImportAliases() {
        List<Finding> findings = python("import subprocess as sp\nfrom os import system\n"
                + "def f(path):\n"
                + "    system('rm ' + path)\n"
                + "    sp.run(f'ls {path}', shell=True)\n"
                + "    sp.run(['ls', path])\n");
        assertEquals(2, findings.stream().filter(f -> f.ruleId().equals("command-injection")
                && f.severity() == Severity.HIGH).count(), rules(findings)::toString);
    }

    @Test
    void codeInjectionIgnoresMethodsNamedEval() {
        assertHas(python("def f(s):\n    return eval('1 + ' + s)\n"), "code-injection", Severity.HIGH);
        assertNone(python("model.eval()\nexec_time = 3\n"), "code-injection");
    }

    @Test
    void hardcodedSecrets() {
        List<Finding> findings = python("API_KEY = 'a8F3kd92LmQwErTy'\n"
                + "password = 'your_password_here'\n"
                + "TOKEN_URL = 'https://auth.example.com/token'\n"
                + "PASSWORD_ENV = 'DB_PASSWORD'\n"
                + "pwd_field = 'password'\n"
                + "connect(user='a', password='hunter22')\n"
                + "aws = 'AKIAABCDEFGHIJKLMNOP'\n"
                + "config = {'secret': 'zZ9!xQ'}\n");
        List<Integer> lines = findings.stream().filter(f -> f.ruleId().equals("hardcoded-secret"))
                .map(Finding::line).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList(1, 2, 6, 7, 8), lines, rules(findings)::toString);
        assertEquals(Severity.MEDIUM, findings.stream().filter(f -> f.line() == 2).findFirst().get().severity());
    }

    @Test
    void cryptoDeserializationTlsAndConfig() {
        List<Finding> findings = python("import hashlib, pickle, yaml, requests, random, tempfile\n"
                + "def make_token():\n    return random.randint(0, 10**6)\n"
                + "hashlib.md5(b'x')\nhashlib.md5(b'x', usedforsecurity=False)\nhashlib.sha256(b'x')\n"
                + "pickle.loads(data)\nyaml.load(s)\nyaml.load(s, Loader=yaml.SafeLoader)\n"
                + "requests.get(u, verify=False, timeout=5)\nrequests.post(u)\n"
                + "tempfile.mktemp()\napp.run(host='0.0.0.0', debug=True)\n");
        assertHas(findings, "insecure-random", Severity.MEDIUM);
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("weak-hash")).count());
        assertEquals(2, findings.stream().filter(f -> f.ruleId().equals("insecure-deserialization")).count());
        assertHas(findings, "tls-verification-disabled", Severity.HIGH);
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("missing-timeout")).count());
        assertHas(findings, "insecure-temp-file", Severity.MEDIUM);
        assertHas(findings, "debug-mode-enabled", Severity.MEDIUM);
        assertHas(findings, "bind-all-interfaces", Severity.LOW);
        assertNone(python("import random\nkeys = [random.random() for _ in range(3)]\n"), "insecure-random");
    }

    // ------------------------------------------------------------ 보안: Java

    @Test
    void javaSecurityRules() {
        List<Finding> findings = java("class A {\n"
                + "  private static final String DB_PASSWORD = \"Sup3rS3cret!\";\n"
                + "  private static final String PASSWORD_PROP = \"passwordFile\";\n"
                + "  ResultSet find(Statement st, String n) throws Exception {\n"
                + "    String sql = \"SELECT * FROM u WHERE n = '\" + n + \"'\";\n"
                + "    return st.executeQuery(sql);\n  }\n"
                + "  void run(String in) throws Exception {\n"
                + "    Runtime.getRuntime().exec(\"ping \" + in);\n"
                + "    Runtime.getRuntime().exec(new String[]{\"ping\", in});\n"
                + "    MessageDigest.getInstance(\"MD5\"); MessageDigest.getInstance(\"SHA-256\");\n"
                + "    Cipher.getInstance(\"AES\"); Cipher.getInstance(\"AES/GCM/NoPadding\");\n"
                + "    new ObjectInputStream(in).readObject();\n"
                + "  }\n"
                + "  String newSessionToken() { return Long.toString(new Random().nextLong()); }\n"
                + "  class T implements X509TrustManager { public void checkServerTrusted(X509Certificate[] c, String a) { } }\n"
                + "}\n");
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("hardcoded-secret")).count(), rules(findings)::toString);
        assertHas(findings, "sql-injection", Severity.HIGH);
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("command-injection")).count());
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("weak-hash")).count());
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("weak-cipher")).count());
        assertHas(findings, "insecure-deserialization", Severity.MEDIUM);
        assertHas(findings, "insecure-random", Severity.MEDIUM);
        assertHas(findings, "tls-verification-disabled", Severity.HIGH);
    }

    @Test
    void javaXxeOnlyWhenNotHardened() {
        assertHas(java("class A { void f() { DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); } }"),
                "xml-external-entities", Severity.MEDIUM);
        assertNone(java("class A { void f() { DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();"
                + " f.setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true); } }"), "xml-external-entities");
    }

    // ------------------------------------------------------------ 품질

    @Test
    void pythonQualityRules() {
        List<Finding> findings = python("import os\nimport json\n"
                + "def f(items=[]):\n"
                + "    try:\n        x = items[0]\n    except:\n        pass\n"
                + "    if x == None:\n        return 1\n        print('never')\n"
                + "    return os.sep\n"
                + "def send():\n    # TODO: implement\n    raise NotImplementedError\n"
                + "def gen():\n    return\n    yield\n");
        assertHas(findings, "unused-import", Severity.LOW);
        assertEquals("Import 'json' is never used.", findings.stream().filter(f -> f.ruleId().equals("unused-import"))
                .findFirst().get().message());
        assertHas(findings, "mutable-default-argument", Severity.MEDIUM);
        assertHas(findings, "empty-catch", Severity.MEDIUM);
        assertHas(findings, "broad-exception", Severity.MEDIUM);
        assertHas(findings, "none-comparison", Severity.LOW);
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("unreachable-code")).count(),
                "'return; yield' generator idiom must not be reported");
        assertHas(findings, "placeholder-implementation", Severity.LOW);
        assertHas(findings, "todo-comment", Severity.INFO);
    }

    @Test
    void specificExceptionIgnoredIsLowAndAbstractMethodsAreNotPlaceholders() {
        List<Finding> findings = python("try:\n    import readline\nexcept ImportError:\n    pass\n"
                + "class Base:\n    def handle(self):\n        raise NotImplementedError\n");
        assertHas(findings, "empty-catch", Severity.LOW);
        assertNone(findings, "placeholder-implementation");
    }

    @Test
    void javaQualityRules() {
        List<Finding> findings = java("import java.util.List;\nclass A {\n"
                + "  void f(String s) {\n"
                + "    try { g(); } catch (Throwable t) { }\n"
                + "    if (s == \"x\") { return; }\n"
                + "  }\n"
                + "  int g() { return 1; h(); }\n"
                + "  void todo() { throw new UnsupportedOperationException(\"Not implemented yet\"); }\n"
                + "  void a(int p1, int p2, int p3, int p4, int p5, int p6, int p7) { }\n"
                + "}\n");
        assertHas(findings, "unused-import", Severity.LOW);
        assertHas(findings, "empty-catch", Severity.MEDIUM);
        assertHas(findings, "broad-exception", Severity.MEDIUM);
        assertHas(findings, "string-reference-comparison", Severity.MEDIUM);
        assertHas(findings, "unreachable-code", Severity.MEDIUM);
        assertHas(findings, "placeholder-implementation", Severity.LOW);
        assertHas(findings, "too-many-parameters", Severity.LOW);
    }

    @Test
    void suppressionCommentsAreHonored() {
        List<Finding> findings = python("import json  # noqa: F401\nimport os\n"
                + "eval(x)  # nosec\nexec(y)  # codeanalyzer: ignore\n");
        assertEquals(1, findings.size(), rules(findings)::toString);
        assertEquals("unused-import", findings.get(0).ruleId());
        assertEquals(2, findings.get(0).line());
    }

    // ------------------------------------------------------------ 보고서

    @Test
    void reportStructureAndStyle() {
        AnalysisReport report = CodeAnalyzer.analyze(new SourceFile("svc.py",
                "class Service:\n    def get_user(self, user_id: int) -> dict:\n        \"\"\"Doc.\"\"\"\n        return {}\n"
                        + "    def deleteUser(self, uid):\n        return None\n"));
        assertEquals(1, report.classCount());
        assertEquals(2, report.functionCount());
        assertNull(report.highestSeverity());
        StyleProfile style = report.style();
        assertNotNull(style);
        assertEquals(0.5, style.documentedFunctionRatio(), 1e-9);
        assertEquals(0.5, style.typedParameterRatio(), 1e-9);
        assertEquals(0.5, style.namingConsistency(), 1e-9);
        assertTrue(StyleProfile.NOTE.contains("not evidence"));
    }

    @Test
    void decafGetsMetricsOnly() {
        AnalysisReport report = CodeAnalyzer.analyze("void main() { Print(1); }", Language.DECAF);
        assertFalse(report.rulesApplied());
        assertTrue(report.findings().isEmpty());
        assertEquals(1, report.metrics().functions().size());
    }

    @Test
    void securityWordSplitting() {
        assertTrue(SecurityRules.isSecurityName("sessionToken"));
        assertTrue(SecurityRules.isSecurityName("API_KEY"));
        assertTrue(SecurityRules.isSecurityName("generate_otp"));
        assertFalse(SecurityRules.isSecurityName("keys"));
        assertFalse(SecurityRules.isSecurityName("monkey"));
        assertFalse(SecurityRules.isSecurityName("key"));
    }
}
