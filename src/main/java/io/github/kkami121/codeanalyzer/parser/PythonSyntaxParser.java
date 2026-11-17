package io.github.kkami121.codeanalyzer.parser;

import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Python 3.12 구문분석기 (재귀 하강). 식은 Python 문법의 우선순위 단계를 그대로 따른다.
 * {@code match} 문의 패턴은 세부 구조 없이 OTHER 노드로 남긴다.
 * 구문 오류가 나면 그 논리적 줄을 ERROR 노드로 남기고 다음 줄부터 계속한다.
 */
final class PythonSyntaxParser {

    private PythonSyntaxParser() {
    }

    static SyntaxTree parse(LexResult lexResult) {
        Impl impl = new Impl(lexResult.tokens());
        Node root = impl.module();
        return new SyntaxTree(lexResult, root, impl.errors);
    }

    private static final Set<String> AUGMENTED = new HashSet<>(Arrays.asList(
            "+=", "-=", "*=", "/=", "//=", "%=", "@=", "&=", "|=", "^=", ">>=", "<<=", "**="));
    private static final Set<String> COMPARISONS = new HashSet<>(Arrays.asList(
            "<", ">", "==", ">=", "<=", "!="));

    private static final class Impl extends ParserBase {

        Impl(List<Token> tokens) {
            super(tokens);
        }

        Node module() {
            Token start = peek();
            List<Node> body = new ArrayList<>();
            while (!atEnd()) {
                statementInto(body);
            }
            return node(NodeKind.MODULE, "", start, body);
        }

        // ------------------------------------------------------------ 문장

        /** 문장 하나를 읽어 out에 넣는다. 구문 오류면 줄 끝까지 건너뛰고 ERROR 노드를 넣는다. */
        private void statementInto(List<Node> out) {
            int before = pos;
            Token start = peek();
            try {
                if (checkKind(TokenKind.NEWLINE) || checkKind(TokenKind.DEDENT)) {
                    advance();
                } else if (checkKind(TokenKind.INDENT)) {
                    // 앞 줄의 구문 오류 등으로 남은 들여쓰기 블록
                    advance();
                    List<Node> body = new ArrayList<>();
                    while (!atEnd() && !checkKind(TokenKind.DEDENT)) {
                        statementInto(body);
                    }
                    match(TokenKind.DEDENT);
                    out.add(node(NodeKind.BLOCK, "", start, body));
                } else {
                    statement(out);
                }
            } catch (ParseFailure failure) {
                recordError(failure);
                skipToLineEnd();
                out.add(errorNode(failure, start));
            }
            if (pos == before && !atEnd()) {
                advance();
            }
        }

        private boolean match(TokenKind kind) {
            if (checkKind(kind)) {
                advance();
                return true;
            }
            return false;
        }

        private void skipToLineEnd() {
            while (!atEnd() && !checkKind(TokenKind.NEWLINE) && !checkKind(TokenKind.INDENT)
                    && !checkKind(TokenKind.DEDENT)) {
                advance();
            }
            match(TokenKind.NEWLINE);
        }

        private void statement(List<Node> out) {
            if (check("@")) {
                out.add(decorated());
            } else if (check("def")) {
                out.add(function(peek(), Collections.<Node>emptyList()));
            } else if (check("class")) {
                out.add(classDef(peek(), Collections.<Node>emptyList()));
            } else if (check("async") && (checkAt(1, "def") || checkAt(1, "for") || checkAt(1, "with"))) {
                Token start = advance();
                if (check("def")) {
                    out.add(function(start, Collections.<Node>emptyList()));
                } else if (check("for")) {
                    out.add(forStatement(start));
                } else {
                    out.add(withStatement(start));
                }
            } else if (check("if")) {
                out.add(ifStatement());
            } else if (check("for")) {
                out.add(forStatement(peek()));
            } else if (check("while")) {
                out.add(whileStatement());
            } else if (check("try")) {
                out.add(tryStatement());
            } else if (check("with")) {
                out.add(withStatement(peek()));
            } else if (checkIdentifier("match") && compoundLineAhead()) {
                out.add(matchStatement());
            } else {
                simpleStatements(out);
            }
        }

