package com.visualizer.interpreter.engine;

/**
 * A handful of supported-subset programs used as living documentation and as
 * fixtures for tests. Each is a complete, valid single-class program.
 */
public final class ExamplePrograms {

    private ExamplePrograms() {
    }

    /** Arithmetic across declarations: z ends at 15. */
    public static final String ARITHMETIC = """
            public class Main {
                public static void main(String[] args) {
                    int x = 10;
                    int y = 5;
                    int z = x + y;
                    System.out.println(z);
                }
            }
            """;

    /** Counts 0..4, printing each value on its own line. */
    public static final String COUNTING_LOOP = """
            public class Main {
                public static void main(String[] args) {
                    for (int i = 0; i < 5; i++) {
                        System.out.println(i);
                    }
                }
            }
            """;

    /** while loop accumulating a sum: total ends at 10. */
    public static final String WHILE_SUM = """
            public class Main {
                public static void main(String[] args) {
                    int total = 0;
                    int n = 1;
                    while (n <= 4) {
                        total += n;
                        n++;
                    }
                    System.out.println(total);
                }
            }
            """;

    /** Mixed numeric promotion and string concatenation. */
    public static final String PROMOTION_AND_STRINGS = """
            public class Main {
                public static void main(String[] args) {
                    double d = 5;
                    double avg = (1 + 2) / 2.0;
                    String label = "avg=" + avg;
                    System.out.println(label);
                }
            }
            """;

    /** if/else branching on a comparison. */
    public static final String BRANCHING = """
            public class Main {
                public static void main(String[] args) {
                    int score = 72;
                    if (score >= 60) {
                        System.out.println("pass");
                    } else {
                        System.out.println("fail");
                    }
                }
            }
            """;
}
