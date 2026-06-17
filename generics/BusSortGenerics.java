package dev.shreyas;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

// BusSortGenerics — cache-aware, stable, histogram-based sorting algorithm for generic objects.
// Uses ToIntFunction<T> key extractor. Beats Java's TimSort on distribution-heavy inputs.
public class BusSortGenerics {

    static final int BUCKETS = 128;
    static final int BUS_SIZE = 4096;
    static final int THRESHOLD = 1024;

    // ============================================================
    // BUCKET INDEX
    // ============================================================
    static int bucketOf(long key, long min, long range) {
        return (int) Math.min((key - min) / range, BUCKETS - 1);
    }

    // ============================================================
    // DISTRIBUTE RANGE — T[] direct, zero packed allocation
    // ============================================================
    @SuppressWarnings("unchecked")
    public static <T> void distributeRange(T[] input, int left, int right,
            T[] output, int[] globalCount, int[] bucketStarts,
            int[] globalNext, T[] busObjects, int[] busBucket,
            T[] grouped, int[] localCount, int[] localStart, int[] localNext,
            ToIntFunction<T> keyExtractor) {

        // MIN MAX
        long min = keyExtractor.applyAsInt(input[left]);
        long max = keyExtractor.applyAsInt(input[left]);
        for (int i = left + 1; i <= right; i++) {
            long key = keyExtractor.applyAsInt(input[i]);
            if (key < min)
                min = key;
            if (key > max)
                max = key;
        }

        long rangeL = (max - min + 1 + BUCKETS - 1) / BUCKETS;
        if (rangeL <= 0)
            rangeL = 1;

        // GLOBAL HISTOGRAM
        Arrays.fill(globalCount, 0);
        for (int i = left; i <= right; i++)
            globalCount[bucketOf(keyExtractor.applyAsInt(input[i]), min, rangeL)]++;

        // GLOBAL PREFIX
        globalNext[0] = left;
        bucketStarts[0] = left;
        for (int i = 1; i < BUCKETS; i++) {
            globalNext[i] = globalNext[i - 1] + globalCount[i - 1];
            bucketStarts[i] = bucketStarts[i - 1] + globalCount[i - 1];
        }

        // PROCESS BUS CHUNKS
        for (int chunkStart = left; chunkStart <= right; chunkStart += BUS_SIZE) {
            int len = Math.min(BUS_SIZE, right - chunkStart + 1);
            Arrays.fill(localCount, 0, BUCKETS, 0);

            // PASS 1 — extract key, assign bucket, cache object reference
            for (int i = 0; i < len; i++) {
                T obj = input[chunkStart + i];
                int bucket = bucketOf(keyExtractor.applyAsInt(obj), min, rangeL);
                busObjects[i] = obj;
                busBucket[i] = bucket;
                localCount[bucket]++;
            }

            // PASS 2 — local prefix sums
            localStart[0] = 0;
            for (int b = 1; b < BUCKETS; b++)
                localStart[b] = localStart[b - 1] + localCount[b - 1];
            System.arraycopy(localStart, 0, localNext, 0, BUCKETS);

            // PASS 3 — scatter objects into local grouped buffer
            for (int i = 0; i < len; i++)
                grouped[localNext[busBucket[i]]++] = busObjects[i];

            // PASS 4 — copy from grouped to global output
            for (int b = 0; b < BUCKETS; b++) {
                int count = localCount[b];
                if (count == 0)
                    continue;
                System.arraycopy(grouped, localStart[b], output, globalNext[b], count);
                globalNext[b] += count;
            }
        }
    }

    // ============================================================
    // SORT CORE — T[]
    // ============================================================
    @SuppressWarnings("unchecked")
    public static <T> void sortCore(T[] arr, int left, int right, T[] buf,
            int[] globalCount, int[] bucketStarts, int[] globalNext,
            T[] busObjects, int[] busBucket, T[] grouped,
            int[] localCount, int[] localStart, int[] localNext,
            ToIntFunction<T> keyExtractor) {

        int[][] stack = new int[BUCKETS * 4][2];
        int top = 0;
        stack[top][0] = left;
        stack[top][1] = right;
        top++;

        while (top > 0) {
            top--;
            int l = stack[top][0];
            int r = stack[top][1];
            int n = r - l + 1;

            if (n <= THRESHOLD) {
                insertionSort(arr, l, r, keyExtractor);
                continue;
            }

            long min = keyExtractor.applyAsInt(arr[l]);
            long max = keyExtractor.applyAsInt(arr[l]);
            for (int i = l + 1; i <= r; i++) {
                long key = keyExtractor.applyAsInt(arr[i]);
                if (key < min)
                    min = key;
                if (key > max)
                    max = key;
            }

            if (min == max)
                continue; // all equal — stable, skip

            distributeRange(arr, l, r, buf, globalCount, bucketStarts,
                    globalNext, busObjects, busBucket, grouped,
                    localCount, localStart, localNext, keyExtractor);
            System.arraycopy(buf, l, arr, l, n);

            for (int b = BUCKETS - 1; b >= 0; b--) {
                int bLeft = bucketStarts[b];
                int bRight = (b == BUCKETS - 1) ? r : bucketStarts[b + 1] - 1;
                if (bRight > bLeft) {
                    stack[top][0] = bLeft;
                    stack[top][1] = bRight;
                    top++;
                }
            }
        }
    }