        /** 이 논리적 줄이 ':'로 끝나고 다음에 들여쓴 블록이 오는지 (soft keyword 판별용). */
        private boolean compoundLineAhead() {
            int depth = 0;
            for (int i = pos; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.kind() == TokenKind.NEWLINE) {
                    return i > pos && tokens.get(i - 1).isOperator(":") && depth == 0
                            && i + 1 < tokens.size() && tokens.get(i + 1).kind() == TokenKind.INDENT;
                }
                if (t.isOperator("(") || t.isOperator("[") || t.isOperator("{")) {
                    depth++;
                } else if (t.isOperator(")") || t.isOperator("]") || t.isOperator("}")) {
                    depth--;
                }
            }
            return false;
        }

        private void simpleStatements(List<Node> out) {
            while (true) {
                out.add(simpleStatement());
                if (!match(";")) {
                    break;
                }
                if (checkKind(TokenKind.NEWLINE) || atEnd()) {
                    break;
                }
            }
            if (!atEnd() && !match(TokenKind.NEWLINE)) {
                throw fail("Expected end of line");
            }
        }

        private boolean endOfSimpleStatement() {
            return atEnd() || checkKind(TokenKind.NEWLINE) || check(";") || checkKind(TokenKind.DEDENT);
        }

        private Node simpleStatement() {
            Token start = peek();
            if (match("return")) {
                return node(NodeKind.RETURN, "", start, endOfSimpleStatement() ? null : starExpressions());
            }
            if (match("raise")) {
                if (endOfSimpleStatement()) {
                    return node(NodeKind.THROW, "", start);
                }
                Node exception = expression();
                Node cause = match("from") ? expression() : null;
                return node(NodeKind.THROW, "", start, exception, cause);
            }
            if (match("pass")) {
                return node(NodeKind.PASS, "", start);
            }
            if (match("break")) {
                return node(NodeKind.BREAK, "", start);
            }
            if (match("continue")) {
                return node(NodeKind.CONTINUE, "", start);
            }
            if (check("global") || check("nonlocal")) {
                String keyword = advance().text();
                List<Node> names = new ArrayList<>();
                do {
                    names.add(leaf(NodeKind.NAME, expectIdentifier()));
                } while (match(","));
                return node(NodeKind.OTHER, keyword, start, names);
            }
            if (match("del")) {
                return node(NodeKind.OTHER, "del", start, starExpressions());
            }
            if (match("assert")) {
                Node test = expression();
                Node message = match(",") ? expression() : null;
                return node(NodeKind.ASSERT, "", start, test, message);
            }
            if (check("import")) {
                return importStatement();
            }
            if (check("from")) {
                return fromImport();
            }
            if (checkIdentifier("type") && peek(1).kind() == TokenKind.IDENTIFIER
                    && (checkAt(2, "=") || checkAt(2, "["))) {
                // type X = ... (3.12 타입 별칭)
                advance();
                Node name = leaf(NodeKind.NAME, advance());
                if (check("[")) {
                    skipBrackets("[", "]");
                }
                expect("=");
                return node(NodeKind.ASSIGN, "=", start, name, expression());
            }
            return expressionStatement();
        }

        private Node expressionStatement() {
            Token start = peek();
            Node target = starExpressions();
            if (match(":")) {
                Node annotation = node(NodeKind.TYPE, "", peek(), expression());
                Node value = match("=") ? yieldOrStarExpressions() : null;
                return node(NodeKind.ASSIGN, ":", start, target, annotation, value);
            }
            if (!atEnd() && peek().kind() == TokenKind.OPERATOR && AUGMENTED.contains(peek().text())) {
                String op = advance().text();
                return node(NodeKind.ASSIGN, op, start, target, yieldOrStarExpressions());
            }
            if (check("=")) {
                List<Node> parts = new ArrayList<>();
                parts.add(target);
                while (match("=")) {
                    parts.add(yieldOrStarExpressions());
                }
                return node(NodeKind.ASSIGN, "=", start, parts);
            }
            return node(NodeKind.EXPRESSION_STATEMENT, "", start, target);
        }

