package io.github.kkami121.codeanalyzer.parser;

import io.github.kkami121.codeanalyzer.lexer.LexResult;
import io.github.kkami121.codeanalyzer.lexer.Token;
import io.github.kkami121.codeanalyzer.lexer.TokenKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 21 구문분석기 (재귀 하강 + 우선순위 상승법).
 *
 * <p>선언, 문장, 식을 공통 AST로 만든다. 타입은 TYPE 노드의 문자열로만 남긴다.
 * switch의 case 레이블과 패턴은 세부 구조 없이 건너뛴다.
 * 구문 오류가 나면 다음 ';' 또는 블록 경계까지 건너뛰고 계속한다.
 */
final class JavaSyntaxParser {

    private JavaSyntaxParser() {
    }

    static SyntaxTree parse(LexResult lexResult) {
        Impl impl = new Impl(lexResult.tokens());
        Node root = impl.compilationUnit();
        return new SyntaxTree(lexResult, root, impl.errors);
    }

    private static final Set<String> PRIMITIVES = new HashSet<>(Arrays.asList(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"));
    private static final Set<String> MODIFIERS = new HashSet<>(Arrays.asList(
            "public", "protected", "private", "static", "abstract", "final", "native", "synchronized",
            "transient", "volatile", "strictfp", "default"));
    private static final Set<String> ASSIGNMENT_OPERATORS = new HashSet<>(Arrays.asList(
            "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>="));
    private static final Map<String, Integer> BINARY_PRECEDENCE = new HashMap<>();

    static {
        String[][] levels = {
                {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">=", "instanceof"},
                {"<<", ">>", ">>>"}, {"+", "-"}, {"*", "/", "%"}
        };
        for (int i = 0; i < levels.length; i++) {
            for (String op : levels[i]) {
                BINARY_PRECEDENCE.put(op, i + 1);
            }
        }
    }

    private static final class Impl extends ParserBase {

        /** '>>' 같은 토큰을 타입 인자의 닫는 꺾쇠로 나눠 읽을 때, 현재 토큰에서 이미 쓴 '>' 수. */
        private int angleSplit = 0;

        Impl(List<Token> tokens) {
            super(tokens);
        }

        // ------------------------------------------------------------ 백트래킹

        private int[] save() {
            return new int[]{pos, angleSplit, errors.size()};
        }

        private void restore(int[] state) {
            pos = state[0];
            angleSplit = state[1];
            while (errors.size() > state[2]) {
                errors.remove(errors.size() - 1);
            }
        }

        // ------------------------------------------------------------ 파일 / 선언

        Node compilationUnit() {
            Token start = peek();
            List<Node> body = new ArrayList<>();
            while (!atEnd()) {
                int before = pos;
                Token itemStart = peek();
                try {
                    if (match(";")) {
                        continue;
                    }
                    List<Node> annotations = annotations();
                    if (check("package")) {
                        advance();
                        String name = qualifiedName();
                        expect(";");
                        body.add(node(NodeKind.OTHER, "package " + name, itemStart));
                    } else if (check("import")) {
                        body.add(importDeclaration());
                    } else if (checkIdentifier("module") || checkIdentifier("open")) {
                        skipModuleDeclaration();
                    } else {
                        body.addAll(memberDeclaration(itemStart, annotations, null));
                    }
                } catch (ParseFailure failure) {
                    recordError(failure);
                    recover();
                    body.add(errorNode(failure, itemStart));
                }
                if (pos == before && !atEnd()) {
                    advance();
                }
            }
            return node(NodeKind.MODULE, "", start, body);
        }

        private void skipModuleDeclaration() {
            while (!atEnd() && !check("{")) {
                advance();
            }
            if (check("{")) {
                skipBalanced("{", "}");
            }
        }

        private Node importDeclaration() {
            Token start = expect("import");
            match("static");
            StringBuilder name = new StringBuilder(expectIdentifier().text());
            boolean wildcard = false;
            while (match(".")) {
                if (match("*")) {
                    wildcard = true;
                    break;
                }
                name.append('.').append(expectIdentifier().text());
            }
            expect(";");
            String full = name.toString();
            List<Node> aliases = new ArrayList<>();
            if (!wildcard) {
                String simple = full.substring(full.lastIndexOf('.') + 1);
                aliases.add(node(NodeKind.VARIABLE, simple, start, node(NodeKind.NAME, full, start)));
            }
            return node(NodeKind.IMPORT, wildcard ? full + ".*" : "", start, aliases);
        }

        /** 오류 뒤 복구: 같은 깊이의 ';'까지 또는 닫는 '}' 앞까지 건너뛴다. */
        private void recover() {
            angleSplit = 0;
            int depth = 0;
            while (!atEnd()) {
                if (check("{")) {
                    depth++;
                } else if (check("}")) {
                    if (depth == 0) {
                        return;
                    }
                    depth--;
                    if (depth == 0) {
                        advance();
                        return;
                    }
                } else if (check(";") && depth == 0) {
                    advance();
                    return;
                }
                advance();
            }
        }

        private List<Node> annotations() {
            List<Node> result = new ArrayList<>();
            while (check("@") && !checkAt(1, "interface")) {
                result.add(annotation());
            }
            return result;
        }

        private Node annotation() {
            Token start = expect("@");
            String name = qualifiedName();
            List<Node> args = new ArrayList<>();
            if (match("(")) {
                while (!check(")") && !atEnd()) {
                    Token argStart = peek();
                    if (checkKind(TokenKind.IDENTIFIER) && checkAt(1, "=")) {
                        String key = advance().text();
                        advance();
                        args.add(node(NodeKind.ARGUMENT, key, argStart, elementValue()));
                    } else {
                        args.add(elementValue());
                    }
                    if (!match(",")) {
                        break;
                    }
                }
                expect(")");
            }
            return node(NodeKind.DECORATOR, name, start, args);
        }

        private Node elementValue() {
            if (check("@")) {
                return annotation();
            }
            if (check("{")) {
                return arrayInitializer();
            }
            return ternary();
        }

        /** 수식어와 어노테이션을 읽는다. 어노테이션 노드를 돌려준다. */
        private List<Node> modifiers(List<Node> annotations) {
            List<Node> result = new ArrayList<>(annotations);
            while (true) {
                if (check("@") && !checkAt(1, "interface")) {
                    result.add(annotation());
                } else if (!atEnd() && peek().kind() == TokenKind.KEYWORD && MODIFIERS.contains(peek().text())
                        && !(check("default") && (checkAt(1, ":") || checkAt(1, "->")))) {
                    advance();
                } else if (checkIdentifier("sealed") && isTypeStartAfterModifier(1)) {
                    advance();
                } else if (checkIdentifier("non") && checkAt(1, "-") && peek(2).text().equals("sealed")) {
                    advance(); advance(); advance();
                } else {
                    return result;
                }
            }
        }

        private boolean isTypeStartAfterModifier(int offset) {
            Token t = peek(offset);
            return t.kind() == TokenKind.KEYWORD || (t.kind() == TokenKind.IDENTIFIER
                    && (t.text().equals("record") || t.text().equals("non")));
        }

        private boolean atTypeDeclaration() {
            if (check("class") || check("interface") || check("enum")) {
                return true;
            }
            if (check("@") && checkAt(1, "interface")) {
                return true;
            }
            return checkIdentifier("record") && peek(1).kind() == TokenKind.IDENTIFIER
                    && (checkAt(2, "(") || checkAt(2, "<"));
        }

        /** 클래스 멤버 하나 (또는 최상위 선언). 필드 선언은 변수 수만큼 노드를 돌려준다. */
        private List<Node> memberDeclaration(Token start, List<Node> leadingAnnotations, String className) {
            List<Node> decorators = modifiers(leadingAnnotations);
            if (atTypeDeclaration()) {
                return Collections.singletonList(typeDeclaration(start, decorators));
            }
            if (className == null) {
                throw fail("Expected type declaration");
            }
            if (check("{")) {
                return Collections.singletonList(block()); // 초기화 블록
            }
            if (check("<")) {
                typeArguments(new StringBuilder()); // 제네릭 메서드의 타입 매개변수
            }
            List<Node> children = new ArrayList<>(decorators);
            if (checkKind(TokenKind.IDENTIFIER) && checkAt(1, "(")) {
                String name = advance().text(); // 생성자
                children.addAll(formalParameters());
                throwsClause();
                children.add(block());
                return Collections.singletonList(node(NodeKind.FUNCTION, name, start, children));
            }
            if (checkIdentifier(className) && checkAt(1, "{")) {
                advance(); // record 의 compact 생성자
                children.add(block());
                return Collections.singletonList(node(NodeKind.FUNCTION, className, start, children));
            }
            Node type = type();
            Token nameToken = expectIdentifier();
            if (check("(")) {
                children.addAll(formalParameters());
                dims(new StringBuilder());
                throwsClause();
                children.add(type);
                if (match("default")) {
                    children.add(elementValue());
                }
                if (check("{")) {
                    children.add(block());
                } else {
                    expect(";");
                }
                return Collections.singletonList(node(NodeKind.FUNCTION, nameToken.text(), start, children));
            }
            List<Node> fields = variableDeclarators(start, type, nameToken);
            expect(";");
            return fields;
        }

        private Node typeDeclaration(Token start, List<Node> decorators) {
            enter();
            try {
                String keyword;
                if (match("@")) {
                    expect("interface");
                    keyword = "@interface";
                } else {
                    keyword = advance().text(); // class / interface / enum / record
                }
                String name = expectIdentifier().text();
                List<Node> children = new ArrayList<>(decorators);
                if (check("<")) {
                    typeArguments(new StringBuilder());
                }
                if (keyword.equals("record")) {
                    children.addAll(formalParameters());
                }
                while (check("extends") || check("implements") || checkIdentifier("permits")) {
                    advance();
                    do {
                        children.add(type());
                    } while (match(","));
                }
                children.add(classBody(name, keyword.equals("enum")));
                return node(NodeKind.CLASS, name, start, children);
            } finally {
                exit();
            }
        }

        private Node classBody(String className, boolean isEnum) {
            Token start = expect("{");
            List<Node> members = new ArrayList<>();
            if (isEnum) {
                enumConstants(members);
            }
            while (!check("}") && !atEnd()) {
                int before = pos;
                Token memberStart = peek();
                try {
                    if (!match(";")) {
                        members.addAll(memberDeclaration(memberStart, annotations(), className));
                    }
                } catch (ParseFailure failure) {
                    recordError(failure);
                    recover();
                    members.add(errorNode(failure, memberStart));
                }
                if (pos == before && !atEnd() && !check("}")) {
                    advance();
                }
            }
            expect("}");
            return node(NodeKind.BLOCK, "", start, members);
        }

        private void enumConstants(List<Node> members) {
            while (!check(";") && !check("}") && !atEnd()) {
                Token start = peek();
                List<Node> children = new ArrayList<>(annotations());
                String name = expectIdentifier().text();
                if (check("(")) {
                    children.addAll(arguments());
                }
                if (check("{")) {
                    children.add(node(NodeKind.CLASS, name, start, classBody(name, false)));
                }
                members.add(node(NodeKind.VARIABLE, name, start, children));
                if (!match(",")) {
                    break;
                }
            }
            match(";");
        }

        private List<Node> formalParameters() {
            expect("(");
            List<Node> params = new ArrayList<>();
            while (!check(")") && !atEnd()) {
                Token start = peek();
                modifiers(Collections.<Node>emptyList());
                Node type = type();
                StringBuilder typeText = new StringBuilder(type.text());
                if (match("...")) {
                    typeText.append("...");
                }
                String name;
                if (check("this")) {
                    name = advance().text(); // 수신자 매개변수
                } else {
                    name = expectIdentifier().text();
                    if (match(".")) {
                        expect("this");
                        name = name + ".this";
                    }
                }
                dims(typeText);
                Node finalType = new Node(NodeKind.TYPE, typeText.toString(), null, null,
                        type.line(), type.column(), type.endLine(), type.endColumn());
                params.add(node(NodeKind.PARAMETER, name, start, finalType));
                if (!match(",")) {
                    break;
                }
            }
            expect(")");
            return params;
        }

        private void throwsClause() {
            if (match("throws")) {
                do {
                    type();
                } while (match(","));
            }
        }

        private List<Node> variableDeclarators(Token start, Node type, Token firstName) {
            List<Node> result = new ArrayList<>();
            Token name = firstName;
            Token declStart = start;
            while (true) {
                StringBuilder typeText = new StringBuilder(type.text());
                dims(typeText);
                Node varType = new Node(NodeKind.TYPE, typeText.toString(), null, null,
                        type.line(), type.column(), type.endLine(), type.endColumn());
                Node init = match("=") ? variableInitializer() : null;
                result.add(node(NodeKind.VARIABLE, name.text(), declStart, varType, init));
                if (!match(",")) {
                    return result;
                }
                declStart = peek();
                name = expectIdentifier();
            }
        }

        private Node variableInitializer() {
            return check("{") ? arrayInitializer() : expression();
        }

        private Node arrayInitializer() {
            Token start = expect("{");
            List<Node> items = new ArrayList<>();
            while (!check("}") && !atEnd()) {
                items.add(variableInitializer());
                if (!match(",")) {
                    break;
                }
            }
            expect("}");
            return node(NodeKind.COLLECTION, "{", start, items);
        }

        // ------------------------------------------------------------ 타입

        private String qualifiedName() {
            StringBuilder sb = new StringBuilder(expectIdentifier().text());
            while (check(".") && peek(1).kind() == TokenKind.IDENTIFIER) {
                advance();
                sb.append('.').append(advance().text());
            }
            return sb.toString();
        }

        /** 타입 하나. 텍스트는 공백 없이 이어 붙인다 (예: {@code Map<String,List<Integer>>[]}). */
        private Node type() {
            Token start = peek();
            StringBuilder text = new StringBuilder();
            annotations();
            if (!atEnd() && peek().kind() == TokenKind.KEYWORD && PRIMITIVES.contains(peek().text())) {
                text.append(advance().text());
            } else if (match("?")) {
                text.append('?');
                if (check("extends") || check("super")) {
                    text.append(' ').append(advance().text()).append(' ').append(type().text());
                }
                return node(NodeKind.TYPE, text.toString(), start);
            } else {
                text.append(expectIdentifier().text());
                if (check("<")) {
                    typeArguments(text);
                }
                while (check(".") && (peek(1).kind() == TokenKind.IDENTIFIER || checkAt(1, "@"))) {
                    advance();
                    annotations();
                    text.append('.').append(expectIdentifier().text());
                    if (check("<")) {
                        typeArguments(text);
                    }
                }
            }
            dims(text);
            return node(NodeKind.TYPE, text.toString(), start);
        }

        private void dims(StringBuilder text) {
            while (true) {
                int[] state = save();
                annotations();
                if (check("[") && checkAt(1, "]")) {
                    advance();
                    advance();
                    text.append("[]");
                } else {
                    restore(state);
                    return;
                }
            }
        }

        private void typeArguments(StringBuilder text) {
            expect("<");
            text.append('<');
            boolean first = true;
            while (!closeAngle()) {
                if (!first) {
                    expect(",");
                    text.append(',');
                }
                first = false;
                text.append(type().text());
                if (check("&")) { // <T extends A & B>
                    while (match("&")) {
                        text.append('&').append(type().text());
                    }
                }
                if (checkKind(TokenKind.IDENTIFIER) || check("extends") || check("super")) {
                    // 타입 매개변수 선언: <T extends Comparable<T>>
                    if (match("extends") || match("super")) {
                        text.append(" extends ").append(type().text());
                        while (match("&")) {
                            text.append('&').append(type().text());
                        }
                    }
                }
                if (atEnd()) {
                    throw fail("Expected '>'");
                }
            }
            text.append('>');
        }

        /** '>'를 하나 읽는다. '>>', '>>>', '>=', '>>=' 는 한 글자씩 나눠 읽는다. */
        private boolean closeAngle() {
            if (atEnd()) {
                return false;
            }
            Token t = peek();
            if (t.kind() != TokenKind.OPERATOR || !t.text().startsWith(">")) {
                return false;
            }
            String rest = t.text().substring(angleSplit);
            if (!rest.startsWith(">")) {
                return false;
            }
            if (rest.length() == 1) {
                angleSplit = 0;
                advance();
            } else {
                angleSplit++;
            }
            return true;
        }

        // ------------------------------------------------------------ 문장

        private Node block() {
            enter();
            try {
                Token start = expect("{");
                List<Node> statements = new ArrayList<>();
                while (!check("}") && !atEnd()) {
                    blockStatementInto(statements);
                }
                expect("}");
                return node(NodeKind.BLOCK, "", start, statements);
            } finally {
                exit();
            }
        }

        private void blockStatementInto(List<Node> out) {
            int before = pos;
            Token start = peek();
            try {
                blockStatement(out);
            } catch (ParseFailure failure) {
                recordError(failure);
                recover();
                out.add(errorNode(failure, start));
            }
            if (pos == before && !atEnd() && !check("}")) {
                advance();
            }
        }

        private void blockStatement(List<Node> out) {
            Token start = peek();
            int[] state = save();
            List<Node> annotations = annotations();
            boolean sawFinal = false;
            while (check("final") || check("abstract") || check("static") || check("strictfp")) {
                sawFinal = true;
                advance();
            }
            if (atTypeDeclaration()) {
                out.add(typeDeclaration(start, annotations));
                return;
            }
            if (sawFinal || !annotations.isEmpty() || isLocalVariableDeclaration()) {
                out.addAll(localVariableDeclaration(start));
                expect(";");
                return;
            }
            restore(state);
            Node statement = statement();
            if (statement != null) {
                out.add(statement);
            }
        }

        /** 지금 위치가 '타입 이름 (= ; , [ :)' 형태인지 미리 읽어 본다. */
        private boolean isLocalVariableDeclaration() {
            if (checkIdentifier("var") && peek(1).kind() == TokenKind.IDENTIFIER) {
                return true;
            }
            if (!checkKind(TokenKind.IDENTIFIER) && !(peek().kind() == TokenKind.KEYWORD
                    && PRIMITIVES.contains(peek().text()) && !check("void"))) {
                return false;
            }
            int[] state = save();
            try {
                type();
                return angleSplit == 0 && checkKind(TokenKind.IDENTIFIER)
                        && (checkAt(1, "=") || checkAt(1, ";") || checkAt(1, ",") || checkAt(1, "[")
                        || checkAt(1, ":"));
            } catch (ParseFailure failure) {
                return false;
            } finally {
                restore(state);
            }
        }

        private List<Node> localVariableDeclaration(Token start) {
            Node type;
            if (checkIdentifier("var") && peek(1).kind() == TokenKind.IDENTIFIER) {
                type = node(NodeKind.TYPE, "var", advance());
            } else {
                type = type();
            }
            return variableDeclarators(start, type, expectIdentifier());
        }

        private Node statement() {
            enter();
            try {
                return statementInner();
            } finally {
                exit();
            }
        }

        private Node statementInner() {
            Token start = peek();
            if (check("{")) {
                return block();
            }
            if (match(";")) {
                return node(NodeKind.PASS, "", start);
            }
            if (match("if")) {
                Node condition = parenthesized();
                Node then = statement();
                Node otherwise = match("else") ? statement() : null;
                return node(NodeKind.IF, "", start, condition, then, otherwise);
            }
            if (match("while")) {
                Node condition = parenthesized();
                return node(NodeKind.LOOP, "while", start, condition, statement());
            }
            if (match("do")) {
                Node body = statement();
                expect("while");
                Node condition = parenthesized();
                expect(";");
                return node(NodeKind.LOOP, "do", start, condition, body);
            }
            if (match("for")) {
                return forStatement(start);
            }
            if (match("try")) {
                return tryStatement(start);
            }
            if (check("switch")) {
                Node node = switchConstruct();
                match(";");
                return node;
            }
            if (match("return")) {
                Node value = check(";") ? null : expression();
                expect(";");
                return node(NodeKind.RETURN, "", start, value);
            }
            if (match("throw")) {
                Node value = expression();
                expect(";");
                return node(NodeKind.THROW, "", start, value);
            }
            if (match("break")) {
                if (checkKind(TokenKind.IDENTIFIER)) {
                    advance();
                }
                expect(";");
                return node(NodeKind.BREAK, "", start);
            }
            if (match("continue")) {
                if (checkKind(TokenKind.IDENTIFIER)) {
                    advance();
                }
                expect(";");
                return node(NodeKind.CONTINUE, "", start);
            }
            if (match("synchronized")) {
                Node lock = parenthesized();
                return node(NodeKind.OTHER, "synchronized", start, lock, block());
            }
            if (match("assert")) {
                Node test = expression();
                Node message = match(":") ? expression() : null;
                expect(";");
                return node(NodeKind.ASSERT, "", start, test, message);
            }
            // Java 14+: 한정자 없는 yield(...) 호출은 금지되어 있으므로 'yield (' 도 yield 문이다
            if (checkIdentifier("yield") && !checkAt(1, "=") && !checkAt(1, ".") && !checkAt(1, "[")
                    && !checkAt(1, "++") && !checkAt(1, "--") && !checkAt(1, ";") && !checkAt(1, "+=")
                    && !checkAt(1, "-=")) {
                advance();
                Node value = expression();
                expect(";");
                return node(NodeKind.RETURN, "yield", start, value);
            }
            if (checkKind(TokenKind.IDENTIFIER) && checkAt(1, ":") && !checkAt(1, "::")) {
                String label = advance().text();
                advance();
                return node(NodeKind.OTHER, "label " + label, start, statement());
            }
            Node expression = expression();
            expect(";");
            return node(NodeKind.EXPRESSION_STATEMENT, "", start, expression);
        }

        private Node parenthesized() {
            expect("(");
            Node value = expression();
            expect(")");
            return value;
        }

        private Node forStatement(Token start) {
            expect("(");
            int[] state = save();
            Token initStart = peek();
            modifiers(Collections.<Node>emptyList());
            if (isLocalVariableDeclaration() || checkIdentifier("var")) {
                Node type = checkIdentifier("var") ? node(NodeKind.TYPE, "var", advance()) : type();
                Token name = expectIdentifier();
                if (match(":")) { // 향상된 for
                    Node variable = node(NodeKind.VARIABLE, name.text(), initStart, type);
                    Node iterable = expression();
                    expect(")");
                    return node(NodeKind.LOOP, "for", start, variable, iterable, statement());
                }
                List<Node> children = new ArrayList<>(variableDeclarators(initStart, type, name));
                return classicFor(start, children);
            }
            restore(state);
            List<Node> children = new ArrayList<>();
            while (!check(";")) {
                children.add(expression());
                if (!match(",")) {
                    break;
                }
            }
            return classicFor(start, children);
        }

        private Node classicFor(Token start, List<Node> children) {
            expect(";");
            if (!check(";")) {
                children.add(expression());
            }
            expect(";");
            while (!check(")")) {
                children.add(expression());
                if (!match(",")) {
                    break;
                }
            }
            expect(")");
            children.add(statement());
            return node(NodeKind.LOOP, "for", start, children);
        }

        private Node tryStatement(Token start) {
            List<Node> children = new ArrayList<>();
            if (match("(")) {
                while (!check(")") && !atEnd()) {
                    Token resourceStart = peek();
                    modifiers(Collections.<Node>emptyList());
                    if (isLocalVariableDeclaration() || checkIdentifier("var")) {
                        children.addAll(localVariableDeclaration(resourceStart));
                    } else {
                        children.add(expression());
                    }
                    if (!match(";")) {
                        break;
                    }
                }
                expect(")");
            }
            children.add(block());
            while (check("catch")) {
                Token catchStart = advance();
                expect("(");
                modifiers(Collections.<Node>emptyList());
                Token typeStart = peek();
                StringBuilder types = new StringBuilder(type().text());
                while (match("|")) {
                    types.append('|').append(type().text());
                }
                Node typeNode = node(NodeKind.TYPE, types.toString(), typeStart);
                String name = expectIdentifier().text();
                expect(")");
                children.add(node(NodeKind.CATCH, name, catchStart, typeNode, block()));
            }
            if (check("finally")) {
                Token finallyStart = advance();
                children.add(node(NodeKind.FINALLY, "", finallyStart, block()));
            }
            return node(NodeKind.TRY, "", start, children);
        }

        /** switch 문과 switch 식 공통. */
        private Node switchConstruct() {
            Token start = expect("switch");
            List<Node> children = new ArrayList<>();
            children.add(parenthesized());
            expect("{");
            while (!check("}") && !atEnd()) {
                Token caseStart = peek();
                String label;
                if (match("default")) {
                    label = "default";
                } else {
                    expect("case");
                    label = "case";
                    skipCaseLabel();
                }
                List<Node> body = new ArrayList<>();
                if (match("->")) {
                    if (check("{")) {
                        body.add(block());
                    } else if (check("throw")) {
                        body.add(statement());
                    } else {
                        Token exprStart = peek();
                        Node value = expression();
                        expect(";");
                        body.add(node(NodeKind.EXPRESSION_STATEMENT, "", exprStart, value));
                    }
                } else {
                    expect(":");
                    while (!check("case") && !check("default") && !check("}") && !atEnd()) {
                        if (check("default") && (checkAt(1, ":") || checkAt(1, "->"))) {
                            break;
                        }
                        blockStatementInto(body);
                    }
                }
                children.add(node(NodeKind.CASE, label, caseStart, body));
            }
            expect("}");
            return node(NodeKind.SWITCH, "switch", start, children);
        }

        /** case 레이블/패턴을 ':' 또는 '->' 까지 건너뛴다 (괄호 안과 삼항 연산자의 ':'는 제외). */
        private void skipCaseLabel() {
            int depth = 0;
            int ternary = 0;
            while (!atEnd()) {
                if (depth == 0 && check("->")) {
                    return;
                }
                if (depth == 0 && check(":")) {
                    if (ternary == 0) {
                        return;
                    }
                    ternary--;
                }
                Token t = advance();
                if (t.isOperator("(") || t.isOperator("[") || t.isOperator("{")) {
                    depth++;
                } else if (t.isOperator(")") || t.isOperator("]") || t.isOperator("}")) {
                    depth--;
                } else if (t.isOperator("?") && depth == 0) {
                    ternary++;
                }
            }
        }

        // ------------------------------------------------------------ 식

        private Node expression() {
            enter();
            try {
                if (lambdaAhead()) {
                    return lambda();
                }
                Token start = peek();
                Node left = ternary();
                if (!atEnd() && peek().kind() == TokenKind.OPERATOR && ASSIGNMENT_OPERATORS.contains(peek().text())) {
                    String op = advance().text();
                    return node(NodeKind.ASSIGN, op, start, left, expression());
                }
                return left;
            } finally {
                exit();
            }
        }

        private boolean lambdaAhead() {
            if (checkKind(TokenKind.IDENTIFIER) && checkAt(1, "->")) {
                return true;
            }
            if (!check("(")) {
                return false;
            }
            int depth = 0;
            for (int i = pos; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.isOperator("(")) {
                    depth++;
                } else if (t.isOperator(")")) {
                    if (--depth == 0) {
                        return i + 1 < tokens.size() && tokens.get(i + 1).isOperator("->");
                    }
                } else if (t.isOperator(";") || t.isOperator("{") || t.isOperator("}")) {
                    return false;
                }
            }
            return false;
        }

        private Node lambda() {
            Token start = peek();
            List<Node> children = new ArrayList<>();
            if (checkKind(TokenKind.IDENTIFIER)) {
                Token name = advance();
                children.add(node(NodeKind.PARAMETER, name.text(), name));
            } else {
                expect("(");
                while (!check(")") && !atEnd()) {
                    Token paramStart = peek();
                    modifiers(Collections.<Node>emptyList());
                    if (checkKind(TokenKind.IDENTIFIER) && (checkAt(1, ",") || checkAt(1, ")"))) {
                        children.add(node(NodeKind.PARAMETER, advance().text(), paramStart));
                    } else {
                        Node type = checkIdentifier("var") ? node(NodeKind.TYPE, "var", advance()) : type();
                        match("...");
                        String name = expectIdentifier().text();
                        children.add(node(NodeKind.PARAMETER, name, paramStart, type));
                    }
                    if (!match(",")) {
                        break;
                    }
                }
                expect(")");
            }
            expect("->");
            children.add(check("{") ? block() : expression());
            return node(NodeKind.LAMBDA, "", start, children);
        }

        private Node ternary() {
            Token start = peek();
            Node condition = binary(1);
            if (match("?")) {
                Node then = expression(); // JLS: 가운데 피연산자는 대입식도 될 수 있다
                expect(":");
                Node otherwise = lambdaAhead() ? lambda() : ternary();
                return node(NodeKind.TERNARY, "", start, condition, then, otherwise);
            }
            return condition;
        }

        private Node binary(int minPrecedence) {
            Token start = peek();
            Node left = unary();
            while (!atEnd()) {
                Token t = peek();
                if (t.kind() != TokenKind.OPERATOR && !t.isKeyword("instanceof")) {
                    break;
                }
                Integer precedence = BINARY_PRECEDENCE.get(t.text());
                if (precedence == null || precedence < minPrecedence) {
                    break;
                }
                advance();
                if (t.text().equals("instanceof")) {
                    match("final");
                    Node type = type();
                    if (check("(")) {
                        skipBalanced("(", ")"); // record 패턴
                    }
                    if (checkKind(TokenKind.IDENTIFIER)) {
                        advance(); // 패턴 변수
                    }
                    left = node(NodeKind.BINARY, "instanceof", start, left, type);
                } else {
                    left = node(NodeKind.BINARY, t.text(), start, left, binary(precedence + 1));
                }
            }
            return left;
        }

        private Node unary() {
            enter();
            try {
                Token start = peek();
                if (check("+") || check("-") || check("++") || check("--") || check("!") || check("~")) {
                    String op = advance().text();
                    return node(NodeKind.UNARY, op, start, unary());
                }
                if (check("(")) {
                    Node cast = tryCast();
                    if (cast != null) {
                        return cast;
                    }
                }
                Node value = primary();
                while (check("++") || check("--")) {
                    value = node(NodeKind.UNARY, "post" + advance().text(), start, value);
                }
                return value;
            } finally {
                exit();
            }
        }

        private Node tryCast() {
            int[] state = save();
            Token start = advance(); // (
            try {
                boolean primitive = peek().kind() == TokenKind.KEYWORD && PRIMITIVES.contains(peek().text());
                StringBuilder text = new StringBuilder(type().text());
                while (match("&")) {
                    text.append('&').append(type().text());
                }
                if (angleSplit != 0 || !check(")")) {
                    restore(state);
                    return null;
                }
                advance();
                if (!canStartCastOperand(primitive)) {
                    restore(state);
                    return null;
                }
                Node operand = lambdaAhead() ? lambda() : unary();
                return node(NodeKind.CAST, text.toString(), start, operand);
            } catch (ParseFailure failure) {
                restore(state);
                return null;
            }
        }

        private boolean canStartCastOperand(boolean primitiveCast) {
            if (atEnd()) {
                return false;
            }
            Token t = peek();
            if (t.kind() == TokenKind.IDENTIFIER || t.kind().isLiteral()) {
                return true;
            }
            if (t.kind() == TokenKind.KEYWORD) {
                return t.text().equals("this") || t.text().equals("super") || t.text().equals("new")
                        || t.text().equals("switch") || PRIMITIVES.contains(t.text());
            }
            String s = t.text();
            if (s.equals("(") || s.equals("!") || s.equals("~")) {
                return true;
            }
            return primitiveCast && (s.equals("+") || s.equals("-") || s.equals("++") || s.equals("--"));
        }

        private Node primary() {
            Token start = peek();
            Node value = primaryBase();
            while (true) {
                if (check(".")) {
                    advance();
                    if (check("<")) {
                        typeArguments(new StringBuilder());
                    }
                    if (check("new")) {
                        value = creator(start); // outer.new Inner()
                        continue;
                    }
                    Token member = peek();
                    if (member.kind() == TokenKind.IDENTIFIER || member.isKeyword("class")
                            || member.isKeyword("this") || member.isKeyword("super")) {
                        advance();
                        value = node(NodeKind.ATTRIBUTE, member.text(), start, value);
                    } else {
                        throw fail("Expected member name");
                    }
                } else if (check("(") && (value.kind() == NodeKind.NAME || value.kind() == NodeKind.ATTRIBUTE)) {
                    List<Node> children = new ArrayList<>();
                    children.add(value);
                    children.addAll(arguments());
                    value = node(NodeKind.CALL, "", start, children);
                } else if (check("[") && checkAt(1, "]")) {
                    StringBuilder text = new StringBuilder(value.qualifiedName() == null ? "?" : value.qualifiedName());
                    dims(text); // String[]::new, int[].class
                    value = node(NodeKind.TYPE, text.toString(), start);
                } else if (match("[")) {
                    Node index = expression();
                    expect("]");
                    value = node(NodeKind.INDEX, "", start, value, index);
                } else if (match("::")) {
                    if (check("<")) {
                        typeArguments(new StringBuilder());
                    }
                    String member = check("new") ? advance().text() : expectIdentifier().text();
                    value = node(NodeKind.ATTRIBUTE, "::" + member, start, value);
                } else {
                    return value;
                }
            }
        }

        private Node primaryBase() {
            Token t = peek();
            if (atEnd()) {
                throw fail("Expected expression");
            }
            if (t.kind().isLiteral()) {
                return leaf(NodeKind.LITERAL, advance());
            }
            if (t.kind() == TokenKind.IDENTIFIER) {
                // 제네릭 타입의 메서드 참조: List<String>::size
                if (checkAt(1, "<")) {
                    int[] state = save();
                    try {
                        Node type = type();
                        if (check("::")) {
                            return type;
                        }
                    } catch (ParseFailure ignored) {
                        // 비교식이다
                    }
                    restore(state);
                }
                return leaf(NodeKind.NAME, advance());
            }
            if (check("this") || check("super")) {
                return leaf(NodeKind.NAME, advance());
            }
            if (check("new")) {
                return creator(t);
            }
            if (check("switch")) {
                return switchConstruct();
            }
            if (match("(")) {
                Node value = expression();
                expect(")");
                return value;
            }
            if (t.kind() == TokenKind.KEYWORD && PRIMITIVES.contains(t.text())) {
                Node type = type(); // int.class, int[]::new 만 허용
                if (!(check(".") && checkAt(1, "class")) && !check("::")) {
                    throw fail("Expected '.class' or '::' after primitive type");
                }
                return type;
            }
            if (check("@")) {
                annotations();
                return primaryBase();
            }
            throw fail("Expected expression");
        }

        private Node creator(Token start) {
            expect("new");
            if (check("<")) {
                typeArguments(new StringBuilder());
            }
            annotations();
            StringBuilder typeName = new StringBuilder();
            if (!atEnd() && peek().kind() == TokenKind.KEYWORD && PRIMITIVES.contains(peek().text())) {
                typeName.append(advance().text());
            } else {
                typeName.append(expectIdentifier().text());
                if (check("<")) {
                    typeArguments(typeName);
                }
                while (match(".")) {
                    annotations();
                    typeName.append('.').append(expectIdentifier().text());
                    if (check("<")) {
                        typeArguments(typeName);
                    }
                }
            }
            List<Node> children = new ArrayList<>();
            if (check("[")) {
                while (check("[")) {
                    advance();
                    if (!check("]")) {
                        children.add(expression());
                    }
                    expect("]");
                    typeName.append("[]");
                }
                if (check("{")) {
                    children.add(arrayInitializer());
                }
                return node(NodeKind.NEW, typeName.toString(), start, children);
            }
            children.addAll(arguments());
            if (check("{")) {
                Token bodyStart = peek();
                children.add(node(NodeKind.CLASS, "<anonymous " + typeName + ">", bodyStart,
                        classBody(typeName.toString(), false)));
            }
            return node(NodeKind.NEW, typeName.toString(), start, children);
        }

        private List<Node> arguments() {
            expect("(");
            List<Node> args = new ArrayList<>();
            while (!check(")") && !atEnd()) {
                args.add(expression());
                if (!match(",")) {
                    break;
                }
            }
            expect(")");
            return args;
        }

        private void skipBalanced(String open, String close) {
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
    }
}
