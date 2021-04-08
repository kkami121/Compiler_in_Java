import javax.print.event.PrintEvent;

public class sada {
    public static void main(String[] args){
        
        int i;
        int a = A;
        char [] string = {'i', 'n', '=', '1', '2', '.', '2' };
        String temp = "";
        for (i = 0; i < string.length; i++){
            boolean unknown_char = true;
            if (string[i] >= '0' && string[i] <= '9' || string[i] == '.') {//배열이 숫자인지 확인
            
                while (i < string.length && string[i] >= '0' && string[i] <= '9' || string[i] == '.') {
                    temp += string[i]; //temp에 읽어온 수를 string타입으로 변경하며 저장
                    i++;
                    
                }
                unknown_char = false;
                
            }
        }
        System.out.println(temp);
        System.out.println(a);
    }
}
