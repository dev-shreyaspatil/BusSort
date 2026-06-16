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
 *   mvn clean package -DskipTests
 *   java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 5 -i 10 -f 3
 *
 * Quick test:
 *   java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 1 -i 2 -f 1 -p n=1000000
 */

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
public class BusSortGenericsBenchmark {

    @Param({"1000000", "10000000", "50000000"})
    public int n;

    @Param({"RANDOM", "SORTED", "REVERSE", "NEARLY_SORTED",
            "DUPLICATES", "FEW_DUPLICATES", "ALL_SAME", "CLUSTERED"})
    public String inputType;

    private Integer[] template;
    private Integer[] workingCopy;

    @Setup(Level.Trial)
    public void setupTrial() {
        template = generateInput(n, inputType);
        workingCopy = new Integer[n];
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        System.arraycopy(template, 0, workingCopy, 0, n);
    }

    // ----------------------------------------------------------------
    // Benchmarks
    // ----------------------------------------------------------------

    @Benchmark
    public Integer[] busSortGenerics() {
        BusSortGenerics.sort(workingCopy, x -> x);
        return workingCopy;
    }

    @Benchmark
    public Integer[] timSort() {
        Arrays.sort(workingCopy);
        return workingCopy;
    }

    // ----------------------------------------------------------------
    // Input generators
    // ----------------------------------------------------------------

    private static Integer[] generateInput(int n, String type) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Integer[] arr = new Integer[n];

        switch (type) {
            case "RANDOM":
                for (int i = 0; i < n; i++)
                    arr[i] = rng.nextInt();
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
                for (int i = 0; i < n / 100; i++) {
                    int a = rng.nextInt(n), b = rng.nextInt(n);
                    Integer t = arr[a]; arr[a] = arr[b]; arr[b] = t;
                }
                break;

            case "DUPLICATES":
                for (int i = 0; i < n; i++)
                    arr[i] = rng.nextInt(-50, 50);
                break;

            case "FEW_DUPLICATES":
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
    // Run from IDE
    // ----------------------------------------------------------------
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BusSortGenericsBenchmark.class.getSimpleName())
                .param("n", "50000000")
                .param("inputType", "RANDOM", "DUPLICATES", "FEW_DUPLICATES", "CLUSTERED")
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
