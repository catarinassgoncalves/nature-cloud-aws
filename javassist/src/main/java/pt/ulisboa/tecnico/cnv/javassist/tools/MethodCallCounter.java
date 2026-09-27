package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CtBehavior;

public class MethodCallCounter extends CodeDumper {

    /**
     * Number of executed methods.
     */
    private static long nmethods = 0;

    public MethodCallCounter(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static synchronized void incBehavior(String name) {
        nmethods++;
        System.out.println(String.format("[%s] Executed method: %s", MethodCallCounter.class.getSimpleName(), name));
    }

    public static void printStatistics() {
        System.out.println(String.format("[%s] Number of executed methods: %s",
                MethodCallCounter.class.getSimpleName(), nmethods));
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        behavior.insertBefore(
            String.format("%s.incBehavior(\"%s\");",
                MethodCallCounter.class.getName(),
                behavior.getLongName())
        );

        if (behavior.getName().equals("main")) {
            behavior.insertAfter(
                String.format("%s.printStatistics();",
                    MethodCallCounter.class.getName())
            );
        }
    }
}
