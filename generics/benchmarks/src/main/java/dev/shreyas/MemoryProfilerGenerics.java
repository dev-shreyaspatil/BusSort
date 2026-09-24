import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

// Standalone peak-heap-memory profiler for BusSortGenerics vs TimSort.
// See MemoryProfiler.java (int variant) for the full rationale.
//
// HOW TO RUN (compile alongside BusSortGenerics.java):
//   javac BusSortGenerics.java MemoryProfilerGenerics.java
//   java -Xmx4g MemoryProfilerGenerics
//
// SIZES capped lower than the int variant: Record[] objects carry far more
// per-element memory overhead (object header + fields) than raw int[], and
// this profiler was tuned for an 8GB-RAM machine — raise the cap if you
// have more headroom, but watch for OutOfMemoryError.
public class MemoryProfilerGenerics {

    static final int[] SIZES = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};
    static final int BUCKETS = 80;
    static final int BUS_SIZE = 4096;
    static final int THRESHOLD = 64;

    public static class Record {
        public final int key;
        public final int originalIndex;

        public Record(int key, int originalIndex) {
            this.key = key;
            this.originalIndex = originalIndex;
        }
    }

    static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP)
                .collect(Collectors.toList());
    }

    static void resetPeaks(List<MemoryPoolMXBean> pools) {
        for (MemoryPoolMXBean p : pools) {
            try {
                p.resetPeakUsage();
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    static long totalPeakUsed(List<MemoryPoolMXBean> pools) {
        long sum = 0;
        for (MemoryPoolMXBean p : pools) {
            sum += p.getPeakUsage().getUsed();
        }
        return sum;
    }

    static long measurePeak(Runnable task) {
        List<MemoryPoolMXBean> pools = heapPools();
        System.gc();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        resetPeaks(pools);
        task.run();
        return totalPeakUsed(pools);
    }

    public static void main(String[] args) {
        System.out.println("n,algorithm,peakHeapUsedMB");

        for (int n : SIZES) {
            Random rng = new Random(42);
            Record[] template = new Record[n];
            for (int i = 0; i < n; i++) template[i] = new Record(rng.nextInt(), i);

            // BusSortGenerics
            Record[] a1 = template.clone();
            long peakBusSort = measurePeak(() ->
                    BusSortGenerics.sort(a1, 0, a1.length - 1, r -> r.key, BUCKETS, BUS_SIZE, THRESHOLD));
            System.out.printf("%d,BusSortGenerics,%.2f%n", n, peakBusSort / 1048576.0);

            // TimSort
            Record[] a2 = template.clone();
            long peakTimSort = measurePeak(() ->
                    Arrays.sort(a2, Comparator.comparingInt(r -> r.key)));
            System.out.printf("%d,TimSort,%.2f%n", n, peakTimSort / 1048576.0);

            boolean ok1 = isSorted(a1), ok2 = isSorted(a2);
            if (!ok1 || !ok2) {
                System.err.printf("WARNING: correctness check failed at n=%d (BusSort=%s, TimSort=%s)%n",
                        n, ok1, ok2);
            }
        }
    }

    static boolean isSorted(Record[] arr) {
        for (int i = 1; i < arr.length; i++)
            if (arr[i].key < arr[i - 1].key) return false;
        return true;
    }
}
