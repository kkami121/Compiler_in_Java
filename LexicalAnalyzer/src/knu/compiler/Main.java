package knu.compiler;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws FileNotFoundException {

        String inputText = "";
        List<String> memory = new ArrayList<>();

        File file = new File("tests/samples/program2.decaf"); //파일 불러오기
        Scanner scanner = new Scanner(file);

        inputText = scanner.nextLine();// 불러온 파일의 각 라인을 입력
        while (scanner.hasNextLine()) { //hasNextLine(자바 EOF : 더 이상 읽을 데이터가 없는 파일의 끝을 의미)
            inputText = inputText + "\n" + scanner.nextLine(); //줄 단위로 값을 읽어온다.
        }

        System.out.println(inputText);
        
        int lineNum = 1; //row값 초기화
        int colNum = 0; //column값 초기화

        char[] string = inputText.toCharArray(); //문자열을 char형태 배열로 변환

        for (int i = 0; i < string.length; i++) { //string의 길이 만큼 반복
            boolean unknown_char = true;
            colNum++;
            // System.out.println(string.length); length = 103

            if (string[i] >= '0' && string[i] <= '9') {//배열이 숫자인지 확인
                String temp = "";
                
                while (i < string.length && string[i] >= '0' && string[i] <= '9') {
                    temp += string[i]; //temp에 읽어온 수를 string타입으로 변경하며 저장
                    i++;
                    colNum++;
                }
                System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is T_IntConstant (token value: %5$s)", temp, lineNum, colNum - temp.length(), colNum - 1, temp));
                unknown_char = false;
            }
            

            if ((string[i] >= 'A' && string[i] <= 'Z') || string[i] == '_' || (string[i] >= 'a' && string[i] <= 'z')) { // 문자열 확인
                String temp = "";
                temp += string[i];

                i++;
                colNum++;
                while (i < string.length && ((string[i] >= 'A' && string[i] <= 'Z') || string[i] == '_'
                        || (string[i] >= 'a' && string[i] <= 'z')
                        || (string[i] >= '0' && string[i] <= '9'))) {
                    temp += string[i];
                    i++;
                    colNum++;
                }

                // 식별자, 키워드 구분
                if (temp.equals("if") || temp.equals("else") || temp.equals("for") || temp.equals("while") || temp.equals("void") ||
                        temp.equals("class") || temp.equals("extends") || temp.equals("implements") || temp.equals("interface") ||
                        temp.equals("Print") || temp.equals("break") || temp.equals("return") || temp.equals("this") ||
                        temp.equals("new") || temp.equals("ReadInteger") || temp.equals("ReadLine") || temp.equals("NewArray") ||
                        temp.equals("int") || temp.equals("double") || temp.equals("bool") || temp.equals("string") ||
                        temp.equals("id") || temp.equals("null")){
                    System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is T_%5$s", temp, lineNum, colNum - temp.length(), colNum - 1, temp.substring(0, 1).toUpperCase() + temp.substring(1)));
                } else if (temp.equals("true") || temp.equals("false")) {
                    System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is T_BoolConstant (token value: %5$s)", temp, lineNum, colNum - temp.length(), colNum - 1, temp));
                }
                else {  // 키워드(타입), 식별자
                    // How to deal with identifiers?
                }

                if (i >= string.length) {
                    break;
                } else {
                    i--;
                    colNum--;
                }
                unknown_char = false;
            }

            if (string[i] == '"') {
                String temp = "\"";
                i++;
                colNum++;
                boolean is_terminated = true;

                while (i < string.length && string[i] != '"') {
                    if (string[i] == '\n') {
                        System.out.println(String.format("\n*** Error line %d.", lineNum));
                        System.out.println(String.format("*** Unterminated string constant: %s\n", temp));
                        is_terminated = false;
                        break;
                    }
                    temp += string[i];
                    i++;
                    colNum++;
                }

                if (i < string.length &&  string[i] == '"') {
                    temp += string[i];
                }

                if (is_terminated) {
                    System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is T_StringConstant (token value: %5$s)", temp, lineNum, colNum - temp.length() + 1, colNum, temp));
                }

                if (i >= string.length) {
                    break;
                }

                unknown_char = false;
            }

            if (string[i] == '+' || string[i] == '-' || string[i] == '*' || string[i] == '%' || string[i] == ';' || string[i] == '('
                    || string[i] == ')' || string[i] == '{' || string[i] == '}' || string[i] == ',' || string[i] == '.' || string[i] == '[' || string[i] == ']') {
                String temp = "" + string[i];
                System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is '%5$s'", temp, lineNum, colNum, colNum + temp.length() - 1, temp));
                unknown_char = false;
            }

            if (string[i] == '=' || string[i] == '!' || string[i] == '<' || string[i] == '>') {
                String temp = "" + string[i];
                if (string[i+1] == '=') {
                    temp += string[i+1];
                    i++;
                    colNum++;
                }
                System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is '%5$s'", temp, lineNum, colNum - temp.length() + 1, colNum, temp));
                unknown_char = false;
            }

            if (string[i] == '&') {
                String temp = "" + string[i];
                if (string[i+1] == '&') {
                    temp += string[i + 1];
                    i++;
                    colNum++;
                    System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is '%5$s'", temp, lineNum, colNum - temp.length() + 1, colNum, temp));
                    unknown_char = false;
                }
            }

            if (string[i] == '|') {
                String temp = "" + string[i];
                if (string[i+1] == '|') {
                    temp += string[i+1];
                    i++;
                    colNum++;
                    System.out.println(String.format("%1$-14s line %2$d cols %3$d-%4$d is '%5$s'", temp, lineNum, colNum, colNum + temp.length() - 1, temp));
                    unknown_char = false;
                }
            }

            // How to deal with single-line and multi-line comments?

            if (string[i] == '\n') {
                colNum = 0;
                lineNum++;
            } else {
                if (unknown_char && string[i] != ' ') {
                    System.out.println(String.format("\n*** Error line %d.", lineNum));
                    System.out.println(String.format("*** Unrecognized char: '%c'\n", string[i]));
                }
            }
        }
    }
}
