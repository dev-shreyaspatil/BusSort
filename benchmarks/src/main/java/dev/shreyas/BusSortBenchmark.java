package dev.shreyas;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/*
 * HOW TO RUN:
 *   mvn clean package
 *   java -jar target/benchmarks.jar
 *
 * Quick run (fewer iterations, less accurate):
 *   java -jar target/benchmarks.jar -wi 3 -i 5 -f 1
 *
 * Full rigorous run (recommended before posting results):
 *   java -jar target/benchmarks.jar -wi 5 -i 10 -f 3
 *
 * Filter to one input type:
 *   java -jar target/benchmarks.jar ".*RANDOM.*"
 */

@BenchmarkMode(Mode.AverageTime)        // measure average time per op
@OutputTimeUnit(TimeUnit.MILLISECONDS)  // report in ms
@State(Scope.Thread)                    // each thread gets its own state
@Warmup(iterations = 5, time = 2)      // 5 warmup rounds x 2s each — JIT gets to compile
@Measurement(iterations = 10, time = 2) // 10 measured rounds x 2s each
@Fork(3)                                // 3 separate JVM forks — eliminates JVM startup bias
public class BusSortBenchmark {

    // ----------------------------------------------------------------
    // Benchmark size — change this to test different scales
    // ----------------------------------------------------------------
    @Param({"1000000", "10000000", "100000000"})
    public int n;

    // ----------------------------------------------------------------
    // Input type — JMH will run all combinations with n
    // ----------------------------------------------------------------
    @Param({"RANDOM", "SORTED", "REVERSE", "NEARLY_SORTED",
            "DUPLICATES", "FEW_DUPLICATES", "ALL_SAME", "CLUSTERED"})
    public String inputType;

    // The "template" array — generated once in setup, copied fresh before each benchmark
    private int[] template;

    // Working copy — reset before each invocation so we're always sorting fresh data
    private int[] workingCopy;

    @Setup(Level.Trial)
    public void setupTrial() {
        // Generate the template array once per (n, inputType) combination
        template = generateInput(n, inputType);
        workingCopy = new int[n];
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        // Fresh copy before EVERY benchmark call — critical!
        // Without this, second call sorts an already-sorted array (unfair advantage/disadvantage)
        System.arraycopy(template, 0, workingCopy, 0, n);
    }

    // ----------------------------------------------------------------
    // Benchmarks
    // ----------------------------------------------------------------

    @Benchmark
    public int[] busSort() {
        BusSort.sort(workingCopy);
        return workingCopy; // return to prevent dead-code elimination by JIT
    }

    @Benchmark
    public int[] dualPivotQuickSort() {
        Arrays.sort(workingCopy);
        return workingCopy;
    }

    // ----------------------------------------------------------------
    // Input generators
    // ----------------------------------------------------------------

    private static int[] generateInput(int n, String type) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int[] arr = new int[n];

        switch (type) {
            case "RANDOM":
                for (int i = 0; i < n; i++)
                    arr[i] = rng.nextInt(-n, n);
                break;

            case "SORTED":
                for (int i = 0; i < n; i++)
                    arr[i] = i;
                break;

            case "REVERSE":
                for (int i = 0; i < n; i++)
                    arr[i] = n - i;
                break;

            case "NEARLY_SORTED":
                for (int i = 0; i < n; i++)
                    arr[i] = i;
                // swap 1% of elements randomly
                for (int i = 0; i < n / 100; i++) {
                    int a = rng.nextInt(n), b = rng.nextInt(n);
                    int t = arr[a]; arr[a] = arr[b]; arr[b] = t;
                }
                break;

            case "DUPLICATES":
                // high duplicate density — only 100 distinct values
                for (int i = 0; i < n; i++)
                    arr[i] = rng.nextInt(-50, 50);
                break;

            case "FEW_DUPLICATES":
                // medium duplicate density — 1000 distinct values
                for (int i = 0; i < n; i++)
                    arr[i] = rng.nextInt(-500, 500);
                break;

            case "ALL_SAME":
                Arrays.fill(arr, 42);
                break;

            case "CLUSTERED":
                for (int i = 0; i < n / 3; i++)
                    arr[i] = rng.nextInt(0, 100);
                for (int i = n / 3; i < 2 * n / 3; i++)
                    arr[i] = 100_000 + rng.nextInt(0, 100);
                for (int i = 2 * n / 3; i < n; i++)
                    arr[i] = 500_000 + rng.nextInt(0, 100);
                break;

            default:
                throw new IllegalArgumentException("Unknown input type: " + type);
        }
        return arr;
    }

    // ----------------------------------------------------------------
    // Optional: run directly from IDE
    // ----------------------------------------------------------------
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BusSortBenchmark.class.getSimpleName())
                .param("n", "100000000")           // only 100M for direct runs
                .param("inputType", "RANDOM", "DUPLICATES", "REVERSE")
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
