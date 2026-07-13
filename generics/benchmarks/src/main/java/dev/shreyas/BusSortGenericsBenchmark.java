package dev.shreyas;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/*
 * HOW TO RUN:
 *   mvn clean package -DskipTests
 *   java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 5 -i 10 -f 3
 *
 * With GC profiling:
 *   java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 5 -i 10 -f 3 -prof gc
 *
 * Quick test:
 *   java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 1 -i 2 -f 1 -p n=1000000
 *
 * Scalability sweep:
 *   java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 3 -i 5 -f 1
 *     -p n=1000000,5000000,10000000,20000000,40000000,70000000
 *     -p inputType=RANDOM
 */

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
public class BusSortGenericsBenchmark {

    // ----------------------------------------------------------------
    // Record — representative real-world object with numeric key
    // ----------------------------------------------------------------
    public static class Record {
        public final int key;
        public final int originalIndex; // used for stability verification

        public Record(int key, int originalIndex) {
            this.key = key;
            this.originalIndex = originalIndex;
        }
    }

    // ----------------------------------------------------------------
    // Parameters
    // ----------------------------------------------------------------

    @Param({"1000000", "5000000", "10000000", "20000000", "40000000", "70000000"})
    public int n;

    @Param({"RANDOM", "SORTED", "REVERSE", "NEARLY_SORTED",
            "DUPLICATES", "FEW_DUPLICATES", "ALL_SAME", "CLUSTERED"})
    public String inputType;

    private Record[] template;
    private Record[] workingCopy;

    // ----------------------------------------------------------------
    // Setup
    // ----------------------------------------------------------------

    @Setup(Level.Trial)
    public void setupTrial() {
        template = generateInput(n, inputType);
        workingCopy = new Record[n];
        verifyCorrectnessAndStability(template, inputType);
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        System.arraycopy(template, 0, workingCopy, 0, n);
    }

    // ----------------------------------------------------------------
    // Correctness and stability verification
    // runs once per trial — not part of timed benchmark
    // ----------------------------------------------------------------
    private static void verifyCorrectnessAndStability(Record[] template, String inputType) {
        int n = template.length;

        Record[] busCopy = template.clone();
        Record[] oracle  = template.clone();

        BusSortGenerics.sort(busCopy, r -> r.key);
        Arrays.sort(oracle, Comparator.comparingInt(r -> r.key));

        // correctness
        for (int i = 0; i < n; i++) {
            if (busCopy[i].key != oracle[i].key)
                throw new AssertionError(
                    "Correctness failed at index " + i +
                    " for inputType=" + inputType +
                    ": expected key=" + oracle[i].key +
                    " but got key=" + busCopy[i].key);
        }

        // stability — equal-key elements must preserve original relative order
        for (int i = 1; i < n; i++) {
            if (busCopy[i].key == busCopy[i - 1].key &&
                busCopy[i].originalIndex < busCopy[i - 1].originalIndex) {
                throw new AssertionError(
                    "Stability failed at index " + i +
                    " for inputType=" + inputType +
                    ": equal key=" + busCopy[i].key +
                    " but originalIndex " + busCopy[i].originalIndex +
                    " < " + busCopy[i - 1].originalIndex);
            }
        }
    }

    // ----------------------------------------------------------------
    // Benchmarks
    // ----------------------------------------------------------------

    @Benchmark
    public void busSortGenerics() {
        BusSortGenerics.sort(workingCopy, r -> r.key);
    }

    @Benchmark
    public void timSort() {
        Arrays.sort(workingCopy, Comparator.comparingInt(r -> r.key));
    }

    // ----------------------------------------------------------------
    // Input generators — fixed seed for reproducibility
    // ----------------------------------------------------------------
    private static Record[] generateInput(int n, String type) {
        Random rng = new Random(42);
        Record[] arr = new Record[n];

        switch (type) {
            case "RANDOM":
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(rng.nextInt(), i);
                break;

            case "SORTED":
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(i, i);
                break;

            case "REVERSE":
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(n - i, i);
                break;

            case "NEARLY_SORTED":
                // sorted with 1% random swaps (n/100 swap pairs)
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(i, i);
                for (int i = 0; i < n / 100; i++) {
                    int a = rng.nextInt(n), b = rng.nextInt(n);
                    Record t = arr[a]; arr[a] = arr[b]; arr[b] = t;
                }
                break;

            case "DUPLICATES":
                // high duplicate density — 100 distinct values across full array
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(rng.nextInt(-50, 50), i);
                break;

            case "FEW_DUPLICATES":
                // moderate duplicate density — 1000 distinct values
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(rng.nextInt(-500, 500), i);
                break;

            case "ALL_SAME":
                for (int i = 0; i < n; i++)
                    arr[i] = new Record(42, i);
                break;

            case "CLUSTERED":
                // three tight clusters with large gaps between them
                for (int i = 0; i < n / 3; i++)
                    arr[i] = new Record(rng.nextInt(0, 100), i);
                for (int i = n / 3; i < 2 * n / 3; i++)
                    arr[i] = new Record(100_000 + rng.nextInt(0, 100), i);
                for (int i = 2 * n / 3; i < n; i++)
                    arr[i] = new Record(500_000 + rng.nextInt(0, 100), i);
                break;

            default:
                throw new IllegalArgumentException("Unknown input type: " + type);
        }
        return arr;
    }

    // ----------------------------------------------------------------
    // Run from IDE
    // ----------------------------------------------------------------
    public static void main(String[] args) throws RunnerException {
        System.out.println("JVM: " + System.getProperty("java.vm.name"));
        System.out.println("Java version: " + System.getProperty("java.version"));

        Options opt = new OptionsBuilder()
                .include(BusSortGenericsBenchmark.class.getSimpleName())
                .param("n", "1000000", "5000000", "10000000", "20000000", "40000000", "70000000")
                .param("inputType", "RANDOM", "DUPLICATES", "FEW_DUPLICATES", "CLUSTERED",
                                    "SORTED", "REVERSE", "NEARLY_SORTED", "ALL_SAME")
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
