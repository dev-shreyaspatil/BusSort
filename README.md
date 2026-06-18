# BusSort

## A cache-aware, stable, histogram-based sorting algorithm that beats Java's Dual-Pivot Quicksort and TimSort on distribution-heavy inputs.

---

## What is BusSort?

BusSort is a **cache-aware, stable, histogram-based sorting algorithm** designed around the constraints of modern CPU memory hierarchies — specifically L1 cache size — to minimize cache misses during the most expensive part of sorting: random writes.

Unlike comparison-based sorts (Quicksort, Mergesort, TimSort), BusSort exploits the **distribution of values** directly, using histograms to know exactly where every element belongs before moving it.

Two variants are available:
- **`BusSort.java`** — for `int[]`, beats Java's Dual-Pivot Quicksort
- **`BusSortGenerics.java`** — for `T[]` via `ToIntFunction<T>`, beats Java's TimSort

---

## Design Outcomes

| Property | Details |
|----------|---------|
| **Naturally Stable** | Equal elements always preserve original relative order — guaranteed by left-to-right chunk processing, not by extra bookkeeping |
| **Cache Efficient** | Scatter buffer bounded to L1 cache size — random writes stay in L1, eliminating cache thrashing |
| **Fast** | Beats DPQ by 2.2x on random data, 8.9x on duplicates vs TimSort at large n |
| **Distribution-Aware** | Bucket range dynamically mapped to `[min, max]` — no wasted buckets regardless of value range |
| **Generic Support** | Works on any object type via `ToIntFunction<T>` key extractor |
| **Zero Comparison Overhead** | No comparator calls in the hot path — pure integer arithmetic for bucketing |
| **Predictable Performance** | No pivot selection, no adversarial worst-case inputs unlike Quicksort |
| **Full Int Range** | Handles negative numbers correctly — `Integer.MIN_VALUE` to `Integer.MAX_VALUE` |
| **Bounded Stack Depth** | 128-way split → max ~4 levels at 100M elements, no JVM stack overflow risk |
| **Parallel-Ready** | Each bucket is independent after the histogram pass — parallel processing can be integrated naturally |

> Note: Stack is currently statically sized (`BUCKETS * 4`). Dynamic stack is a planned improvement.

---

## How It Works

### Step 1 — Global Histogram

Scan the entire input to find `min`, `max`, and divide the value range into **128 buckets**. Count how many elements fall into each bucket. From this, compute the exact start position of every bucket in the output array.

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

After one pass, each of the 128 buckets contains elements in the correct region but internally unsorted. Push each bucket onto an explicit stack and repeat. With 128-way splitting, recursion depth is at most `log₁₂₈(n)` — roughly **4 levels** for 100M elements.

### Step 4 — Base Case

When a bucket has ≤ 1024 elements, sort it with **Insertion Sort**. At this size, insertion sort's sequential memory access and tiny overhead wins.

### Early Exits

- **Already sorted** → return immediately (O(n) detection pass)
- **Reverse sorted** → stable reverse into buffer, return (O(n))
- **All same value** → `min == max` check skips the bucket entirely

---

## Why Is It Fast?

| Problem with naive bucket/radix sort | BusSort's solution |
|--------------------------------------|-------------------|
| Scattering n elements globally → L1/L2 cache miss on every write | Process in L1-sized chunks → all random writes stay in cache |
| Wide value ranges → many empty buckets wasted | 128 buckets dynamically mapped to `[min, max]` range |
| Deep recursion on large arrays | 128-way split → only ~4 levels deep at 100M elements |

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

## Benchmarks

### `int[]` vs Dual-Pivot Quicksort — JMH Results

Tested against Java's `Arrays.sort(int[])` which uses **Dual-Pivot Quicksort**.

**Machine:** 11th Gen Intel Core i3-1115G4 @ 3.00GHz | **Java:** 18 | **n:** 100,000,000
**Methodology:** JMH 1.37 — 5 warmup iterations, 10 measurement iterations, 3 JVM forks

| Input Type     | BusSort (ms) | ± Error | DPQ (ms) | ± Error | Ratio    |
|----------------|-------------|---------|----------|---------|----------|
| Random         | 3864        | ±130    | 8344     | ±268    | **2.2x** ✅ |
| Duplicates     | 890         | ±165    | 2266     | ±31     | **2.5x** ✅ |
| Few Duplicates | 2232        | ±273    | 3382     | ±62     | **1.5x** ✅ |
| Clustered      | 1527        | ±35     | 2394     | ±44     | **1.6x** ✅ |
| Sorted         | 38          | ±0.7    | 33       | ±1.3    | 0.9x ❌  |
| Reverse        | 191         | ±2.9    | 89       | ±2.2    | 0.5x ❌  |
| Nearly Sorted  | 2726        | ±190    | 2303     | ±33     | 0.8x ❌  |
| All Same       | 53          | ±9.9    | 35       | ±0.5    | 0.6x ❌  |

