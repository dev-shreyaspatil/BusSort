# BusSort

## A cache-aware, stable, histogram-based sorting algorithm that beats Java's Dual-Pivot Quicksort and TimSort on distribution-heavy inputs.

---

## What is BusSort?

BusSort is a **cache-aware, stable, histogram-based sorting algorithm** designed around the constraints of modern CPU memory hierarchies — specifically L1 cache size — to minimize cache misses during the most expensive part of sorting: random writes.

Unlike comparison-based sorts (Quicksort, Mergesort, TimSort), BusSort exploits the **distribution of values** directly, using histograms to know exactly where every element belongs before moving it.

Two variants are available:
- **`BusSort.java`** — for `int[]`, beats Java's Dual-Pivot Quicksort
- **`BusSortGenerics.java`** — for `T[]` via `ToIntFunction<T>`, beats Java's TimSort

I'm sharing this as my own independent work, in the hope of getting feedback and scrutiny from people who know sorting and the JVM far better than I do. My aim was to build something stable, fast, and efficient — the numbers below are what I measured, honestly, including where it loses.

---

## Design Outcomes

| Property | Details |
|----------|---------|
| **Naturally Stable** | Equal elements always preserve original relative order — guaranteed by left-to-right chunk processing, not by extra bookkeeping |
| **Cache Efficient** | Scatter buffer bounded to L1 cache size — random writes stay in L1, eliminating cache thrashing |
| **Fast** | Beats DPQ by up to 2.75x on distribution-heavy `int[]` data, beats TimSort by up to 2.87x on `T[]` at large n |
| **Distribution-Aware** | Bucket range dynamically mapped to `[min, max]` — no wasted buckets regardless of value range |
| **Generic Support** | Works on any object type via `ToIntFunction<T>` key extractor |
| **Zero Comparison Overhead** | No comparator calls in the hot path — pure integer arithmetic for bucketing |
| **Predictable Performance** | No pivot selection, no adversarial worst-case inputs unlike Quicksort |
| **Full Int Range** | Handles negative numbers correctly — `Integer.MIN_VALUE` to `Integer.MAX_VALUE` |
| **Bounded Stack Depth** | Wide bucket split → shallow recursion, no JVM stack overflow risk at tested scales |
| **Parallel-Ready** | Each bucket is independent after the histogram pass — parallel processing can be integrated naturally |

> Note: Stack is currently statically sized (`BUCKETS * 8`). Dynamic stack is a planned improvement.

---

## How It Works

### Step 1 — Global Histogram

Scan the entire input to find `min`, `max`, and divide the value range into buckets. Count how many elements fall into each bucket. From this, compute the exact start position of every bucket in the output array.

```
Bucket 0: values [min, min+range)    → starts at position 0
Bucket 1: values [min+range, ...)    → starts at position count[0]
...
```

### Step 2 — Bus Chunking (the key idea)

Instead of scattering all elements globally at once (which causes cache thrashing), process the input in **L1 cache-sized chunks** (4096 elements = ~16KB for `int[]`).

For each chunk:

- **PASS 1** — Scan left-to-right, compute the bucket for each element, build a *local* histogram for this chunk only.
- **PASS 2** — Compute local prefix sums so we know each bucket's position *within the chunk*.
- **PASS 3** — Scatter elements into a local `grouped` buffer. Because this buffer is L1-sized, all random writes stay in cache. ✅
- **PASS 4** — Copy each bucket's portion from the local buffer to its correct position in the global output using `System.arraycopy`.

### Step 3 — Recurse

After one pass, each bucket contains elements in the correct region but internally unsorted. Push each bucket onto an explicit stack and repeat.

### Step 4 — Base Case

When a bucket is small enough (below `THRESHOLD`), sort it with **Insertion Sort**. At this size, insertion sort's sequential memory access and tiny overhead wins.

### Early Exits

- **Already sorted** → return immediately (O(n) detection pass)
- **Reverse sorted** → stable reverse into buffer, return (O(n))
- **All same value** → `min == max` check skips the bucket entirely

---

## Why Is It Fast?

| Problem with naive bucket/radix sort | BusSort's solution |
|--------------------------------------|-------------------|
| Scattering n elements globally → L1/L2 cache miss on every write | Process in L1-sized chunks → all random writes stay in cache |
| Wide value ranges → many empty buckets wasted | Buckets dynamically mapped to `[min, max]` range |
| Deep recursion on large arrays | Wide bucket split → shallow recursion depth |

The core insight: **bounded random access is cache-friendly random access.** By keeping the scatter buffer at L1 size, the algorithm converts what would be millions of cache misses into cache hits.

---

## Stability

