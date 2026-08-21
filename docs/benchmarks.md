# Benchmarks

Recorded so that each phase has something to be measured against. Spec §10 asks for JMH
microbenchmarks per primitive plus end-to-end throughput; §9 makes each later phase's exit criterion
a comparison, and a comparison needs a number from before.

Regenerate with `./gradlew :rule-engine-testkit:jmh`. The source is
`rule-engine-testkit/src/jmh/java/com/codeheadsystems/rules/bench/Phase0Benchmarks.java`.

## Run

| | |
|---|---|
| Date | 2026-08-20 |
| Engine | Phase 0, naive matcher (no network, no indexes) |
| JDK | 25 (Temurin/OpenJDK, Linux x86-64) |
| JMH | 1.37, `AverageTime`, 1 fork, 3 warmup + 3 measurement iterations at 2s |

**Read these as order-of-magnitude, not as precise figures.** The iteration counts are sized so the
benchmark actually gets run; the error bars on the batch benchmarks are wide because a session
allocation and 100 inserts is a lumpy unit of work. What they have to support is "Phase 1 made this
faster", which is not a 2% question. Lengthen the iterations before hanging a decision on a small
difference.

## Results

```
Benchmark                            (facts)  (fired)  (operator)  (payloadFields)   Score      Error  Units
Phase0Benchmarks.comparison              N/A      N/A          EQ              N/A       1.6 ±    3.7  ns/op
Phase0Benchmarks.comparison              N/A      N/A          NE              N/A       1.3 ±    0.4  ns/op
Phase0Benchmarks.comparison              N/A      N/A          IN              N/A       7.0 ±    2.8  ns/op
Phase0Benchmarks.comparison              N/A      N/A          GT              N/A       1.4 ±    0.4  ns/op
Phase0Benchmarks.insertBatchCopying      N/A      N/A         N/A                5   14 726 ± 10 783  ns/op
Phase0Benchmarks.insertBatchCopying      N/A      N/A         N/A               50  102 463 ±205 714  ns/op
Phase0Benchmarks.insertBatchOwned        N/A      N/A         N/A                5    4 953 ± 13 709  ns/op
Phase0Benchmarks.insertBatchOwned        N/A      N/A         N/A               50    4 928 ±  7 323  ns/op
Phase0Benchmarks.oneShotSession           10      N/A         N/A              N/A   20 696 ± 14 547  ns/op
Phase0Benchmarks.oneShotSession          100      N/A         N/A              N/A1 262 489 ±783 340  ns/op
Phase0Benchmarks.refractionProbe         N/A    1 000         N/A              N/A      16.9 ±    1.1  ns/op
Phase0Benchmarks.refractionProbe         N/A  100 000         N/A              N/A      12.3 ±    9.2  ns/op
```

## What they say

**The deep copy is the dominant insert cost, exactly as §2.2 claims.** Insert is the batch of 100,
so per fact: copying costs ~147ns at 5 fields and ~1 025ns at 50; the ownership-transfer variant is
flat at ~49ns per fact regardless of payload size, because there is nothing to copy. At 50 fields
the copy is **95% of the cost of inserting a fact** — larger, as §2.2 says, than the alpha tests it
protects. That is the number to quote when deciding whether `insertOwned` is worth its contract at
an ingestion boundary.

**Comparison evaluation is ~1.3–1.6ns for scalars and ~7ns for `in`.** `in` is linear in the array
literal (three elements here) and re-derives each element's canonical key per probe. Phase 1 hoists
the literal side to compile time and turns `eq`/`in` into an index lookup instead, which is where
that 7ns goes.

**The refraction probe is flat from 1 000 to 100 000 fired matches.** That is the point of keying it
on a hash set with its own handle index rather than scanning. (The 100 000 case measuring slightly
*faster* is noise, not a real inversion.)

