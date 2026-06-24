// Default program shown in the editor. Runs against the real interpreter — a
// small supported-subset program (primitives, String, arithmetic, println).
export const SAMPLE_CODE = `public class Main {
    public static void main(String[] args) {
        int x = 5;
        int y = x + 3;
        String s = "hello";
        System.out.println(s);
        System.out.println(y);
    }
}
`;
