package io.github.kkami121.codeanalyzer.metrics;

import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.HashSet;
import java.util.Set;

/**
 * Halstead 지표. 키워드와 연산자/구분자를 연산자로, 식별자와 리터럴을 피연산자로 센다.
 * 닫는 괄호 {@code ) ] }}는 여는 괄호와 한 쌍으로 보고 세지 않는다.
 *
 * <ul>
 *   <li>n1, n2: 서로 다른 연산자/피연산자 수</li>
 *   <li>N1, N2: 전체 연산자/피연산자 수</li>
 *   <li>Volume V = (N1+N2) × log2(n1+n2)</li>
 *   <li>Difficulty D = (n1 / 2) × (N2 / n2)</li>
 *   <li>Effort E = D × V, Time T = E / 18 (초), Bugs B = V / 3000</li>
 * </ul>
 */
public final class Halstead {

    private final int distinctOperators;
    private final int distinctOperands;
    private final int totalOperators;
    private final int totalOperands;

    public Halstead(int distinctOperators, int distinctOperands, int totalOperators, int totalOperands) {
        this.distinctOperators = distinctOperators;
        this.distinctOperands = distinctOperands;
        this.totalOperators = totalOperators;
        this.totalOperands = totalOperands;
    }

    /** 코드 토큰들로 계산한다. 주석과 레이아웃 토큰은 무시한다. */
    public static Halstead of(Iterable<Token> tokens) {
        Set<String> operators = new HashSet<>();
        Set<String> operands = new HashSet<>();
        int totalOperators = 0;
        int totalOperands = 0;
        for (Token token : tokens) {
            if (isOperand(token)) {
                operands.add(token.text());
                totalOperands++;
            } else if (isOperator(token)) {
                operators.add(token.text());
                totalOperators++;
            }
        }
        return new Halstead(operators.size(), operands.size(), totalOperators, totalOperands);
    }

    static boolean isOperand(Token token) {
        return token.kind() == TokenKind.IDENTIFIER || token.kind().isLiteral();
    }

    static boolean isOperator(Token token) {
        if (token.kind() == TokenKind.KEYWORD) {
            return true;
        }
        if (token.kind() != TokenKind.OPERATOR) {
            return false;
        }
        String text = token.text();
        return !(text.equals(")") || text.equals("]") || text.equals("}"));
    }

    /** n1 */
    public int distinctOperators() {
        return distinctOperators;
    }

    /** n2 */
    public int distinctOperands() {
        return distinctOperands;
    }

    /** N1 */
    public int totalOperators() {
        return totalOperators;
    }

    /** N2 */
    public int totalOperands() {
        return totalOperands;
    }

    /** n = n1 + n2 */
    public int vocabulary() {
        return distinctOperators + distinctOperands;
    }

    /** N = N1 + N2 */
    public int length() {
        return totalOperators + totalOperands;
    }

    public double volume() {
        int vocabulary = vocabulary();
        return vocabulary < 2 ? 0 : length() * (Math.log(vocabulary) / Math.log(2));
    }

    /** 코드를 이해하거나 작성하기 어려운 정도. */
    public double difficulty() {
        return distinctOperands == 0 ? 0 : (distinctOperators / 2.0) * ((double) totalOperands / distinctOperands);
    }

    public double effort() {
        return difficulty() * volume();
    }

    /** 작성에 드는 추정 시간(초). */
    public double timeSeconds() {
        return effort() / 18.0;
    }

    /** 추정 버그 수. */
    public double estimatedBugs() {
        return volume() / 3000.0;
    }
}
