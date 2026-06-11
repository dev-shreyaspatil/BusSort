# BusSort

A stable sort that consistently beats Dual-Pivot Quicksort at 100M elements, including ~2x on random data and duplicate-heavy inputs.
---

## What is BusSort?

BusSort is a **cache-aware, stable, histogram-based sorting algorithm** for integers. It is designed around the constraints of modern CPU memory hierarchies — specifically L1 cache size — to minimize cache misses during the most expensive part of sorting: random writes.

Unlike comparison-based sorts (Quicksort, Mergesort, TimSort), BusSort exploits the **distribution of values** directly, using histograms to know exactly where every element belongs before moving it.

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
- **Reverse sorted** → stable reverse copy into buffer, return (O(n))
- **All same value** → `min == max` check skips the bucket entirely

---

## Why Is It Fast?

| Problem with naive bucket/radix sort | BusSort's solution |
|---|---|
| Scattering n elements globally → L1/L2 cache miss on every write | Process in L1-sized chunks → all random writes stay in cache |
| Wide value ranges → many empty buckets wasted | 128 buckets dynamically mapped to `[min, max]` range |
| Deep recursion on large arrays | 128-way split → only ~4 levels deep at 100M elements |

The core insight: **bounded random access is cache-friendly random access.** By keeping the scatter buffer at L1 size, the algorithm converts what would be millions of cache misses into cache hits.

---

## Stability

BusSort is **stable** — equal elements preserve their original relative order.

This holds because:
- Input chunks are processed **left-to-right**
- Within each chunk, elements are scattered **left-to-right**
- `globalNext[b]` advances per chunk, so earlier chunks always land before later chunks in the output

This makes BusSort one of very few sorting algorithms that is both **faster than Dual-Pivot Quicksort** and **stable**.

> Note: The reverse-sorted early exit path requires care for generic object sorting (equal-key objects). The main distribution path is unconditionally stable.

---

## Benchmarks

### Current — `int[]` vs Dual-Pivot Quicksort

Tested against Java's `Arrays.sort(int[])` which uses **Dual-Pivot Quicksort** — one of the most optimized sorting algorithms in production use today.

**Machine:** 11th Gen Intel Core i5-1135G7 @ 2.40GHz, Java 17, n = 100,000,000

| Input Type | BusSort | Dual-Pivot QS | Ratio | Correct |
|---|---|---|---|---|
| Random | 3991ms | 8604ms | **2.2x** | ✅ |
| Sorted | 57ms | 104ms | **1.8x** | ✅ |
| Reverse | 280ms | 166ms | 0.6x | ✅ |
| Nearly Sorted | 2452ms | 2789ms | **1.1x** | ✅ |
| Duplicates | 712ms | 2242ms | **3.1x** | ✅ |
| Few Duplicates | 1295ms | 3185ms | **2.5x** | ✅ |
| All Same | 51ms | 32ms | 0.6x | ✅ |
| Clustered | 1419ms | 2242ms | **1.6x** | ✅ |

**6/8 input types faster. Stable. Zero comparison overhead.**

The two losses (Reverse, All Same) are on inputs where Dual-Pivot QS has structural advantages — run detection and early termination. BusSort handles both correctly, just without the same specialization.

### Upcoming — `Integer[]` / Generic vs TimSort
Once generic object support is added, benchmarks against Java's `Arrays.sort(Integer[])` (TimSort) will be published here.

---

## Complexity

| | BusSort |
|---|---|
| Time (average) | O(n · k) where k = recursion depth ≈ log₁₂₈(n) |
| Time (best) | O(n) — sorted/reverse/all-same early exit |
| Time (worst) | O(n · log₁₂₈(n)) |
| Space | O(n) — output buffer + O(1) auxiliary arrays |
| Stable | ✅ Yes |
| Comparison-based | ❌ No |
| In-place | ❌ No |

---

## Parameters

| Constant | Value | Meaning |
|---|---|---|
| `BUCKETS` | 128 | Number of buckets per level |
| `BUS_SIZE` | 4096 | Chunk size (fits in L1 cache for `int[]`) |
| `THRESHOLD` | 1024 | Fall back to insertion sort below this size |

These are tunable. `BUS_SIZE` should be chosen so that `BUS_SIZE * 3 * sizeof(element)` fits comfortably in L1 cache (`busValues` + `busBucket` + `grouped`).
On the benchmark machine (i5-1135G7, 48KB L1 data cache), `4096 × 3 × 4 = 49,152 bytes ≈ 48KB` — fitting exactly in L1. This is why the default value is not arbitrary.

---

## Comparison with Similar Algorithms

| Algorithm | Stable | Beats DPQ on random | Cache-aware | Notes |
|---|---|---|---|---|
| Dual-Pivot Quicksort | ❌ | baseline | partial | Java default for primitives |
| TimSort | ✅ | ❌ | partial | Java default for objects |
| **BusSort** | ✅ | **yes (2.2x)** | **yes (L1)** | This work |

---

## Roadmap

- [ ] Generic object support (`Comparator<T>` with key extractor)
- [ ] Benchmark generic variant against TimSort
- [ ] Parallel / multi-threaded variant
- [ ] Port to C for lower-level benchmarking
- [ ] Formal write-up / paper

---

## Status

> **Theory and benchmarks published. Code coming soon.**  
> The algorithm is fully designed and verified. Implementation will be released after further improvements and generic support is added.

---

## Author

**Shreyas Subhash Patil** — Built and benchmarked independently.  
If you use or build on this, a mention would be appreciated.  
[LinkedIn](https://www.linkedin.com/in/shreyaspatil14)
