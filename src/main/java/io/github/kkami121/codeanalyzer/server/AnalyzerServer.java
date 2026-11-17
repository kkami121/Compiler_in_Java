package io.github.kkami121.codeanalyzer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kkami121.codeanalyzer.CodeAnalyzer;
import io.github.kkami121.codeanalyzer.Language;
import io.github.kkami121.codeanalyzer.SourceFile;
import io.github.kkami121.codeanalyzer.clone.CloneDetector;
import io.github.kkami121.codeanalyzer.json.Json;
import io.github.kkami121.codeanalyzer.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDK 내장 HTTP 서버로 만든 JSON REST API. 외부 라이브러리가 필요 없다.
 *
 * <pre>
 * GET  /health                                         상태 확인
 * GET  /languages                                      지원 언어
 * POST /tokenize    {"language": "java", "code": "..."}
 * POST /metrics     {"language": "python", "code": "..."}
 * POST /analyze     {"language": "python", "code": "..."}   품질/보안 발견 사항 + 지표 + 스타일
 * POST /clones      {"files": [{"name": "A.java", "code": "..."}, ...], "minTokens": 30}
 * POST /similarity  {"first": {"name": "a.py", "code": "..."}, "second": {...}}
 * </pre>
 * {@code language}를 생략하면 {@code name}의 확장자로 정한다.
 * 요청 본문은 {@value #MAX_BODY_BYTES} 바이트까지 받는다.
 */
public final class AnalyzerServer {

    /** 요청 본문 최대 크기 (1 MiB). */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /** /clones 한 번에 받는 최대 파일 수. */
    public static final int MAX_FILES = 200;

    private final HttpServer server;
    private final ExecutorService executor;

    /** @param port 0이면 빈 포트를 자동으로 고른다 */
    public AnalyzerServer(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (method.equals("OPTIONS")) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (method.equals("GET") && (path.equals("/") || path.equals("/health"))) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "ok");
                body.put("version", CodeAnalyzer.VERSION);
                send(exchange, 200, body);
                return;
            }
            if (method.equals("GET") && path.equals("/languages")) {
                List<Object> languages = new ArrayList<>();
                for (Language language : Language.values()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", language.name().toLowerCase());
                    item.put("displayName", language.displayName());
                    item.put("extensions", language.extensions());
                    languages.add(item);
                }
                send(exchange, 200, languages);
                return;
            }
            boolean known = path.equals("/tokenize") || path.equals("/metrics") || path.equals("/clones")
                    || path.equals("/similarity") || path.equals("/analyze");
            if (!known) {
                sendError(exchange, 404, "Not found: " + path);
                return;
            }
            if (!method.equals("POST")) {
                exchange.getResponseHeaders().add("Allow", "POST");
                sendError(exchange, 405, "Use POST for " + path);
                return;
            }
            String body = readBody(exchange);
            if (body == null) {
                sendError(exchange, 413, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
                return;
            }
            Map<String, Object> request = asObject(Json.parse(body), "request body");
            send(exchange, 200, dispatch(path, request));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            sendError(exchange, 500, "Internal error: " + e.getClass().getSimpleName());
        } finally {
            exchange.close();
        }
    }

    private static Object dispatch(String path, Map<String, Object> request) {
        switch (path) {
            case "/tokenize": {
                SourceFile file = sourceFile(request, "request");
                return JsonMapper.toMap(CodeAnalyzer.tokenize(file.content(), file.language()));
            }
            case "/metrics": {
                SourceFile file = sourceFile(request, "request");
                return JsonMapper.toMap(CodeAnalyzer.measure(file));
            }
            case "/analyze": {
                SourceFile file = sourceFile(request, "request");
                return JsonMapper.toMap(CodeAnalyzer.analyze(file));
            }
            case "/clones": {
                Object filesValue = request.get("files");
                if (!(filesValue instanceof List)) {
                    throw new IllegalArgumentException("'files' must be an array");
                }
                List<?> items = (List<?>) filesValue;
                if (items.size() > MAX_FILES) {
                    throw new IllegalArgumentException("At most " + MAX_FILES + " files per request");
                }
                List<SourceFile> files = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    files.add(sourceFile(asObject(items.get(i), "files[" + i + "]"), "files[" + i + "]"));
                }
                int minTokens = intField(request, "minTokens", CloneDetector.DEFAULT_MIN_TOKENS);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("minTokens", minTokens);
                result.put("clones", JsonMapper.toList(CodeAnalyzer.findClones(files, minTokens)));
                return result;
            }
            default: { // /similarity
                SourceFile first = sourceFile(asObject(request.get("first"), "first"), "first");
                SourceFile second = sourceFile(asObject(request.get("second"), "second"), "second");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("similarity", Math.round(CodeAnalyzer.similarity(first, second) * 1000) / 1000.0);
                return result;
            }
        }
    }

    private static SourceFile sourceFile(Map<String, Object> object, String where) {
        Object code = object.get("code");
        if (!(code instanceof String)) {
            throw new IllegalArgumentException("'" + where + ".code' must be a string");
        }
        Object name = object.get("name");
        Object language = object.get("language");
        String fileName = name instanceof String ? (String) name : where;
        if (language instanceof String) {
            return new SourceFile(fileName, Language.fromName((String) language), (String) code);
        }
        if (name instanceof String) {
            return new SourceFile(fileName, (String) code);
        }
        throw new IllegalArgumentException("'" + where + "' needs 'language' or a 'name' with a file extension");
    }

    private static int intField(Map<String, Object> object, String key, int defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Long) || (Long) value < 2 || (Long) value > 100_000) {
            throw new IllegalArgumentException("'" + key + "' must be an integer between 2 and 100000");
        }
        return ((Long) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String where) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("'" + where + "' must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** 크기 제한을 넘으면 null. */
    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_BODY_BYTES) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        send(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