BusSort is **naturally stable** — equal elements preserve their original relative order without any extra bookkeeping.

This holds because:
- Input chunks are processed **left-to-right**
- Within each chunk, elements are scattered **left-to-right**
- `globalNext[b]` advances per chunk, so earlier chunks always land before later chunks in the output

Equal-key objects in the generic variant are handled the same way — stability comes from traversal order, not from tagging or index tracking.

The reverse-sorted early exit uses a three-pointer stable reverse — equal-key groups are copied left-to-right, preserving original order.

> For `int[]`, equal integers are identical by value — stability is technically unobservable. However, the stable reverse path is intentionally preserved for consistency with the stability guarantee and to serve as a reference for future ports to other types.

---

## Parameters

Both variants were tuned via a sequential JMH sweep (bucket count → threshold → chunk size, each held fixed while the others varied), rather than an exhaustive grid search. Full methodology and raw results are reproducible via the benchmark commands below.

| Constant    | `int[]` (`BusSort.java`) | `T[]` (`BusSortGenerics.java`) | Meaning |
|-------------|---------------------------|-----------------------------------|---------|
| `BUCKETS`   | 256                       | 80                                 | Number of buckets per recursion level |
| `BUS_SIZE`  | 4096                      | 4096                               | Chunk size processed per pass |
| `THRESHOLD` | 64                        | 64                                 | Fall back to insertion sort below this size |

The two variants land on different `BUCKETS` values because `T[]` scatter buffers hold object references (pointer indirection), not raw primitives — the cache and allocation behavior differs enough that the optimal split width isn't the same. `BUS_SIZE` was swept too but showed only marginal (<5%), noise-level effect for either variant, so 4096 was kept for both as a simple, defensible default.

`BUS_SIZE` should be chosen so that `BUS_SIZE * 3 * sizeof(element)` fits comfortably in L1 cache. At 4096 for `int[]`: `4096 × 3 × 4 = 49,152 bytes ≈ 48KB`, at or near typical L1 data cache size — worth rechecking against your own CPU's L1 size (`lscpu` on Linux, or equivalent) if tuning further.

---

## Benchmarks

**Machine:** Intel Core i5-1035G1 @ 1.00GHz (4 cores / 8 threads), 8GB RAM | **Java:** 21
**Methodology:** JMH 1.37 — 5 warmup iterations, 10 measurement iterations, 3 JVM forks, `AverageTime` mode, `-prof gc` for allocation stats

### `int[]` vs Dual-Pivot Quicksort

Tested against Java's `Arrays.sort(int[])`, which uses Dual-Pivot Quicksort. `n = 100,000,000`.

| Input Type      | BusSort (ms) | ± Error | DPQ (ms) | ± Error | Ratio |
|------------------|-------------|---------|----------|---------|-------|
| Random           | 3981        | ±25     | 10606    | ±207    | **2.66x** ✅ |
| Duplicates       | 954         | ±8      | 2622     | ±29     | **2.75x** ✅ |
| Few Duplicates   | 2108        | ±150    | 4113     | ±71     | **1.95x** ✅ |
| Clustered        | 1897        | ±16     | 2707     | ±47     | **1.43x** ✅ |
| Nearly Sorted    | 3306        | ±106    | 3783     | ±61     | **1.14x** ✅ |
| Adversarial\*    | 3725        | ±23     | 3908     | ±93     | 1.05x — near tie |
| Sorted           | 42          | —       | 38       | —       | 0.92x — trivial cost either way |
| All Same         | 42          | —       | 38       | —       | 0.89x — trivial cost either way |
| Reverse          | 333         | ±2      | 111      | ±1      | 0.33x ❌ |

