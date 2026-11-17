package io.github.kkami121.codeanalyzer.clone;

import io.github.kkami121.codeanalyzer.SourceFile;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 토큰 기반 코드 클론 탐지기.
 *
 * <p>소스를 토큰으로 나눈 뒤 식별자는 {@code ID}, 리터럴은 {@code LIT}로 바꿔(정규화) 비교한다.
 * 그래서 변수 이름이나 상수 값만 바꾼 복사 코드(Type-2)도 찾는다. 주석과 공백, 줄바꿈은 무시한다.
 *
 * <ul>
 *   <li>{@link #detect(List)}: 길이가 {@code minTokens} 이상인 일치 구간을 모두 찾는다 (같은 파일 안도 포함).
 *       정규화한 토큰 창(window)의 롤링 해시로 후보를 찾고, 실제로 같은지 확인한 뒤 최대한 늘린다.</li>
 *   <li>{@link #similarity(SourceFile, SourceFile)}: 두 소스가 얼마나 비슷한지 0~1로 계산한다.
 *       정규화한 토큰 k-gram 집합의 Jaccard 유사도라서, 문장이 조금 추가되거나 빠진 경우(Type-3)도 점수에 반영된다.</li>
 * </ul>
 * 상태가 없으므로 여러 스레드에서 같이 써도 된다.
 */
public final class CloneDetector {

    /** 기본 최소 클론 길이(토큰). 대략 3~5줄 정도의 코드. */
    public static final int DEFAULT_MIN_TOKENS = 30;

    /** {@link #similarity}에서 쓰는 k-gram 길이. */
    public static final int SIMILARITY_KGRAM = 5;

    /** 같은 해시가 너무 많이 나오는 반복 코드에서 비교 횟수가 폭발하지 않도록 제한한다. */
    private static final int MAX_OCCURRENCES_PER_HASH = 64;

    private static final long BASE = 1_000_003L;

    private final int minTokens;

    public CloneDetector() {
        this(DEFAULT_MIN_TOKENS);
    }

    /** @param minTokens 클론으로 볼 최소 토큰 수 (2 이상) */
    public CloneDetector(int minTokens) {
        if (minTokens < 2) {
            throw new IllegalArgumentException("minTokens must be at least 2: " + minTokens);
        }
        this.minTokens = minTokens;
    }

    public int minTokens() {
        return minTokens;
    }

    /** 여러 소스 사이와 각 소스 안의 클론을 찾는다. 결과는 긴 것부터 정렬된다. */
    public List<ClonePair> detect(List<SourceFile> files) {
        Map<String, Integer> vocabulary = new HashMap<>();
        List<Document> documents = new ArrayList<>();
        for (SourceFile file : files) {
            documents.add(new Document(file, vocabulary));
        }

        // 창 해시 → 위치들 (문서 번호 << 32 | 토큰 위치)
        Map<Long, List<Long>> buckets = new HashMap<>();
        for (int d = 0; d < documents.size(); d++) {
            Document doc = documents.get(d);
            for (int p = 0; p + minTokens <= doc.size(); p++) {
                List<Long> bucket = buckets.get(doc.hash(p, minTokens));
                if (bucket == null) {
                    bucket = new ArrayList<>(2);
                    buckets.put(doc.hash(p, minTokens), bucket);
                }
                if (bucket.size() < MAX_OCCURRENCES_PER_HASH) {
                    bucket.add(((long) d << 32) | p);
                }
            }
        }

        List<ClonePair> pairs = new ArrayList<>();
        for (List<Long> bucket : buckets.values()) {
            for (int a = 0; a < bucket.size(); a++) {
                for (int b = a + 1; b < bucket.size(); b++) {
                    ClonePair pair = match(documents, bucket.get(a), bucket.get(b));
                    if (pair != null) {
                        pairs.add(pair);
                    }
                }
            }
        }
        pairs.sort(Comparator.comparingInt(ClonePair::tokenCount).reversed()
                .thenComparing(p -> p.first().sourceName())
                .thenComparingInt(p -> p.first().startLine())
                .thenComparing(p -> p.second().sourceName())
                .thenComparingInt(p -> p.second().startLine()));
        return Collections.unmodifiableList(pairs);
    }

    private ClonePair match(List<Document> documents, long occurrenceA, long occurrenceB) {
        int d1 = (int) (occurrenceA >>> 32);
        int p1 = (int) occurrenceA;
        int d2 = (int) (occurrenceB >>> 32);
        int p2 = (int) occurrenceB;
        Document a = documents.get(d1);
        Document b = documents.get(d2);
        boolean sameDocument = d1 == d2;
        if (sameDocument && p2 - p1 < minTokens) {
            return null; // 겹치는 구간
        }
        // 바로 앞 토큰도 같으면 더 앞에서 시작하는 같은 클론이 이미 있다
        if (p1 > 0 && p2 > 0 && a.ids[p1 - 1] == b.ids[p2 - 1]) {
            return null;
        }
        for (int i = 0; i < minTokens; i++) {
            if (a.ids[p1 + i] != b.ids[p2 + i]) {
                return null; // 해시 충돌
            }
        }
        int length = minTokens;
        while (p1 + length < a.size() && p2 + length < b.size() && a.ids[p1 + length] == b.ids[p2 + length]
                && (!sameDocument || p1 + length < p2)) {
            length++;
        }

        boolean identical = true;
        for (int i = 0; i < length && identical; i++) {
            identical = a.tokens.get(p1 + i).text().equals(b.tokens.get(p2 + i).text());
        }
        return new ClonePair(a.fragment(p1, length), b.fragment(p2, length), length,
                identical ? CloneType.TYPE_1 : CloneType.TYPE_2);
    }

    /**
     * 두 소스의 구조적 유사도 (0~1). 정규화한 토큰 {@value #SIMILARITY_KGRAM}-gram 집합의 Jaccard 계수.
     * 둘 다 비어 있으면 1, 한쪽만 비어 있으면 0.
     */
    public double similarity(SourceFile first, SourceFile second) {
        Map<String, Integer> vocabulary = new HashMap<>();
        Set<Long> a = new Document(first, vocabulary).kgrams(SIMILARITY_KGRAM);
        Set<Long> b = new Document(second, vocabulary).kgrams(SIMILARITY_KGRAM);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int common = 0;
        for (Long hash : a) {
            if (b.contains(hash)) {
                common++;
            }
        }
        return (double) common / (a.size() + b.size() - common);
    }

    /** 정규화한 토큰 열과 접두 해시. */
    private static final class Document {
        final SourceFile file;
        final List<Token> tokens;
        final int[] ids;
        private final long[] prefix;
        private final long[] power;

        Document(SourceFile file, Map<String, Integer> vocabulary) {
            this.file = file;
            this.tokens = file.language().lexer().tokenize(file.content()).codeTokens();
            this.ids = new int[tokens.size()];
            for (int i = 0; i < ids.length; i++) {
                String key = normalize(tokens.get(i));
                Integer id = vocabulary.get(key);
                if (id == null) {
                    id = vocabulary.size() + 1;
                    vocabulary.put(key, id);
                }
                ids[i] = id;
            }
            prefix = new long[ids.length + 1];
            power = new long[ids.length + 1];
            power[0] = 1;
            for (int i = 0; i < ids.length; i++) {
                prefix[i + 1] = prefix[i] * BASE + ids[i];
                power[i + 1] = power[i] * BASE;
            }
        }

        int size() {
            return ids.length;
        }

        /** ids[start, start+length)의 해시. long 오버플로는 의도한 것(mod 2^64). */
        long hash(int start, int length) {
            return prefix[start + length] - prefix[start] * power[length];
        }

        Set<Long> kgrams(int k) {
            Set<Long> result = new HashSet<>();
            if (size() > 0 && size() < k) {
                result.add(hash(0, size()));
            }
            for (int p = 0; p + k <= size(); p++) {
                result.add(hash(p, k));
            }
            return result;
        }

        CodeFragment fragment(int start, int length) {
            Token first = tokens.get(start);
            Token last = tokens.get(start + length - 1);
            return new CodeFragment(file.name(), first.line(), first.column(), last.endLine(), last.endColumn());
        }

        private static String normalize(Token token) {
            if (token.kind() == TokenKind.IDENTIFIER) {
                return "ID";
            }
            if (token.kind().isLiteral()) {
                return "LIT";
            }
            return token.kind().name() + ":" + token.text();
        }
    }
}
