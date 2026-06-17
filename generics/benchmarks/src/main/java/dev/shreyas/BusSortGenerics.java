import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

public class BusSortGenerics {

    static final int BUCKETS = 64;
    static final int BUS_SIZE = 4096;
    static final int THRESHOLD = 1024;

    // ============================================================
    // LONG HELPERS
    // ============================================================
    static int bucketOfLong(long key, long min, long range) {
        return (int) Math.min((key - min) / range, BUCKETS - 1);
    }

    // ============================================================
    // LONG DISTRIBUTE
    // ============================================================
    public static void distributeRangeLong(long[] input, int left, int right,
            long[] output, int[] globalCount, int[] bucketStarts,
            int[] globalNext, long[] busValues, int[] busBucket,
            long[] grouped, int[] localCount, int[] localStart, int[] localNext) {

        // use unsigned >>> 32 to get key
        long min = input[left] >>> 32, max = input[left] >>> 32;
        for (int i = left + 1; i <= right; i++) {
            long key = input[i] >>> 32;
            if (key < min)
                min = key;
            if (key > max)
                max = key;
        }

        long rangeL = (max - min + 1 + BUCKETS - 1) / BUCKETS;
        if (rangeL <= 0)
            rangeL = 1;

        Arrays.fill(globalCount, 0);
        for (int i = left; i <= right; i++)
            globalCount[bucketOfLong(input[i] >>> 32, min, rangeL)]++;

        globalNext[0] = left;
        bucketStarts[0] = left;
        for (int i = 1; i < BUCKETS; i++) {
            globalNext[i] = globalNext[i - 1] + globalCount[i - 1];
            bucketStarts[i] = bucketStarts[i - 1] + globalCount[i - 1];
        }

        for (int chunkStart = left; chunkStart <= right; chunkStart += BUS_SIZE) {
            int len = Math.min(BUS_SIZE, right - chunkStart + 1);
            Arrays.fill(localCount, 0, BUCKETS, 0);

            for (int i = 0; i < len; i++) {
                long value = input[chunkStart + i];
                int bucket = bucketOfLong(value >>> 32, min, rangeL);
                busValues[i] = value;
                busBucket[i] = bucket;
                localCount[bucket]++;
            }

            localStart[0] = 0;
            for (int b = 1; b < BUCKETS; b++)
                localStart[b] = localStart[b - 1] + localCount[b - 1];
            System.arraycopy(localStart, 0, localNext, 0, BUCKETS);

            for (int i = 0; i < len; i++)
                grouped[localNext[busBucket[i]]++] = busValues[i];

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
    // LONG SORT CORE
    // ============================================================
    public static void sortLongCore(long[] arr, int left, int right, long[] buf,
            int[] globalCount, int[] bucketStarts, int[] globalNext,
            long[] busValues, int[] busBucket, long[] grouped,
            int[] localCount, int[] localStart, int[] localNext) {

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
                insertionSortLong(arr, l, r);
                continue;
            }

            long min = arr[l] >>> 32, max = arr[l] >>> 32;
            for (int i = l + 1; i <= r; i++) {
                long key = arr[i] >>> 32;
                if (key < min)
                    min = key;
                if (key > max)
                    max = key;
            }
            if (min == max) {
                insertionSortLong(arr, l, r);
                continue;
            }

            distributeRangeLong(arr, l, r, buf, globalCount, bucketStarts,
                    globalNext, busValues, busBucket, grouped,
                    localCount, localStart, localNext);
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
    // GENERIC PUBLIC ENTRY
    // ============================================================
    @SuppressWarnings("unchecked")
    public static <T> void sort(T[] arr, ToIntFunction<T> keyExtractor) {
        int n = arr.length;
        if (n <= 1)
            return;

        // early exit checks
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

        if (reverse) {
            T[] temp = (T[]) new Object[n];

            int left = 0;
            int right = n - 1;
            while (0 <= right) {
                int flag = right;
                while (flag > 0 && keyExtractor.applyAsInt(arr[flag - 1]) == keyExtractor.applyAsInt(arr[right])) {
                    flag--;
                }
                for (int i = flag; i <= right; i++) {
                    temp[left++] = arr[i];
                }
                right = flag - 1;
            }
            System.arraycopy(temp, 0, arr, 0, n);
            return;
        }

        // XOR with 0x80000000 to convert signed to unsigned order
        long[] packed = new long[n];
        for (int i = 0; i < n; i++) {
            long normKey = (long) (keyExtractor.applyAsInt(arr[i]) ^ 0x80000000);
            packed[i] = (normKey << 32) | (i & 0xFFFFFFFFL);
        }

        long[] buf = new long[n];
        int[] globalCount = new int[BUCKETS], bucketStarts = new int[BUCKETS];
        int[] globalNext = new int[BUCKETS];
        long[] busValues = new long[BUS_SIZE], grouped = new long[BUS_SIZE];
        int[] busBucket = new int[BUS_SIZE];
        int[] localCount = new int[BUCKETS], localStart = new int[BUCKETS];
        int[] localNext = new int[BUCKETS];

        sortLongCore(packed, 0, n - 1, buf, globalCount, bucketStarts,
                globalNext, busValues, busBucket, grouped,
                localCount, localStart, localNext);

        T[] temp = (T[]) new Object[n];
        for (int i = 0; i < n; i++)
            temp[i] = arr[(int) (packed[i] & 0xFFFFFFFFL)];
        System.arraycopy(temp, 0, arr, 0, n);
    }

    // ============================================================
    // INSERTION SORT
    // ============================================================
    public static void insertionSortLong(long[] arr, int left, int right) {
        for (int i = left + 1; i <= right; i++) {
            long key = arr[i];
            int j = i - 1;
            while (j >= left && Long.compareUnsigned(arr[j], key) > 0) {
                arr[j + 1] = arr[j];
                j--;
            }
            arr[j + 1] = key;
        }
    }

    // ============================================================
    // MAIN
    // ============================================================
    public static void main(String[] args) {
        int n = 40000000;
        System.out.println("n = " + n);
        System.out.println("Generic BusSort vs Timsort (Integer[])");
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
                        int a = ThreadLocalRandom.current().nextInt(n), b = ThreadLocalRandom.current().nextInt(n),
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
            for (int i = 1; i < n; i++) {
                if (arr1[i] < arr1[i - 1]) {
                    correct = false;
                    break;
                }
            }

            System.out.printf("%-15s BusSort: %4dms  Timsort: %4dms  ratio: %.1fx  correct: %s%n",
                    names[type],
                    (e1 - s1) / 1000000, (e2 - s2) / 1000000,
                    (double) (e2 - s2) / (e1 - s1),
                    correct ? "✅" : "❌");
        }

    }
}
