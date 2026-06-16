import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

public class BusSortGenerics {

    static final int BUCKETS = 64;
    static final int BUS_SIZE = 4096;
    static final int THRESHOLD = 1024;

    // ============================================================
    // INT HELPERS
    // ============================================================
    static int bucketOf(int value, int min, int range) {
        return (int) Math.min(((long) value - min) / range, BUCKETS - 1);
    }

    static void reverseInt(int[] arr) {
        int l = 0, r = arr.length - 1;
        while (l < r) {
            int t = arr[l];
            arr[l++] = arr[r];
            arr[r--] = t;
        }
    }

    static <T> void reverseArray(T[] arr) {
        int l = 0, r = arr.length - 1;
        while (l < r) {
            T t = arr[l];
            arr[l++] = arr[r];
            arr[r--] = t;
        }
    }

    // ============================================================
    // INT DISTRIBUTE
    // ============================================================
    public static void distributeRange(int[] input, int left, int right,
            int[] output, int[] globalCount, int[] bucketStarts,
            int[] globalNext, int[] busValues, int[] busBucket,
            int[] grouped, int[] localCount, int[] localStart, int[] localNext) {

        int min = input[left], max = input[left];
        for (int i = left + 1; i <= right; i++) {
            if (input[i] < min)
                min = input[i];
            if (input[i] > max)
                max = input[i];
        }

        long rangeL = ((long) max - min + 1 + BUCKETS - 1) / BUCKETS;
        if (rangeL <= 0)
            rangeL = 1;
        int range = (int) Math.min(rangeL, Integer.MAX_VALUE);

        Arrays.fill(globalCount, 0);
        for (int i = left; i <= right; i++)
            globalCount[bucketOf(input[i], min, range)]++;

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
                int value = input[chunkStart + i];
                int bucket = bucketOf(value, min, range);
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
    // INT SORT CORE
    // ============================================================
    public static void sortIntCore(int[] arr, int left, int right, int[] buf,
            int[] globalCount, int[] bucketStarts, int[] globalNext,
            int[] busValues, int[] busBucket, int[] grouped,
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
                insertionSort(arr, l, r);
                continue;
            }

            int min = arr[l], max = arr[l];
            for (int i = l + 1; i <= r; i++) {
                if (arr[i] < min)
                    min = arr[i];
                if (arr[i] > max)
                    max = arr[i];
            }
            if (min == max)
                continue;

            distributeRange(arr, l, r, buf, globalCount, bucketStarts,
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
    // INT PUBLIC ENTRY
    // ============================================================
    public static void sort(int[] arr) {
        int n = arr.length;
        if (n <= 1)
            return;

        boolean sorted = true, reverse = true;
        for (int i = 1; i < n; i++) {
            if (arr[i] < arr[i - 1])
                sorted = false;
            if (arr[i] > arr[i - 1])
                reverse = false;
            if (!sorted && !reverse)
                break;
        }
        if (sorted)
            return;
        if (reverse) {
            reverseInt(arr);
            return;
        }

        int[] buf = new int[n];
        int[] globalCount = new int[BUCKETS], bucketStarts = new int[BUCKETS];
        int[] globalNext = new int[BUCKETS], busValues = new int[BUS_SIZE];
        int[] busBucket = new int[BUS_SIZE], grouped = new int[BUS_SIZE];
        int[] localCount = new int[BUCKETS], localStart = new int[BUCKETS];
        int[] localNext = new int[BUCKETS];

        sortIntCore(arr, 0, n - 1, buf, globalCount, bucketStarts,
                globalNext, busValues, busBucket, grouped,
                localCount, localStart, localNext);
    }

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
            reverseArray(arr);
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
    // INSERTION SORTS
    // ============================================================
    public static void insertionSort(int[] arr, int left, int right) {
        for (int i = left + 1; i <= right; i++) {
            int key = arr[i];
            int j = i - 1;
            while (j >= left && arr[j] > key) {
                arr[j + 1] = arr[j];
                j--;
            }
            arr[j + 1] = key;
        }
    }

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
            for (int i = 1; i < n; i++)
                if (arr1[i] < arr1[i - 1]) {
                    correct = false;
                    break;
                }

            System.out.printf("%-15s BusSort: %4dms  Timsort: %4dms  ratio: %.1fx  correct: %s%n",
                    names[type],
                    (e1 - s1) / 1000000, (e2 - s2) / 1000000,
                    (double) (e2 - s2) / (e1 - s1),
                    correct ? "✅" : "❌");
        }

        // stability test
        System.out.println("\n--- Stability Test ---");
        int m = 100000;
        int[][] stab = new int[m][2]; // [0]=key, [1]=original index
        for (int i = 0; i < m; i++) {
            stab[i][0] = ThreadLocalRandom.current().nextInt(0, 10);
            stab[i][1] = i;
        }
        sort(stab, x -> x[0]);
        boolean stable = true;
        int pk = -1, pi = -1;
        for (int i = 0; i < m; i++) {
            int key = stab[i][0], idx = stab[i][1];
            if (key == pk && idx < pi) {
                stable = false;
                break;
            }
            pk = key;
            pi = idx;
        }
        System.out.println("stable: " + stable);

        // negative number test
        System.out.println("\n--- Negative Number Test ---");
        Integer[] neg = new Integer[100];
        for (int i = 0; i < 100; i++)
            neg[i] = ThreadLocalRandom.current().nextInt();
        Integer[] negExp = neg.clone();
        Arrays.sort(negExp);
        sort(neg, x -> x);
        System.out.println("negative correct: " + Arrays.equals(neg, negExp));
    }
}
