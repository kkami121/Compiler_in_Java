package io.github.kkami121.codeanalyzer.server;

import io.github.kkami121.codeanalyzer.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerServerTest {

    private static AnalyzerServer server;

    @BeforeAll
    static void start() throws IOException {
        server = new AnalyzerServer("127.0.0.1", 0);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void health() throws IOException {
        Response response = request("GET", "/health", null);
        assertEquals(200, response.status);
        assertEquals("ok", ((Map<?, ?>) Json.parse(response.body)).get("status"));
    }

    @Test
    void metrics() throws IOException {
        Response response = request("POST", "/metrics",
                "{\"language\": \"python\", \"code\": \"def f(x):\\n    if x:\\n        return 1\\n\"}");
        assertEquals(200, response.status);
        Map<?, ?> body = (Map<?, ?>) Json.parse(response.body);
        assertEquals("EASY", body.get("difficulty"));
        assertEquals(2L, body.get("maxCyclomaticComplexity"));
    }

    @Test
    void analyze() throws IOException {
        Response response = request("POST", "/analyze",
                "{\"name\": \"a.py\", \"code\": \"import os\\ndef f(p):\\n    os.system('rm ' + p)\\n\"}");
        assertEquals(200, response.status);
        Map<?, ?> body = (Map<?, ?>) Json.parse(response.body);
        assertEquals("HIGH", body.get("highestSeverity"));
        Map<?, ?> finding = (Map<?, ?>) ((List<?>) body.get("findings")).get(0);
        assertEquals("command-injection", finding.get("rule"));
        assertEquals("CWE-78", finding.get("cwe"));
        assertTrue(((Map<?, ?>) body.get("style")).get("note").toString().contains("not evidence"));
    }

    @Test
    void tokenizeDetectsLanguageFromName() throws IOException {
        Response response = request("POST", "/tokenize", "{\"name\": \"A.java\", \"code\": \"int x;\"}");
        assertEquals(200, response.status);
        Map<?, ?> body = (Map<?, ?>) Json.parse(response.body);
        assertEquals("JAVA", body.get("language"));
        assertEquals(3, ((List<?>) body.get("tokens")).size());
    }

    @Test
    void clonesAndSimilarity() throws IOException {
        String code = "def f(a, b):\\n    total = a + b\\n    for i in range(a):\\n        total += i * b\\n    return total\\n";
        String file = "{\"name\": \"%s\", \"code\": \"" + code + "\"}";
        Response clones = request("POST", "/clones", "{\"minTokens\": 10, \"files\": ["
                + String.format(file, "a.py") + "," + String.format(file, "b.py") + "]}");
        assertEquals(200, clones.status);
        assertEquals(1, ((List<?>) ((Map<?, ?>) Json.parse(clones.body)).get("clones")).size());

        Response similarity = request("POST", "/similarity", "{\"first\": " + String.format(file, "a.py")
                + ", \"second\": " + String.format(file, "b.py") + "}");
        assertEquals(1.0, ((Map<?, ?>) Json.parse(similarity.body)).get("similarity"));
    }

    @Test
    void errors() throws IOException {
        assertEquals(404, request("GET", "/nope", null).status);
        assertEquals(405, request("GET", "/metrics", null).status);
        assertEquals(400, request("POST", "/metrics", "{not json").status);
        assertEquals(400, request("POST", "/metrics", "{\"language\": \"cobol\", \"code\": \"\"}").status);
        assertEquals(400, request("POST", "/metrics", "{\"code\": \"x\"}").status);
        assertEquals(400, request("POST", "/clones", "{\"files\": [], \"minTokens\": 1}").status);

        StringBuilder huge = new StringBuilder("{\"language\": \"java\", \"code\": \"");
        while (huge.length() <= AnalyzerServer.MAX_BODY_BYTES) {
            huge.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        }
        huge.append("\"}");
        Response tooLarge = request("POST", "/metrics", huge.toString());
        assertEquals(413, tooLarge.status);
        assertTrue(tooLarge.body.contains("exceeds"));
    }

    private static Response request(String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + server.port() + path).openConnection();
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // 서버가 본문을 다 읽기 전에 응답을 보내면(413) 쓰기가 끊길 수 있다
            }
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            in.close();
        }
        return new Response(status, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