**The end-to-end number is the one that has to move.** Ten orders and ten customers cost ~21µs;
a hundred of each cost ~1.26ms. Ten times the facts, **sixty-one times the time** — the join is a
cross product, and the naive matcher walks it. That is not a defect in the benchmark; it is §3.1's
"naive re-scan, `O(rules × facts^arity)`" row, measured.

**This is the curve Phase 2 has to flatten.** §3.3 puts it plainly: indexed joins are "the single
biggest lever for join-heavy rule sets, and exactly what hand-rolled 'simple' engines skip and then
can't scale." A repeat of this benchmark that still shows 61× is a Phase 2 that did not work.

## What is not measured yet, and why

- **The update diff.** §9's Phase 1 exit criterion is that an untested-field update is a *measured*
  no-op. It is measured today, but by a counter in the correctness suite
  (`DefaultWorkingMemoryTest.untestedFieldIsAMeasuredNoOp`) rather than by JMH, because in Phase 0
  the interesting cost — walking a large tested-path set, or the prefix trie that replaces it — does
  not exist yet. The benchmark belongs with the thing it measures.
- **Concurrent multi-session throughput.** §10 wants facts/sec and rules-fired/sec under concurrent
  load, and §5.5 wants session-creation cost bounded and measured as the throughput ceiling. The
  correctness of the concurrency model is covered (`SmokeTest.acrossSessionConcurrency`), and
  session creation is inside the `oneShotSession` figure, but the scaling curve is Phase 4's
  deliverable and belongs with it.


---

# Phase 1: the network vs the oracle

Same harness, same machine, with the end-to-end benchmark parameterised by matcher so the two are
compared directly rather than across separate runs.

```
Benchmark                        (facts)  (matcher)     Score        Error  Units
Phase0Benchmarks.oneShotSession       10    NETWORK    15 361 ±        686  ns/op
Phase0Benchmarks.oneShotSession       10      NAIVE    21 378 ±        861  ns/op
Phase0Benchmarks.oneShotSession      100    NETWORK   454 843 ±     61 989  ns/op
Phase0Benchmarks.oneShotSession      100      NAIVE 1 232 042 ±  1 746 044  ns/op
```

**Read the last row with the error bar in view.** At a hundred facts the naive figure's error is
larger than the figure. Three two-second iterations is not enough to pin a lumpy unit of work like
"allocate a session, insert two hundred facts, fire to completion", and the honest statement is that
the network is somewhere between somewhat and several times faster there — not "2.7× faster".
Lengthen the iterations before quoting a number to anyone.

What the numbers do support:

**The shape of the curve improved, which is the point.** Ten times the facts costs the naive matcher
about 58× the time and the network about 30×. Both are still super-linear, and they should be: the
join is still enumerate-then-filter. Phase 1 only made the *candidate sets* smaller. §4.1's TREAT
join is Phase 2, and flattening this curve properly is its job.

**Inserts got more expensive, and that is the trade, not a regression.** The ownership-transfer
insert went from ~49ns per fact in Phase 0 to ~95ns, because an insert now evaluates the type's
distinct alpha tests and files the fact into every pattern memory it belongs to. That is the whole
Rete/TREAT bargain stated in §3.1 — work moves from fire time to insert time — and it is worth
watching in a workload that inserts far more than it fires.

**The microbenchmarks did not move**, as expected: comparison evaluation and the refraction probe
are below the network, and neither phase touched them.

## What is still not measured

- **Node sharing's effect on insert cost.** The benchmark's rule set has almost no duplicate
  constraints, so it exercises none of the sharing that §6.5's sublinearity claim is about. A rule
  set with fifty rules testing the same three constraints is the shape that would show it, and it
  is the shape a real deployment has. `NetworkStructureTest` asserts the sharing structurally; no
  benchmark yet asserts it is worth anything.
- **The prefix trie.** §3.4.2's whole argument is that the diff should cost the size of the change
  rather than the size of the rule set, and the benchmark rule set has six tested paths — far too
  few for the difference to appear. Needs a wide rule set and a high update rate.
- **Concurrent multi-session throughput**, still Phase 4's deliverable.