**4/8 input types faster. Stable. Zero comparison overhead.**

**Where BusSort wins:** distribution-heavy inputs — random data, duplicates, clustered ranges. These dominate real-world integer datasets (sensor readings, financial tick data, IDs, leaderboards).

**Where DPQ wins:** structure-heavy inputs — sorted, reverse, nearly sorted, all same. DPQ has specialized run-detection for these cases. BusSort handles them correctly, just without the same structural optimization.

---

### `T[]` vs TimSort — JMH Results (Generic)

Tested against Java's `Arrays.sort(T[])` which uses **TimSort**.

**Machine:** 11th Gen Intel Core i5-1135G7 @ 2.40GHz | **Java:** 17 | **n:** 40,000,000
**Methodology:** JMH 1.37 — 5 warmup iterations, 10 measurement iterations, 3 JVM forks

| Input Type     | BusSort (ms) | ± Error | TimSort (ms) | ± Error | Ratio     |
|----------------|-------------|---------|-------------|---------|-----------|
| Random         | 7357        | ±162    | 21691       | ±277    | **2.9x** ✅ |
| Duplicates     | 619         | ±8      | 5510        | ±55     | **8.9x** ✅ |
| Few Duplicates | 3680        | ±90     | 7412        | ±114    | **2.0x** ✅ |
| Clustered      | 1804        | ±97     | 5599        | ±103    | **3.1x** ✅ |
| All Same       | 34          | ±0.6    | 34          | ±0.8    | ~1x ✅    |
| Sorted         | 63          | ±0.2    | 61          | ±0.3    | ~1x       |
| Nearly Sorted  | 2541        | ±103    | 1835        | ±160    | 0.7x ❌   |
| Reverse        | 652         | ±11     | 313         | ±2      | 0.5x ❌   |

**5/8 input types faster. Naturally stable. 8.9x on duplicates.**

**Where BusSort wins:** distribution-heavy inputs — TimSort has no way to exploit value distribution, BusSort collapses duplicate-heavy data in 1-2 recursion levels.

**Where TimSort wins:** structure-heavy inputs — TimSort's run detection is unbeatable on nearly sorted and reverse data. BusSort processes these correctly but without structural shortcuts.

---

## Reproducing the Benchmarks

### `int[]` benchmarks

```bash
cd benchmarks
mvn clean package -DskipTests
java -jar target/benchmarks.jar -wi 5 -i 10 -f 3 -p n=100000000
```

### `T[]` generic benchmarks

```bash
cd generics/benchmarks
mvn clean package -DskipTests
java -jar target/benchmarks.jar BusSortGenericsBenchmark -wi 5 -i 10 -f 3 -p n=40000000
```

---

## Complexity

|                  | BusSort |
|------------------|---------|
| Time (average)   | O(n · k) where k = recursion depth ≈ log₁₂₈(n) |
| Time (best)      | O(n) — sorted/reverse/all-same early exit |
| Time (worst)     | O(n · log₁₂₈(n)) |
| Space (`int[]`)  | O(n) — output buffer only |
| Space (`T[]`)    | O(n) — output buffer + O(BUS_SIZE) auxiliary |
| Stable           | ✅ Yes — naturally, no extra bookkeeping |
| Comparison-based | ❌ No |
| In-place         | ❌ No |

---

## Parameters

| Constant    | Value | Meaning |
|-------------|-------|---------|
| `BUCKETS`   | 128   | Number of buckets per level |
| `BUS_SIZE`  | 4096  | Chunk size (fits in L1 cache for `int[]`) |
| `THRESHOLD` | 1024  | Fall back to insertion sort below this size |

`BUS_SIZE` should be chosen so that `BUS_SIZE * 3 * sizeof(element)` fits comfortably in L1 cache.
On i5-1135G7 (48KB L1 data cache): `4096 × 3 × 4 = 49,152 bytes ≈ 48KB` — fitting exactly in L1. This is why the default value is not arbitrary.

---

## Comparison with Similar Algorithms

| Algorithm            | Stable | Beats DPQ on random | Beats TimSort on random | Cache-aware  | Notes |
|----------------------|--------|---------------------|------------------------|--------------|-------|
| Dual-Pivot Quicksort | ❌      | baseline            | —                      | partial      | Java default for primitives |
| TimSort              | ✅      | ❌                   | baseline               | partial      | Java default for objects |
| **BusSort (int[])**  | ✅      | **2.2x**            | —                      | **yes (L1)** | This work |
| **BusSortGenerics**  | ✅      | —                   | **2.9x**               | **yes (L1)** | This work |

---

## Roadmap

- [x] Stable `int[]` sort — beats Dual-Pivot Quicksort
- [x] Stable reverse path
- [x] Generic `T[]` support via `ToIntFunction<T>`
- [x] JMH benchmarks for both variants
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
