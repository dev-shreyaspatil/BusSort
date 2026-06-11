import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

// WORK IN PROGRESS — stable reverse and generic support coming soon.
public class BusSort {

    static final int BUCKETS = 128;
    static final int BUS_SIZE = 4096;
    static final int THRESHOLD = 1024;

    static int bucketOf(int value, int min, int range) {
        return Math.min((value - min) / range, BUCKETS - 1);
    }

    // ============================================================
    // DISTRIBUTE RANGE — zero allocation
    // ============================================================
    public static void distributeRange(int[] input, int left, int right,
            int[] output, int[] globalCount, int[] bucketStarts,
            int[] globalNext, int[] busValues, int[] busBucket,
            int[] grouped, int[] localCount, int[] localStart, int[] localNext) {

        // MIN MAX
        int min = input[left];
        int max = input[left];
        for (int i = left + 1; i <= right; i++) {
            if (input[i] < min)
                min = input[i];
            if (input[i] > max)
                max = input[i];
        }

        int range = (int) Math.ceil((double) (max - min + 1) / BUCKETS);
        if (range <= 0)
            range = 1;

        // GLOBAL HISTOGRAM
        Arrays.fill(globalCount, 0);
        for (int i = left; i <= right; i++)
            globalCount[bucketOf(input[i], min, range)]++;

        // GLOBAL PREFIX
        globalNext[0] = left;
        for (int i = 1; i < BUCKETS; i++)
            globalNext[i] = globalNext[i - 1] + globalCount[i - 1];

        // BUCKET STARTS
        bucketStarts[0] = left;
        for (int i = 1; i < BUCKETS; i++)
            bucketStarts[i] = bucketStarts[i - 1] + globalCount[i - 1];

        // PROCESS BUS CHUNKS
        for (int chunkStart = left; chunkStart <= right; chunkStart += BUS_SIZE) {
            int len = Math.min(BUS_SIZE, right - chunkStart + 1);

            Arrays.fill(localCount, 0, BUCKETS, 0);

            // PASS 1
            for (int i = 0; i < len; i++) {
                int value = input[chunkStart + i];
                int bucket = bucketOf(value, min, range);
                busValues[i] = value;
                busBucket[i] = bucket;
                localCount[bucket]++;
            }

            // PASS 2
            localStart[0] = 0;
            for (int b = 1; b < BUCKETS; b++)
                localStart[b] = localStart[b - 1] + localCount[b - 1];
            System.arraycopy(localStart, 0, localNext, 0, BUCKETS);

            // PASS 3
            for (int i = 0; i < len; i++)
                grouped[localNext[busBucket[i]]++] = busValues[i];

            // PASS 4
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
    // SORT
    // ============================================================
    public static void sort(int[] arr, int left, int right, int[] buf, int[] globalCount, int[] bucketStarts,
            int[] globalNext, int[] busValues, int[] busBucket, int[] grouped, int[] localCount, int[] localStart,
            int[] localNext) {

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

            // find min max first
            int min = arr[l], max = arr[l];
            for (int i = l + 1; i <= r; i++) {
                if (arr[i] < min)
                    min = arr[i];
                if (arr[i] > max)
                    max = arr[i];
            }

            if (min == max)
                continue; // all equal → already sorted!

            distributeRange(arr, l, r, buf, globalCount, bucketStarts,
                    globalNext, busValues, busBucket,
                    grouped, localCount, localStart, localNext);
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
    public static void sort(int[] arr) {
        int n = arr.length;

        // detect sorted / reverse
        boolean sorted = true;
        boolean reverse = true;
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

        int[] buf = new int[n];

        // REVERSE IS NOT STABLE YET!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        if (reverse) {
            // copy into buf in reverse order
            for (int i = 0; i < n; i++)
                buf[i] = arr[n - 1 - i];
            System.arraycopy(buf, 0, arr, 0, n);
            return;
        }

        int[] globalCount = new int[BUCKETS];
        int[] bucketStarts = new int[BUCKETS];
        int[] globalNext = new int[BUCKETS];
        int[] busValues = new int[BUS_SIZE];
        int[] busBucket = new int[BUS_SIZE];
        int[] grouped = new int[BUS_SIZE];
        int[] localCount = new int[BUCKETS];
        int[] localStart = new int[BUCKETS];
        int[] localNext = new int[BUCKETS];

        sort(arr, 0, n - 1, buf, globalCount, bucketStarts,
                globalNext, busValues, busBucket,
                grouped, localCount, localStart, localNext);
    }

    // ============================================================
    // INSERTION SORT
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

    // ============================================================
    // MAIN
    // ============================================================
    public static void main(String[] args) {
        int n = 100000000;
        System.out.println("n = " + n);
        System.out.println("--------------------------------------------");

        String[] names = { "RANDOM", "SORTED", "REVERSE", "NEARLY SORTED",
                "DUPLICATES", "FEW DUPS", "ALL SAME", "CLUSTERED" };

        for (int type = 0; type < 8; type++) {
            int[] arr1 = new int[n];
            switch (type) {
                case 0:
                    for (int i = 0; i < n; i++)
                        arr1[i] = ThreadLocalRandom.current().nextInt(-n, n);
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

            int[] arr2 = new int[n];
            for (int i = 0; i < n; i++)
                arr2[i] = arr1[i];

            long s1 = System.nanoTime();
            sort(arr1);
            long e1 = System.nanoTime();

            long s2 = System.nanoTime();
            Arrays.sort(arr2); // Dual-Pivot Quicksort
            long e2 = System.nanoTime();

            boolean correct = true;
            for (int i = 1; i < n; i++)
                if (arr1[i] < arr1[i - 1]) {
                    correct = false;
                    break;
                }

            System.out.printf("%-15s BusSort: %4dms  Dual-Pivot Quicksort: %4dms  ratio: %.1fx  correct: %s%n",
                    names[type],
                    (e1 - s1) / 1000000, (e2 - s2) / 1000000,
                    (double) (e2 - s2) / (e1 - s1),
                    correct ? "TRUE" : "FALSE");
        }
    }
}