        private Node importStatement() {
            Token start = expect("import");
            List<Node> aliases = new ArrayList<>();
            do {
                Token aliasStart = peek();
                String dotted = dottedName();
                String bound = dotted.contains(".") ? dotted.substring(0, dotted.indexOf('.')) : dotted;
                if (match("as")) {
                    bound = expectIdentifier().text();
                }
                Node original = node(NodeKind.NAME, dotted, aliasStart);
                aliases.add(node(NodeKind.VARIABLE, bound, aliasStart, original));
            } while (match(","));
            return node(NodeKind.IMPORT, "", start, aliases);
        }

        private Node fromImport() {
            Token start = expect("from");
            StringBuilder module = new StringBuilder();
            while (check(".") || check("...")) {
                module.append(advance().text());
            }
            if (!check("import")) {
                module.append(dottedName());
            }
            expect("import");
            List<Node> aliases = new ArrayList<>();
            if (match("*")) {
                return node(NodeKind.IMPORT, module.toString(), start, aliases);
            }
            boolean parenthesized = match("(");
            do {
                if (parenthesized && check(")")) {
                    break;
                }
                Token aliasStart = peek();
                String name = expectIdentifier().text();
                String bound = match("as") ? expectIdentifier().text() : name;
                Node original = node(NodeKind.NAME, name, aliasStart);
                aliases.add(node(NodeKind.VARIABLE, bound, aliasStart, original));
            } while (match(","));
            if (parenthesized) {
                expect(")");
            }
            return node(NodeKind.IMPORT, module.toString(), start, aliases);
        }

        private String dottedName() {
            StringBuilder sb = new StringBuilder(expectIdentifier().text());
            while (check(".") && peek(1).kind() == TokenKind.IDENTIFIER) {
                advance();
                sb.append('.').append(advance().text());
            }
            return sb.toString();
        }

        /** ':' 다음의 블록: 들여쓴 여러 줄 또는 같은 줄의 단순 문장들. */
        private Node block() {
            Token start = expect(":");
            List<Node> body = new ArrayList<>();
            if (match(TokenKind.NEWLINE)) {
                if (!match(TokenKind.INDENT)) {
                    throw fail("Expected an indented block");
                }
                while (!atEnd() && !checkKind(TokenKind.DEDENT)) {
                    statementInto(body);
                }
                match(TokenKind.DEDENT);
            } else {
                simpleStatements(body);
            }
            return node(NodeKind.BLOCK, "", start, body);
        }

        private Node decorated() {
            Token start = peek();
            List<Node> decorators = new ArrayList<>();
            while (check("@")) {
                Token at = advance();
                Node expression = namedExpression();
                String name = expression.kind() == NodeKind.CALL
                        ? String.valueOf(expression.child(0).qualifiedName()) : String.valueOf(expression.qualifiedName());
                List<Node> args = expression.kind() == NodeKind.CALL
                        ? expression.children().subList(1, expression.children().size()) : Collections.<Node>emptyList();
                decorators.add(node(NodeKind.DECORATOR, name, at, args));
                if (!match(TokenKind.NEWLINE)) {
                    throw fail("Expected end of line after decorator");
                }
            }
            if (check("async") && checkAt(1, "def")) {
                advance();
            }
            if (check("def")) {
                return function(start, decorators);
            }
            if (check("class")) {
                return classDef(start, decorators);
            }
            throw fail("Expected 'def' or 'class' after decorator");
        }

