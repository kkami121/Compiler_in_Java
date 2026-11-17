package io.github.kkami121.codeanalyzer.metrics;

import java.util.ArrayList;
import java.util.List;

/** 토큰 목록에서 찾아낸 함수 범위와 중첩 정보. 지표 계산에서만 쓴다. */
final class CodeStructure {

    /** 함수 하나의 토큰 범위 [start, end] (토큰 목록 인덱스, 끝 포함). */
    static final class FunctionSpan {
        final String name;
        final int start;
        int end = -1;
        int maxNesting;

        FunctionSpan(String name, int start) {
            this.name = name;
            this.start = start;
        }
    }

    final List<FunctionSpan> functions = new ArrayList<>();
    int topLevelMaxNesting;

    /** 블록 중첩 깊이를 기록한다. owner가 null이면 함수 밖. */
    void recordNesting(FunctionSpan owner, int depth) {
        if (owner != null) {
            owner.maxNesting = Math.max(owner.maxNesting, depth);
        } else {
            topLevelMaxNesting = Math.max(topLevelMaxNesting, depth);
        }
    }
}
