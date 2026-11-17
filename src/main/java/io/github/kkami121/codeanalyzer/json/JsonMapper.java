package io.github.kkami121.codeanalyzer.json;

import io.github.kkami121.codeanalyzer.clone.ClonePair;
import io.github.kkami121.codeanalyzer.clone.CodeFragment;
import io.github.kkami121.codeanalyzer.lexer.LexError;
import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.metrics.CodeMetrics;
import io.github.kkami121.codeanalyzer.metrics.FunctionMetrics;
import io.github.kkami121.codeanalyzer.metrics.Halstead;
import io.github.kkami121.codeanalyzer.metrics.LineMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 분석 결과를 JSON으로 쓸 수 있는 Map/List 구조로 바꾼다. */
public final class JsonMapper {

    private JsonMapper() {
    }

    public static Map<String, Object> toMap(LexResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("language", result.language());
        List<Object> tokens = new ArrayList<>();
        for (Token token : result.tokens()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("kind", token.kind());
            t.put("text", token.text());
            t.put("line", token.line());
            t.put("column", token.column());
            t.put("endLine", token.endLine());
            t.put("endColumn", token.endColumn());
            tokens.add(t);
        }
        map.put("tokens", tokens);
        map.put("errors", errors(result.errors()));
        return map;
    }

    public static Map<String, Object> toMap(CodeMetrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("language", metrics.language());
        map.put("difficulty", metrics.difficulty());
        map.put("difficultyReasons", metrics.difficultyReasons());
        LineMetrics lines = metrics.lines();
        Map<String, Object> lineMap = new LinkedHashMap<>();
        lineMap.put("total", lines.total());
        lineMap.put("code", lines.code());
        lineMap.put("comment", lines.comment());
        lineMap.put("blank", lines.blank());
        map.put("lines", lineMap);
        map.put("tokenCount", metrics.tokenCount());
        map.put("lexErrorCount", metrics.lexErrorCount());
        map.put("maxCyclomaticComplexity", metrics.maxCyclomaticComplexity());
        map.put("averageCyclomaticComplexity", round(metrics.averageCyclomaticComplexity()));
        map.put("topLevelComplexity", metrics.topLevelComplexity());
        map.put("maxNestingDepth", metrics.maxNestingDepth());
        map.put("halstead", toMap(metrics.halstead()));
        List<Object> functions = new ArrayList<>();
        for (FunctionMetrics function : metrics.functions()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", function.name());
            f.put("startLine", function.startLine());
            f.put("endLine", function.endLine());
            f.put("cyclomaticComplexity", function.cyclomaticComplexity());
            f.put("maxNestingDepth", function.maxNestingDepth());
            f.put("halsteadDifficulty", round(function.halstead().difficulty()));
            functions.add(f);
        }
        map.put("functions", functions);
        return map;
    }

    public static Map<String, Object> toMap(Halstead h) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("distinctOperators", h.distinctOperators());
        map.put("distinctOperands", h.distinctOperands());
        map.put("totalOperators", h.totalOperators());
        map.put("totalOperands", h.totalOperands());
        map.put("vocabulary", h.vocabulary());
        map.put("length", h.length());
        map.put("volume", round(h.volume()));
        map.put("difficulty", round(h.difficulty()));
        map.put("effort", round(h.effort()));
        map.put("timeSeconds", round(h.timeSeconds()));
        map.put("estimatedBugs", round(h.estimatedBugs()));
        return map;
    }

    public static List<Object> toList(List<ClonePair> clones) {
        List<Object> list = new ArrayList<>();
        for (ClonePair pair : clones) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", pair.type());
            map.put("tokenCount", pair.tokenCount());
            map.put("first", toMap(pair.first()));
            map.put("second", toMap(pair.second()));
            list.add(map);
        }
        return list;
    }

    public static Map<String, Object> toMap(CodeFragment fragment) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", fragment.sourceName());
        map.put("startLine", fragment.startLine());
        map.put("startColumn", fragment.startColumn());
        map.put("endLine", fragment.endLine());
        map.put("endColumn", fragment.endColumn());
        return map;
    }

    private static List<Object> errors(List<LexError> errors) {
        List<Object> list = new ArrayList<>();
        for (LexError error : errors) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("line", error.line());
            e.put("column", error.column());
            e.put("message", error.message());
            list.add(e);
        }
        return list;
    }

    /** 소수점 아래 3자리. */
    static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