    // ============================================================
    // PUBLIC ENTRY POINT
    // ============================================================
    @SuppressWarnings("unchecked")
    public static <T> void sort(T[] arr, ToIntFunction<T> keyExtractor) {
        int n = arr.length;
        if (n <= 1)
            return;

        // detect sorted / reverse
        boolean sorted = true, reverse = true;
        for (int i = 1; i < n; i++) {
            int a = keyExtractor.applyAsInt(arr[i - 1]);
            int b = keyExtractor.applyAsInt(arr[i]);
            if (a > b)
                sorted = false;
            if (a < b)
                reverse = false;
            if (!sorted && !reverse)
                break;
        }
        if (sorted)
            return;

        T[] buf = (T[]) new Object[n]; // only allocation proportional to n

        if (reverse) {
            int left = 0, right = n - 1;
            while (0 <= right) {
                int flag = right;
                while (flag > 0 &&
                        keyExtractor.applyAsInt(arr[flag - 1]) == keyExtractor.applyAsInt(arr[right])) {
                    flag--;
                }
                for (int i = flag; i <= right; i++)
                    buf[left++] = arr[i];
                right = flag - 1;
            }
            System.arraycopy(buf, 0, arr, 0, n);
            return;
        }

        // BUS_SIZE auxiliary arrays only
        int[] globalCount = new int[BUCKETS], bucketStarts = new int[BUCKETS];
        int[] globalNext = new int[BUCKETS];
        T[] busObjects = (T[]) new Object[BUS_SIZE];
        int[] busBucket = new int[BUS_SIZE];
        T[] grouped = (T[]) new Object[BUS_SIZE];
        int[] localCount = new int[BUCKETS], localStart = new int[BUCKETS];
        int[] localNext = new int[BUCKETS];

        sortCore(arr, 0, n - 1, buf, globalCount, bucketStarts,
                globalNext, busObjects, busBucket, grouped,
                localCount, localStart, localNext, keyExtractor);
    }

    // ============================================================
    // INSERTION SORT — stable, for base case
    // ============================================================
    public static <T> void insertionSort(T[] arr, int left, int right,
            ToIntFunction<T> keyExtractor) {
        for (int i = left + 1; i <= right; i++) {
            T obj = arr[i];
            int key = keyExtractor.applyAsInt(obj);
            int j = i - 1;
            while (j >= left && keyExtractor.applyAsInt(arr[j]) > key) {
                arr[j + 1] = arr[j];
                j--;
            }
            arr[j + 1] = obj;
        }
    }

    // ============================================================
    // MAIN
    // ============================================================
    public static void main(String[] args) {
        int n = 40000000;
        System.out.println("n = " + n);
        System.out.println("Generic BusSort vs TimSort (Integer[])");
        System.out.println("--------------------------------------------");

        String[] names = { "RANDOM", "SORTED", "REVERSE", "NEARLY SORTED",
                "DUPLICATES", "FEW DUPS", "ALL SAME", "CLUSTERED" };

        for (int type = 0; type < 8; type++) {
            Integer[] arr1 = new Integer[n];
            switch (type) {
                case 0:
                    for (int i = 0; i < n; i++)
                        arr1[i] = ThreadLocalRandom.current().nextInt();
                    break;
                case 1:
                    for (int i = 0; i < n; i++)
                        arr1[i] = i;
                    break;
                case 2:
                    for (int i = 0; i < n; i++)
                        arr1[i] = n - i;
                    break;
                case 3:
                    for (int i = 0; i < n; i++)
                        arr1[i] = i;
                    for (int i = 0; i < n / 100; i++) {
                        int a = ThreadLocalRandom.current().nextInt(n),
                                b = ThreadLocalRandom.current().nextInt(n),
                                t = arr1[a];
                        arr1[a] = arr1[b];
                        arr1[b] = t;
                    }
                    break;
                case 4:
                    for (int i = 0; i < n; i++)
                        arr1[i] = ThreadLocalRandom.current().nextInt(-50, 50);
                    break;
                case 5:
                    for (int i = 0; i < n; i++)
                        arr1[i] = ThreadLocalRandom.current().nextInt(-500, 500);
                    break;
                case 6:
                    for (int i = 0; i < n; i++)
                        arr1[i] = 42;
                    break;
                case 7:
                    for (int i = 0; i < n / 3; i++)
                        arr1[i] = ThreadLocalRandom.current().nextInt(0, 100);
                    for (int i = n / 3; i < 2 * n / 3; i++)
                        arr1[i] = 100000 + ThreadLocalRandom.current().nextInt(0, 100);
                    for (int i = 2 * n / 3; i < n; i++)
                        arr1[i] = 500000 + ThreadLocalRandom.current().nextInt(0, 100);
                    break;
            }

            Integer[] arr2 = arr1.clone();

            long s1 = System.nanoTime();
            sort(arr1, x -> x);
            long e1 = System.nanoTime();

            long s2 = System.nanoTime();
            Arrays.sort(arr2);
            long e2 = System.nanoTime();

            boolean correct = true;
            for (int i = 1; i < n; i++)
                if (arr1[i] < arr1[i - 1]) {
                    correct = false;
                    break;
                }

            System.out.printf("%-15s BusSort: %4dms  TimSort: %4dms  ratio: %.1fx  correct: %s%n",
                    names[type],
                    (e1 - s1) / 1000000, (e2 - s2) / 1000000,
                    (double) (e2 - s2) / (e1 - s1),
                    correct ? "✅" : "❌");

        }
    }
}