\*Adversarial: 99% of elements packed into a 1000-wide key range, 1% spread across the full `int` range — stresses histogram skew. See [Known Limitations](#known-limitations).

**GC pressure (RANDOM, n=100M):** BusSort allocates ~400MB/op (30 GC events, 60ms total GC time); DPQ is essentially in-place (~6.9KB/op, ≈0 GC events).

**5/8 non-trivial input types faster, one near-tie under adversarial skew.** Where BusSort loses (Reverse), it's a known, understood cost — see below.

### `T[]` vs TimSort (Generic)

Tested against Java's `Arrays.sort(T[])`, which uses TimSort. `n = 10,000,000`, sorting `Record` objects (an int key + an int field, representative of a real-world keyed object — not boxed `Integer`).

| Input Type      | BusSort (ms) | ± Error | TimSort (ms) | ± Error | Ratio |
|------------------|-------------|---------|--------------|---------|-------|
| Random           | 1864        | ±120    | 5346         | ±99     | **2.87x** ✅ |
| Few Duplicates   | 1015        | ±36     | 2135         | ±51     | **2.10x** ✅ |
| Clustered        | 882         | ±24     | 1478         | ±37     | **1.68x** ✅ |
| Duplicates       | 998         | ±13     | 1637         | ±93     | **1.64x** ✅ |
| Adversarial\*    | 1723        | ±116    | 2093         | ±34     | **1.21x** ✅ |
| All Same         | 19.8        | ±0.2    | 22.5         | ±0.3    | 1.14x — trivial cost either way |
| Sorted           | 22.5        | ±0.04   | 21.7         | ±0.1    | 0.96x — trivial cost either way |
| Reverse          | 185         | ±27     | 104          | ±1      | 0.56x ❌ |
| Nearly Sorted    | 673         | ±6      | 358          | ±21     | 0.53x ❌ |

\*Adversarial: same construction as above.

**GC pressure (RANDOM, n=10M):** BusSort allocates ~40MB/op (≈0 GC events); TimSort allocates ~53.5MB/op (16 GC events).

**6/9 non-trivial input types faster, including a clear win under adversarial skew.** Two honest losses: Reverse (same cause as the `int[]` variant) and Nearly Sorted — TimSort's run-detection is specifically built to exploit near-sortedness, and wins outright there; BusSort's histogram approach doesn't detect or exploit existing order at all.

---

## Peak Memory Footprint

Allocation rate (above) shows *total garbage generated*, not *peak live memory* — a different, arguably more important number for a memory-conscious comparison. Measured separately using `MemoryPoolMXBean` peak-usage tracking around a single sort call (see `MemoryProfiler.java` / `MemoryProfilerGenerics.java`).

| n | BusSort (`int[]`, MB) | DPQ (MB) | BusSortGenerics (MB) | TimSort (MB) |
|---|---|---|---|---|
| 1,000       | 0.73   | 1.12   | 0.75   | 1.14   |
| 10,000      | 1.18   | 1.22   | 1.42   | 1.45   |
| 100,000     | 1.87   | 2.25   | 4.16   | 4.16   |
| 1,000,000   | 13.11  | 13.11  | 36.44  | 41.02  |
| 10,000,000  | 121.11 | 81.11  | 350.08 | 370.21 |
| 100,000,000 | 1153.11| 769.11 | —      | —      |

**Two different stories here, worth stating plainly:**

- **`int[]` vs DPQ:** at scale, BusSort uses **~50% more peak memory** than Dual-Pivot Quicksort. This is expected — DPQ sorts in-place with O(log n) stack overhead, while BusSort allocates a full O(n) output buffer plus scratch arrays every level. This is a genuine speed/memory tradeoff: **~2-2.7x faster, ~1.5x more peak memory**, not a free lunch.
- **`T[]` vs TimSort:** BusSortGenerics uses **less** peak memory than TimSort at every measured scale (5-11% less at 1M-10M elements). TimSort isn't in-place either — its merge machinery needs its own O(n) auxiliary array — so the two aren't competing on the same baseline the way `int[]` sorts are. For the generic variant, BusSort wins on speed *and* memory.

---

## Complexity

|                  | BusSort |
|------------------|---------|
| Time (average)   | O(n · k) where k = recursion depth, shallow in practice |
| Time (best)      | O(n) — sorted/reverse/all-same early exit |
| Time (worst)     | O(n · k) with degraded k under adversarial skew — see below |
| Space (`int[]`)  | O(n) — measured ~1.5x DPQ's peak footprint at scale |
| Space (`T[]`)    | O(n) — measured lower than TimSort's peak footprint at scale |
| Stable           | ✅ Yes — naturally, no extra bookkeeping |
| Comparison-based | ❌ No |
| In-place         | ❌ No |

---

## Known Limitations

**Adversarial case — recursive bucket collapse:**
BusSort can degrade on inputs where values follow a heavily skewed distribution, causing most elements to fall into a single bucket at every recursion level. This was measured directly (99% of elements packed into a 1000-wide key range, 1% spread across the full range): under this skew, BusSort's advantage over the JDK's own sort **collapses to roughly a tie** (1.05x for `int[]`, though it still holds a real 1.21x edge for `T[]`). It doesn't become *worse* than the baseline at the scales tested, but the margin that makes BusSort worth using elsewhere mostly disappears here.

I deliberately did not add a fallback mechanism into the algorithm itself — the core logic stays simple and auditable at the cost of this one known weak spot. A natural fix would be a lightweight health-check after the histogram pass (O(BUCKETS) per level): if one bucket holds a disproportionate share of the elements, hand off to `Arrays.sort()` for that sub-range instead of recursing further. I'd welcome thoughts on whether this is worth building in, and what threshold would make sense.

Real-world datasets with a natural int key (IDs, timestamps, scores, sensor readings) rarely exhibit this pattern — it takes a fairly specific, heavily skewed distribution to trigger.

**Reverse-sorted input:**
Both variants are meaningfully slower than the baseline on strictly reverse-sorted input (0.33x for `int[]`, 0.56x for `T[]`). The reverse-detection path is built to preserve stability for equal-key runs, which means it processes distinct-valued reverse input element-by-element rather than via a simple swap-based reversal. The JDK's sorts don't carry this cost for a raw reversal. This is a known, understood tradeoff, not a bug.

**Nearly-sorted input (generic variant only):**
For `T[]`, TimSort's run-detection is specifically designed to exploit existing order and wins outright here (0.53x for BusSort). The `int[]` variant doesn't show this weakness as sharply (1.14x, still a BusSort win) — the gap is generics-specific, likely because TimSort's per-comparator-call overhead on primitives-in-a-comparator scenario is different from its native run-merging efficiency on objects. BusSort's histogram approach doesn't detect or exploit "already mostly sorted" at all, by design — it always pays the full histogram-and-scatter cost regardless of existing order.

**No pure `Comparator<T>` support:**
BusSortGenerics requires a `ToIntFunction<T>` key extractor — it cannot sort arbitrary `Comparable` objects without a numeric key. This is a fundamental trade-off: arithmetic bucketing requires a numeric key; comparison-based sorting does not.

---

## Reproducing the Benchmarks

### `int[]` benchmarks

```bash
cd benchmarks
mvn clean package -DskipTests
java -jar target/benchmarks.jar -wi 5 -i 10 -f 3 -prof gc
```

Or narrow to a specific size/input type:
```bash
java -jar target/benchmarks.jar -wi 5 -i 10 -f 3 -prof gc -p n=100000000 -p inputType=RANDOM
```

### `T[]` generic benchmarks

```bash
cd generics/benchmarks
mvn clean package -DskipTests
java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 5 -i 10 -f 3 -prof gc
```

> Note: `T[]` benchmarks default to `n` up to 10,000,000. `Record[]` objects carry far more per-element memory overhead than raw `int[]`, and larger sizes were found to exhaust heap on an 8GB machine even with a generous `-Xmx`. Raise the size cap if your machine has more headroom, and consider `-Xmx` explicitly for anything above 10M.

### Peak memory profiling

Standalone (non-JMH) profilers using `MemoryPoolMXBean` peak-usage tracking — see `MemoryProfiler.java` and `MemoryProfilerGenerics.java` alongside the respective benchmark files.

```bash
# int variant
cd benchmarks/src/main/java/dev/shreyas
javac BusSort.java MemoryProfiler.java
java -Xmx8g dev.shreyas.MemoryProfiler

# generic variant
cd generics/benchmarks/src/main/java/dev/shreyas
javac BusSortGenerics.java MemoryProfilerGenerics.java
java -Xmx4g dev.shreyas.MemoryProfilerGenerics
```

---

## Comparison with Similar Algorithms

| Algorithm            | Stable | Beats DPQ on random | Beats TimSort on random | Cache-aware  | Notes |
|----------------------|--------|---------------------|--------------------------|--------------|-------|
| Dual-Pivot Quicksort | ❌      | baseline            | —                        | partial      | Java default for primitives |
| TimSort              | ✅      | ❌                   | baseline                 | partial      | Java default for objects |
| **BusSort (int[])**  | ✅      | **2.66x**           | —                        | **yes (L1)** | This work |
| **BusSortGenerics**  | ✅      | —                    | **2.87x**                | **yes (L1)** | This work |

---

## Roadmap

- [x] Stable `int[]` sort — beats Dual-Pivot Quicksort
- [x] Stable reverse path
- [x] Generic `T[]` support via `ToIntFunction<T>`
- [x] JMH benchmarks for both variants, including adversarial-input testing
- [x] Sequential parameter tuning (bucket count, chunk size, insertion-sort threshold)
- [x] GC pressure and peak-memory measurement
- [ ] Health-check fallback for adversarial distribution collapse (documented above, not yet implemented — open question for feedback)
- [ ] Dynamic stack sizing
- [ ] Auto-tune `BUS_SIZE` based on runtime L1 cache size
- [ ] Parallel / multi-threaded variant
- [ ] Port to C for lower-level benchmarking
- [ ] Formal write-up / paper

---

## Author

**Shreyas Subhash Patil** — Built and benchmarked independently.
If you use or build on this, a mention would be appreciated.
[LinkedIn](https://www.linkedin.com/in/shreyaspatil14) · [dev.to](https://dev.to/dev-shreyas)
