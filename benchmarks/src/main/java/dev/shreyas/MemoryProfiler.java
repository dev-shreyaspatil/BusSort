import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

// Standalone peak-heap-memory profiler for BusSort (int) vs Arrays.sort.
// Not a JMH benchmark: measures actual PEAK heap usage attributable to one
// sort call, using MemoryPoolMXBean.resetPeakUsage()/getPeakUsage() on the
// heap pools. Complements JMH's -prof gc, which only reports cumulative
// allocation (bytes/op), not the peak live footprint at any instant.
//
// HOW TO RUN (compile alongside BusSort.java, e.g. in the same folder):
//   javac BusSort.java MemoryProfiler.java
//   java -Xmx8g MemoryProfiler
//
// Adjust SIZES below if your machine has less RAM; int arrays at n=100M
// are only ~400MB so this should fit comfortably even on modest machines.
public class MemoryProfiler {

    static final int[] SIZES = {1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};
    static final int BUCKETS = 256;
    static final int BUS_SIZE = 4096;
    static final int THRESHOLD = 64;

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
                // some pools don't support peak tracking; skip
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

    // Measures peak heap usage during task.run(). Forces a GC and a brief
    // pause first so the baseline is clean before resetting peak trackers.
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
            int[] template = new int[n];
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < n; i++) template[i] = rng.nextInt();

            // BusSort
            int[] a1 = template.clone();
            long peakBusSort = measurePeak(() ->
                    BusSort.sort(a1, 0, a1.length - 1, BUCKETS, BUS_SIZE, THRESHOLD));
            System.out.printf("%d,BusSort,%.2f%n", n, peakBusSort / 1048576.0);

            // Arrays.sort (Dual-Pivot Quicksort)
            int[] a2 = template.clone();
            long peakDPQ = measurePeak(() -> Arrays.sort(a2));
            System.out.printf("%d,Arrays.sort,%.2f%n", n, peakDPQ / 1048576.0);

            // sanity check both produced correct output
            boolean ok1 = isSorted(a1), ok2 = isSorted(a2);
            if (!ok1 || !ok2) {
                System.err.printf("WARNING: correctness check failed at n=%d (BusSort=%s, DPQ=%s)%n",
                        n, ok1, ok2);
            }
        }
    }

    static boolean isSorted(int[] arr) {
        for (int i = 1; i < arr.length; i++)
            if (arr[i] < arr[i - 1]) return false;
        return true;
    }
}
