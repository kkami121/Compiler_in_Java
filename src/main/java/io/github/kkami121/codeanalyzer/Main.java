package io.github.kkami121.codeanalyzer;

import io.github.kkami121.codeanalyzer.analysis.AnalysisReport;
import io.github.kkami121.codeanalyzer.analysis.Finding;
import io.github.kkami121.codeanalyzer.analysis.Severity;
import io.github.kkami121.codeanalyzer.clone.CloneDetector;
import io.github.kkami121.codeanalyzer.parser.ParseError;
import io.github.kkami121.codeanalyzer.json.Json;
import io.github.kkami121.codeanalyzer.json.JsonMapper;
import io.github.kkami121.codeanalyzer.lexer.DecafFormatter;
import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.server.AnalyzerServer;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 명령줄 도구. {@code java -jar code-analyzer.jar help}로 사용법을 본다. */
public final class Main {

    private static final String USAGE = String.join("\n",
            "Code Analyzer " + CodeAnalyzer.VERSION,
            "",
            "Usage:",
            "  analyze <file>... [--language L] [--format text|json] [--fail-on low|medium|high|critical]",
            "  tokens <file> [--language L] [--format text|json|decaf]",
            "  metrics <file> [--language L]",
            "  clones <file>... [--min-tokens N]",
            "  similarity <fileA> <fileB>",
            "  serve [--port 8080] [--host 127.0.0.1]",
            "",
            "Languages: java, python, decaf (detected from the file extension unless --language is given)");

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (NoSuchFileException e) {
            System.err.println("File not found: " + e.getFile());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        Options options = Options.parse(args);
        switch (args[0]) {
            case "analyze":
                return analyze(options);
            case "tokens":
                return tokens(options);
            case "metrics":
                SourceFile file = options.singleFile();
                System.out.println(Json.writePretty(JsonMapper.toMap(CodeAnalyzer.measure(file))));
                return 0;
            case "clones":
                return clones(options);
            case "similarity":
                if (options.files.size() != 2) {
                    throw new IllegalArgumentException("similarity needs exactly two files");
                }
                List<SourceFile> files = options.sourceFiles();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("similarity", Math.round(CodeAnalyzer.similarity(files.get(0), files.get(1)) * 1000) / 1000.0);
                System.out.println(Json.writePretty(result));
                return 0;
            case "serve":
                return serve(options);
            default:
                throw new IllegalArgumentException("Unknown command '" + args[0] + "'. Run 'help' for usage.");
        }
    }

    /**
     * 종합 분석. --fail-on 을 주면 그 심각도 이상의 발견 사항이 있을 때 종료 코드 1 (CI용).
     */
    private static int analyze(Options options) throws IOException {
        if (options.files.isEmpty()) {
            throw new IllegalArgumentException("analyze needs at least one file");
        }
        String format = options.value("--format", "text");
        String failOn = options.value("--fail-on", null);
        Severity threshold = failOn == null ? null : Severity.valueOf(failOn.toUpperCase(java.util.Locale.ROOT));
        List<Object> reports = new ArrayList<>();
        boolean failed = false;
        for (SourceFile file : options.sourceFiles()) {
            AnalysisReport report = CodeAnalyzer.analyze(file);
            Severity highest = report.highestSeverity();
            if (threshold != null && highest != null && highest.compareTo(threshold) >= 0) {
                failed = true;
            }
            if (format.equals("json")) {
                reports.add(JsonMapper.toMap(report));
            } else if (format.equals("text")) {
                printReport(report);
            } else {
                throw new IllegalArgumentException("Unknown format '" + format + "' (text, json)");
            }
        }
        if (format.equals("json")) {
            System.out.println(Json.writePretty(reports.size() == 1 ? reports.get(0) : reports));
        }
        return failed ? 1 : 0;
    }

    private static void printReport(AnalysisReport report) {
        System.out.println("== " + report.sourceName() + " (" + report.language().displayName() + ")");
        System.out.printf("   difficulty %s, max complexity %d, %d functions, %d classes%s%n",
                report.metrics().difficulty(), report.metrics().maxCyclomaticComplexity(), report.functionCount(),
                report.classCount(), report.parseErrors().isEmpty() ? "" : ", " + report.parseErrors().size()
                        + " parse errors");
        if (!report.rulesApplied()) {
            System.out.println("   (rules are not available for this language; metrics only)");
        }
        for (Finding finding : report.findings()) {
            System.out.printf("   %4d:%-3d %-8s %-8s %-28s %s%n", finding.line(), finding.column(), finding.severity(),
                    finding.cwe() == null ? "" : finding.cwe(), finding.ruleId(), finding.message());
        }
        for (ParseError error : report.parseErrors()) {
            System.out.printf("   %4d:%-3d %-8s %-8s %-28s %s%n", error.line(), error.column(), "ERROR", "",
                    "syntax", error.message());
        }
        System.out.println("   " + report.findings().size() + " findings " + report.severityCounts());
        System.out.println();
    }

    private static int tokens(Options options) throws IOException {
        SourceFile file = options.singleFile();
        LexResult result = CodeAnalyzer.tokenize(file.content(), file.language());
        String format = options.value("--format", "text");
        switch (format) {
            case "json":
                System.out.println(Json.writePretty(JsonMapper.toMap(result)));
                break;
            case "decaf":
                System.out.print(DecafFormatter.format(result));
                break;
            case "text":
                for (Token token : result.tokens()) {
                    System.out.printf("%4d:%-4d %-16s %s%n", token.line(), token.column(), token.kind(),
                            token.text().replace("\n", "\\n").replace("\r", "\\r"));
                }
                result.errors().forEach(error -> System.err.println("error " + error));
                break;
            default:
                throw new IllegalArgumentException("Unknown format '" + format + "' (text, json, decaf)");
        }
        return result.hasErrors() ? 1 : 0;
    }

    private static int clones(Options options) throws IOException {
        if (options.files.isEmpty()) {
            throw new IllegalArgumentException("clones needs at least one file");
        }
        int minTokens = Integer.parseInt(options.value("--min-tokens", String.valueOf(CloneDetector.DEFAULT_MIN_TOKENS)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minTokens", minTokens);
        result.put("clones", JsonMapper.toList(CodeAnalyzer.findClones(options.sourceFiles(), minTokens)));
        System.out.println(Json.writePretty(result));
        return 0;
    }

    private static int serve(Options options) throws IOException {
        int port = Integer.parseInt(options.value("--port", "8080"));
        String host = options.value("--host", "127.0.0.1");
        AnalyzerServer server = new AnalyzerServer(host, port);
        server.start();
        System.out.println("Code Analyzer API listening on http://" + host + ":" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** 위치 인자(파일)와 --이름 값 옵션. */
    private static final class Options {
        final List<String> files = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for " + args[i]);
                    }
                    options.values.put(args[i], args[++i]);
                } else {
                    options.files.add(args[i]);
                }
            }
            return options;
        }

        String value(String name, String defaultValue) {
            String value = values.get(name);
            return value != null ? value : defaultValue;
        }

        SourceFile singleFile() throws IOException {
            if (files.size() != 1) {
                throw new IllegalArgumentException("Expected exactly one file");
            }
            return sourceFiles().get(0);
        }

        List<SourceFile> sourceFiles() throws IOException {
            List<SourceFile> result = new ArrayList<>();
            String language = values.get("--language");
            for (String path : files) {
                SourceFile file = SourceFile.read(Paths.get(path));
                if (language != null) {
                    file = new SourceFile(file.name(), Language.fromName(language), file.content());
                }
                result.add(file);
            }
            return result;
        }
    }
}
