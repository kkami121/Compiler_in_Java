package io.github.kkami121.codeanalyzer.lexer;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decaf 과제(PA1)의 기대 출력 형식으로 결과를 만든다.
 * <pre>
 * int            line 1 cols 1-3 is T_Int
 * a              line 1 cols 5-5 is T_Identifier (token address: 1)
 * </pre>
 * 식별자 주소는 처음 나온 순서대로 1부터 매긴다.
 */
public final class DecafFormatter {

    private DecafFormatter() {
    }

    public static String format(LexResult result) {
        List<Token> tokens = result.codeTokens();
        List<LexError> errors = result.errors();
        Map<String, Integer> addresses = new HashMap<>();
        StringBuilder out = new StringBuilder();

        // 토큰과 오류를 위치 순으로 합친다. 같은 위치면 오류가 먼저다.
        int t = 0;
        int e = 0;
        while (t < tokens.size() || e < errors.size()) {
            boolean takeError = e < errors.size()
                    && (t >= tokens.size() || compare(errors.get(e), tokens.get(t)) <= 0);
            if (takeError) {
                LexError error = errors.get(e++);
                out.append("\n*** Error line ").append(error.line()).append(".\n*** ")
                        .append(error.message()).append("\n\n");
            } else {
                out.append(formatToken(tokens.get(t++), addresses)).append('\n');
            }
        }
        return out.toString();
    }

    private static int compare(LexError error, Token token) {
        if (error.line() != token.line()) {
            return Integer.compare(error.line(), token.line());
        }
        return Integer.compare(error.column(), token.column());
    }

    private static String formatToken(Token token, Map<String, Integer> addresses) {
        String description;
        switch (token.kind()) {
            case KEYWORD:
                description = "T_" + Character.toUpperCase(token.text().charAt(0)) + token.text().substring(1);
                break;
            case IDENTIFIER:
                Integer address = addresses.get(token.text());
                if (address == null) {
                    address = addresses.size() + 1;
                    addresses.put(token.text(), address);
                }
                description = "T_Identifier (token address: " + address + ")";
                break;
            case INTEGER_LITERAL:
                description = "T_IntConstant (token value: " + integerValue(token.text()) + ")";
                break;
            case FLOAT_LITERAL:
                description = "T_DoubleConstant (token value: " + Double.parseDouble(token.text()) + ")";
                break;
            case BOOLEAN_LITERAL:
                description = "T_BoolConstant (token value: " + token.text() + ")";
                break;
            case STRING_LITERAL:
                description = "T_StringConstant (token value: " + token.text() + ")";
                break;
            case NULL_LITERAL:
                description = "T_Null";
                break;
            default:
                description = "'" + token.text() + "'";
        }
        return String.format("%-14s line %d cols %d-%d is %s",
                token.text(), token.line(), token.column(), token.endColumn(), description);
    }

    /** 10진수는 적힌 그대로(001), 16진수는 10진 값으로 바꾼다. */
    private static String integerValue(String text) {
        if (text.length() > 2 && (text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
            return new BigInteger(text.substring(2), 16).toString();
        }
        return text;
    }
}