        private Node function(Token start, List<Node> decorators) {
            enter();
            try {
                expect("def");
                String name = expectIdentifier().text();
                if (check("[")) {
                    skipBrackets("[", "]"); // 타입 매개변수 (3.12)
                }
                expect("(");
                List<Node> children = new ArrayList<>(decorators);
                children.addAll(parameters(")", true));
                expect(")");
                if (match("->")) {
                    children.add(node(NodeKind.TYPE, "", previous(), expression()));
                }
                children.add(block());
                return node(NodeKind.FUNCTION, name, start, children);
            } finally {
                exit();
            }
        }

        private List<Node> parameters(String terminator, boolean annotations) {
            List<Node> params = new ArrayList<>();
            while (!check(terminator) && !atEnd()) {
                Token start = peek();
                String prefix = "";
                if (match("**")) {
                    prefix = "**";
                } else if (match("*")) {
                    prefix = "*";
                    if (check(",") || check(terminator)) {
                        match(",");
                        continue; // 키워드 전용 표시
                    }
                } else if (match("/")) {
                    match(",");
                    continue; // 위치 전용 표시
                }
                String name = prefix + expectIdentifier().text();
                Node annotation = null;
                if (annotations && match(":")) {
                    annotation = node(NodeKind.TYPE, "", previous(), prefix.isEmpty() ? expression() : starExpression());
                }
                Node defaultValue = match("=") ? expression() : null;
                params.add(node(NodeKind.PARAMETER, name, start, annotation, defaultValue));
                if (!match(",")) {
                    break;
                }
            }
            return params;
        }

        private Node classDef(Token start, List<Node> decorators) {
            enter();
            try {
                expect("class");
                String name = expectIdentifier().text();
                if (check("[")) {
                    skipBrackets("[", "]");
                }
                List<Node> children = new ArrayList<>(decorators);
                if (match("(")) {
                    children.addAll(arguments());
                    expect(")");
                }
                children.add(block());
                return node(NodeKind.CLASS, name, start, children);
            } finally {
                exit();
            }
        }

        /** if 또는 elif 부터. elif/else는 중첩 IF/BLOCK으로 만든다. */
        private Node ifStatement() {
            enter();
            try {
                Token start = advance(); // if / elif
                Node condition = namedExpression();
                Node then = block();
                Node otherwise = null;
                if (check("elif")) {
                    otherwise = ifStatement();
                } else if (match("else")) {
                    otherwise = block();
                }
                return node(NodeKind.IF, "", start, condition, then, otherwise);
            } finally {
                exit();
            }
        }

        private Node forStatement(Token start) {
            enter();
            try {
                expect("for");
                Node target = targetList();
                expect("in");
                Node iterable = starExpressions();
                Node body = block();
                Node orElse = match("else") ? block() : null;
                return node(NodeKind.LOOP, "for", start, target, iterable, body, orElse);
            } finally {
                exit();
            }
        }

        private Node whileStatement() {
            enter();
            try {
                Token start = expect("while");
                Node condition = namedExpression();
                Node body = block();
                Node orElse = match("else") ? block() : null;
                return node(NodeKind.LOOP, "while", start, condition, body, orElse);
            } finally {
                exit();
            }
        }

        private Node tryStatement() {
            enter();
            try {
                Token start = expect("try");
                List<Node> children = new ArrayList<>();
                children.add(block());
                while (check("except")) {
                    Token exceptStart = advance();
                    match("*");
                    Node type = null;
                    String name = "";
                    if (!check(":")) {
                        type = expression();
                        if (check(",")) { // except (A, B) 대신 except A, B (3.14)
                            List<Node> types = new ArrayList<>();
                            types.add(type);
                            while (match(",")) {
                                types.add(expression());
                            }
                            type = node(NodeKind.COLLECTION, "(", exceptStart, types);
                        }
                        if (match("as")) {
                            name = expectIdentifier().text();
                        }
                    }
                    children.add(node(NodeKind.CATCH, name, exceptStart, type, block()));
                }
                if (match("else")) {
                    children.add(block());
                }
                if (check("finally")) {
                    Token finallyStart = advance();
                    children.add(node(NodeKind.FINALLY, "", finallyStart, block()));
                }
                return node(NodeKind.TRY, "", start, children);
            } finally {
                exit();
            }
        }

