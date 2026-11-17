package io.github.kkami121.codeanalyzer.metrics;

/**
 * 난이도 등급. 판정 기준은 {@link CodeMetrics#difficulty()} 참고.
 */
public enum DifficultyLevel {
    EASY,
    MODERATE,
    HARD,
    VERY_HARD;

    static DifficultyLevel max(DifficultyLevel a, DifficultyLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** 값이 easyMax 이하면 EASY, moderateMax 이하면 MODERATE, hardMax 이하면 HARD, 그보다 크면 VERY_HARD. */
    static DifficultyLevel grade(double value, double easyMax, double moderateMax, double hardMax) {
        if (value <= easyMax) {
            return EASY;
        }
        if (value <= moderateMax) {
            return MODERATE;
        }
        if (value <= hardMax) {
            return HARD;
        }
        return VERY_HARD;
    }
}