        private Node withStatement(Token start) {
            enter();
            try {
                expect("with");
                List<Node> items = new ArrayList<>();
                boolean parenthesized = check("(") && parenthesizedWithItems();
                if (parenthesized) {
                    advance();
                }
                do {
                    if (parenthesized && check(")")) {
                        break;
                    }
                    Token itemStart = peek();
                    Node context = expression();
                    if (match("as")) {
                        Node target = targetPrimary();
                        items.add(node(NodeKind.ASSIGN, "as", itemStart, target, context));
                    } else {
                        items.add(context);
                    }
                } while (match(","));
                if (parenthesized) {
                    expect(")");
                }
                items.add(block());
                return node(NodeKind.WITH, "", start, items);
            } finally {
                exit();
            }
        }

        /** with ( a as b, c ): 형태인지 — 괄호가 ':' 바로 앞에서 닫히고 안에 최상위 'as'가 있다. */
        private boolean parenthesizedWithItems() {
            int depth = 0;
            boolean sawAs = false;
            for (int i = pos; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.isOperator("(") || t.isOperator("[") || t.isOperator("{")) {
                    depth++;
                } else if (t.isOperator(")") || t.isOperator("]") || t.isOperator("}")) {
                    depth--;
                    if (depth == 0) {
                        return sawAs && i + 1 < tokens.size() && tokens.get(i + 1).isOperator(":");
                    }
                } else if (depth == 1 && t.isKeyword("as")) {
                    sawAs = true;
                } else if (t.kind() == TokenKind.NEWLINE) {
                    return false;
                }
            }
            return false;
        }

        private Node matchStatement() {
            enter();
            try {
                Token start = advance(); // match
                Node subject = starExpressions();
                expect(":");
                List<Node> children = new ArrayList<>();
                children.add(subject);
                if (!match(TokenKind.NEWLINE) || !match(TokenKind.INDENT)) {
                    throw fail("Expected an indented block after match");
                }
                while (!atEnd() && !checkKind(TokenKind.DEDENT)) {
                    if (!checkIdentifier("case")) {
                        throw fail("Expected 'case'");
                    }
                    Token caseStart = advance();
                    Token patternStart = peek();
                    int depth = 0;
                    while (!atEnd() && !(depth == 0 && check(":"))) {
                        Token t = advance();
                        if (t.isOperator("(") || t.isOperator("[") || t.isOperator("{")) {
                            depth++;
                        } else if (t.isOperator(")") || t.isOperator("]") || t.isOperator("}")) {
                            depth--;
                        }
                    }
                    Node pattern = node(NodeKind.OTHER, "pattern", patternStart);
                    Node body = block();
                    List<Node> caseChildren = new ArrayList<>();
                    caseChildren.add(pattern);
                    caseChildren.addAll(body.children());
                    children.add(node(NodeKind.CASE, "case", caseStart, caseChildren));
                }
                match(TokenKind.DEDENT);
                return node(NodeKind.SWITCH, "match", start, children);
            } finally {
                exit();
            }
        }

        private void skipBrackets(String open, String close) {
            expect(open);
            int depth = 1;
            while (!atEnd() && depth > 0) {
                Token t = advance();
                if (t.isOperator(open)) {
                    depth++;
                } else if (t.isOperator(close)) {
                    depth--;
                }
            }
        }

        // ------------------------------------------------------------ 식

        private boolean canStartExpression() {
            if (atEnd()) {
                return false;
            }
            Token t = peek();
            switch (t.kind()) {
                case IDENTIFIER:
                    return true;
                case KEYWORD:
                    return t.text().equals("not") || t.text().equals("lambda") || t.text().equals("await")
                            || t.text().equals("yield");
                case OPERATOR:
                    String s = t.text();
                    return s.equals("(") || s.equals("[") || s.equals("{") || s.equals("-") || s.equals("+")
                            || s.equals("~") || s.equals("*") || s.equals("...");
                default:
                    return t.kind().isLiteral();
            }
        }

        /** 쉼표로 이어진 식들 (괄호 없는 튜플). */
        private Node starExpressions() {
            Token start = peek();
            Node first = starExpression();
            if (!check(",")) {
                return first;
            }
            List<Node> items = new ArrayList<>();
            items.add(first);
            while (match(",")) {
                if (!canStartExpression()) {
                    break;
                }
                items.add(starExpression());
            }
            return node(NodeKind.COLLECTION, "(", start, items);
        }

        private Node yieldOrStarExpressions() {
            return check("yield") ? yieldExpression() : starExpressions();
        }

        private Node yieldExpression() {
            Token start = expect("yield");
            if (match("from")) {
                return node(NodeKind.UNARY, "yield from", start, expression());
            }
            if (!canStartExpression()) {
                return node(NodeKind.UNARY, "yield", start);
            }
            return node(NodeKind.UNARY, "yield", start, starExpressions());
        }

        private Node starExpression() {
            if (check("*")) {
                Token start = advance();
                return node(NodeKind.UNARY, "*", start, bitwiseOr());
            }
            return namedExpression();
        }

        private Node namedExpression() {
            if (checkKind(TokenKind.IDENTIFIER) && checkAt(1, ":=")) {
                Token start = peek();
                Node name = leaf(NodeKind.NAME, advance());
                advance();
                return node(NodeKind.ASSIGN, ":=", start, name, expression());
            }
            return expression();
        }

        /** for 대상: 비교 연산 'in'과 섞이지 않도록 bitwise_or 수준에서 읽는다. */
        private Node targetList() {
            Token start = peek();
            Node first = check("*") ? starExpression() : bitwiseOr();
            if (!check(",")) {
                return first;
            }
            List<Node> items = new ArrayList<>();
            items.add(first);
            while (match(",")) {
                if (check("in") || !canStartExpression()) {
                    break;
                }
                items.add(check("*") ? starExpression() : bitwiseOr());
            }
            return node(NodeKind.COLLECTION, "(", start, items);
        }

        private Node targetPrimary() {
            return check("(") || check("[") ? primary() : bitwiseOr();
        }

        private Node expression() {
            enter();
            try {
                if (check("lambda")) {
                    return lambda();
                }
                if (check("yield")) {
                    return yieldExpression();
                }
                Token start = peek();
                Node value = disjunction();
                if (check("if")) {
                    advance();
                    Node condition = disjunction();
                    expect("else");
                    Node otherwise = expression();
                    return node(NodeKind.TERNARY, "", start, condition, value, otherwise);
                }
                return value;
            } finally {
                exit();
            }
        }

        private Node lambda() {
            Token start = expect("lambda");
            List<Node> children = new ArrayList<>(parameters(":", false));
            expect(":");
            children.add(expression());
            return node(NodeKind.LAMBDA, "", start, children);
        }

        private Node disjunction() {
            Token start = peek();
            Node left = conjunction();
            while (check("or")) {
                advance();
                left = node(NodeKind.BINARY, "or", start, left, conjunction());
            }
            return left;
        }

        private Node conjunction() {
            Token start = peek();
            Node left = inversion();
            while (check("and")) {
                advance();
                left = node(NodeKind.BINARY, "and", start, left, inversion());
            }
            return left;
        }

        private Node inversion() {
            if (check("not")) {
                Token start = advance();
                enter();
                try {
                    return node(NodeKind.UNARY, "not", start, inversion());
                } finally {
                    exit();
                }
            }
            return comparison();
        }

        private Node comparison() {
            Token start = peek();
            Node left = bitwiseOr();
            while (true) {
                String op;
                if (!atEnd() && peek().kind() == TokenKind.OPERATOR && COMPARISONS.contains(peek().text())) {
                    op = advance().text();
                } else if (check("in")) {
                    advance();
                    op = "in";
                } else if (check("not") && checkAt(1, "in")) {
                    advance();
                    advance();
                    op = "not in";
                } else if (check("is")) {
                    advance();
                    op = match("not") ? "is not" : "is";
                } else {
                    return left;
                }
                left = node(NodeKind.BINARY, op, start, left, bitwiseOr());
            }
        }

        private Node bitwiseOr() {
            return binaryLevel(0);
        }

        private static final String[][] BINARY_LEVELS = {
                {"|"}, {"^"}, {"&"}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "//", "%", "@"}
        };

        private Node binaryLevel(int level) {
            if (level == BINARY_LEVELS.length) {
                return factor();
            }
            Token start = peek();
            Node left = binaryLevel(level + 1);
            while (true) {
                String op = null;
                for (String candidate : BINARY_LEVELS[level]) {
                    if (check(candidate)) {
                        op = candidate;
                        break;
                    }
                }
                if (op == null) {
                    return left;
                }
                advance();
                left = node(NodeKind.BINARY, op, start, left, binaryLevel(level + 1));
            }
        }

        private Node factor() {
            if (check("+") || check("-") || check("~")) {
                Token start = advance();
                enter();
                try {
                    return node(NodeKind.UNARY, start.text(), start, factor());
                } finally {
                    exit();
                }
            }
            return power();
        }

        private Node power() {
            Token start = peek();
            Node base;
            if (check("await")) {
                advance();
                base = node(NodeKind.UNARY, "await", start, primary());
            } else {
                base = primary();
            }
            if (match("**")) {
                return node(NodeKind.BINARY, "**", start, base, factor());
            }
            return base;
        }

        private Node primary() {
            enter();
            try {
                Token start = peek();
                Node value = atom();
                while (true) {
                    if (check(".") && peek(1).kind() == TokenKind.IDENTIFIER) {
                        advance();
                        value = node(NodeKind.ATTRIBUTE, advance().text(), start, value);
                    } else if (match("(")) {
                        List<Node> children = new ArrayList<>();
                        children.add(value);
                        children.addAll(arguments());
                        expect(")");
                        value = node(NodeKind.CALL, "", start, children);
                    } else if (match("[")) {
                        List<Node> children = new ArrayList<>();
                        children.add(value);
                        do {
                            if (check("]")) {
                                break;
                            }
                            children.add(sliceItem());
                        } while (match(","));
                        expect("]");
                        value = node(NodeKind.INDEX, "", start, children);
                    } else {
                        return value;
                    }
                }
            } finally {
                exit();
            }
        }

        private Node sliceItem() {
            Token start = peek();
            Node lower = check(":") ? null : starExpression();
            if (!check(":")) {
                return lower;
            }
            advance();
            Node upper = check(":") || check("]") || check(",") ? null : expression();
            Node step = null;
            if (match(":")) {
                step = check("]") || check(",") ? null : expression();
            }
            return node(NodeKind.OTHER, "slice", start, lower, upper, step);
        }

        /** 호출 인자 목록 (여는 괄호 다음부터 닫는 괄호 앞까지). */
        private List<Node> arguments() {
            List<Node> args = new ArrayList<>();
            while (!check(")") && !atEnd()) {
                Token start = peek();
                if (match("**")) {
                    args.add(node(NodeKind.UNARY, "**", start, expression()));
                } else if (match("*")) {
                    args.add(node(NodeKind.UNARY, "*", start, expression()));
                } else if (checkKind(TokenKind.IDENTIFIER) && checkAt(1, "=")) {
                    String name = advance().text();
                    advance();
                    args.add(node(NodeKind.ARGUMENT, name, start, expression()));
                } else {
                    Node value = namedExpression();
                    if (check("for") || (check("async") && checkAt(1, "for"))) {
                        value = comprehension(start, value);
                    }
                    args.add(value);
                }
                if (!match(",")) {
                    break;
                }
            }
            return args;
        }

        private Node atom() {
            Token t = peek();
            if (atEnd()) {
                throw fail("Expected expression");
            }
            if (t.kind() == TokenKind.IDENTIFIER) {
                return leaf(NodeKind.NAME, advance());
            }
            if (t.kind() == TokenKind.STRING_LITERAL) {
                // 이어 붙인 문자열 "a" "b" 는 하나의 리터럴로
                Token first = advance();
                StringBuilder text = new StringBuilder(first.text());
                while (checkKind(TokenKind.STRING_LITERAL)) {
                    text.append(' ').append(advance().text());
                }
                Token last = previous();
                return new Node(NodeKind.LITERAL, text.toString(), TokenKind.STRING_LITERAL,
                        Collections.<Node>emptyList(), first.line(), first.column(), last.endLine(), last.endColumn());
            }
            if (isLiteral(t)) {
                return leaf(NodeKind.LITERAL, advance());
            }
            if (check("...")) {
                return leaf(NodeKind.LITERAL, advance());
            }
            if (check("(")) {
                Token start = advance();
                if (match(")")) {
                    return node(NodeKind.COLLECTION, "(", start);
                }
                if (check("yield")) {
                    Node value = yieldExpression();
                    expect(")");
                    return value;
                }
                Node first = starNamedOrStar();
                if (check("for") || (check("async") && checkAt(1, "for"))) {
                    Node generator = comprehension(start, first);
                    expect(")");
                    return generator;
                }
                if (!check(",")) {
                    expect(")");
                    return first;
                }
                List<Node> items = new ArrayList<>();
                items.add(first);
                while (match(",")) {
                    if (check(")")) {
                        break;
                    }
                    items.add(starNamedOrStar());
                }
                expect(")");
                return node(NodeKind.COLLECTION, "(", start, items);
            }
            if (check("[")) {
                Token start = advance();
                return collection(start, "[", "]");
            }
            if (check("{")) {
                Token start = advance();
                return collection(start, "{", "}");
            }
            throw fail("Expected expression");
        }

        private Node starNamedOrStar() {
            return check("*") ? starExpression() : namedExpression();
        }

        private Node collection(Token start, String open, String close) {
            List<Node> items = new ArrayList<>();
            boolean first = true;
            while (!check(close) && !atEnd()) {
                Token itemStart = peek();
                Node item;
                if (open.equals("{") && match("**")) {
                    item = node(NodeKind.UNARY, "**", itemStart, bitwiseOr());
                } else {
                    item = starNamedOrStar();
                    if (open.equals("{") && match(":")) {
                        item = node(NodeKind.BINARY, ":", itemStart, item, expression());
                    }
                }
                if (first && (check("for") || (check("async") && checkAt(1, "for")))) {
                    Node comprehension = comprehension(start, item);
                    expect(close);
                    return comprehension;
                }
                first = false;
                items.add(item);
                if (!match(",")) {
                    break;
                }
            }
            expect(close);
            return node(NodeKind.COLLECTION, open, start, items);
        }

        private Node comprehension(Token start, Node element) {
            List<Node> children = new ArrayList<>();
            children.add(element);
            while (check("for") || (check("async") && checkAt(1, "for"))) {
                if (check("async")) {
                    advance();
                }
                advance(); // for
                children.add(targetList());
                expect("in");
                children.add(disjunction());
                while (check("if")) {
                    advance();
                    children.add(disjunction());
                }
            }
            return node(NodeKind.COMPREHENSION, "", start, children);
        }
    }
}
