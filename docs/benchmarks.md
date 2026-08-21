# Benchmarks

Recorded so that each phase has something to be measured against. Spec §10 asks for JMH
microbenchmarks per primitive plus end-to-end throughput; §9 makes each later phase's exit criterion
a comparison, and a comparison needs a number from before.

Regenerate with `./gradlew :rule-engine-testkit:jmh :rule-engine-cel:jmh` — two modules, two
`results.txt` files, and nothing collates them, so the tables below are assembled by hand. The source is
`rule-engine-testkit/src/jmh/java/com/codeheadsystems/rules/bench/`, plus
`rule-engine-cel/src/jmh/.../ExpressionBenchmarks.java`. Every benchmarking module takes its
sizing from `buildlogic.jmh-conventions`, because columns from differently-warmed suites are
not comparable and comparing columns is the whole point.

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
EngineBenchmarks.comparison              N/A      N/A          EQ              N/A       1.6 ±    3.7  ns/op
EngineBenchmarks.comparison              N/A      N/A          NE              N/A       1.3 ±    0.4  ns/op
EngineBenchmarks.comparison              N/A      N/A          IN              N/A       7.0 ±    2.8  ns/op
EngineBenchmarks.comparison              N/A      N/A          GT              N/A       1.4 ±    0.4  ns/op
EngineBenchmarks.insertBatchCopying      N/A      N/A         N/A                5   14 726 ± 10 783  ns/op
EngineBenchmarks.insertBatchCopying      N/A      N/A         N/A               50  102 463 ±205 714  ns/op
EngineBenchmarks.insertBatchOwned        N/A      N/A         N/A                5    4 953 ± 13 709  ns/op
EngineBenchmarks.insertBatchOwned        N/A      N/A         N/A               50    4 928 ±  7 323  ns/op
EngineBenchmarks.oneShotSession           10      N/A         N/A              N/A   20 696 ± 14 547  ns/op
EngineBenchmarks.oneShotSession          100      N/A         N/A              N/A1 262 489 ±783 340  ns/op
EngineBenchmarks.refractionProbe         N/A    1 000         N/A              N/A      16.9 ±    1.1  ns/op
EngineBenchmarks.refractionProbe         N/A  100 000         N/A              N/A      12.3 ±    9.2  ns/op
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
EngineBenchmarks.oneShotSession       10    NETWORK    15 361 ±        686  ns/op
EngineBenchmarks.oneShotSession       10      NAIVE    21 378 ±        861  ns/op
EngineBenchmarks.oneShotSession      100    NETWORK   454 843 ±     61 989  ns/op
EngineBenchmarks.oneShotSession      100      NAIVE 1 232 042 ±  1 746 044  ns/op
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


---

# Phase 2: indexed joins

The Phase 1 note above ended by saying the growth curve was still super-linear because the join was
still enumerate-then-filter, and that flattening it was Phase 2's job. It is flattened.

A first attempt at measuring this failed instructively and is worth recording, because the failure
is easy to repeat. It joined two thousand orders against three customers — lopsided, which is the
shape the join planner exists for — and measured essentially nothing: 3.50ms against 4.05ms with
error bars of ±4.9ms. The reason was not the engine. Two thirds of those orders *matched*, so the
benchmark spent its time firing six hundred right-hand sides and whatever the join did was invisible
underneath them. **A join benchmark has to produce few matches, or it is a right-hand-side
benchmark.**

So: both sides large, and a join key selective enough that only five pairs match.

**And then it was wrong a second time, in a subtler way.** That version still measured session
construction and `2 x facts` copying inserts along with the join. A review measured the split and
found inserts were between half and three quarters of the network arm — so the "linear growth"
reading described insert cost, not join cost. Insert cost is linear in `facts`, which means a
network doing a perfectly linear join and one doing *no join at all* would both have looked linear.
The conclusion was unsupported even though it happened to be true.

Population and session construction now happen in per-invocation setup, so the measured region is
`fireAllRules` alone. The correction is visible in the numbers: at 2000 facts the network arm went
from 1 048us to 206us, i.e. **80% of what the previous table called join cost was insert cost.**

```
Benchmark                       (facts)  (matcher)          Score           Error  Units
EngineBenchmarks.selectiveJoin      500    NETWORK         68 211 ±       225 756  ns/op
EngineBenchmarks.selectiveJoin      500      NAIVE     14 978 866 ±    14 803 190  ns/op
EngineBenchmarks.selectiveJoin     2000    NETWORK        206 033 ±       153 216  ns/op
EngineBenchmarks.selectiveJoin     2000      NAIVE    304 932 781 ±   141 780 185  ns/op
```

**Read the error bars.** The network figures carry relative errors of 74% and over 300%, because
the absolute numbers are small and three two-second iterations is not much. Treat them as
"tens to hundreds of microseconds", never as figures. The oracle's are 46% and 99%.

What survives all of that is the separation and the shape, both of which are far outside the noise:

| | 500 -> 2000 facts (4x) | growth |
|---|---|---|
| Network | 68us -> 206us | **3.0x — linear or better** |
| Oracle | 15.0ms -> 305ms | **20.4x — quadratic** (4^2 = 16) |

Four times the facts costs the network about four times the work and the oracle about sixteen to
twenty. That is the difference between probing an index once per fact and comparing every fact
against every other, and it is §3.3's claim measured: indexed join probing is "the single biggest
lever for join-heavy rule sets, and exactly what hand-rolled 'simple' engines skip and then can't
scale."

The separation is two to three orders of magnitude. Do not quote that as a multiple either — with
the network arm this noisy, the honest statement is the growth exponent, not the ratio.

## What is still not measured

- **The join planner specifically.** Both sides of this benchmark are the same size, so it measures
  indexed probing and not the per-fire reordering. Isolating the planner needs a lopsided join whose
  matches stay few, which is an awkward shape to construct: lopsidedness usually brings either very
  many matches or very few candidates. `JoinPlanTest` asserts the ordering decisions structurally;
  no benchmark yet puts a number on what they are worth.
- **Node sharing's effect on insert cost.** The benchmark rule sets have almost no duplicate
  constraints, so they exercise none of the sharing §6.5's sublinearity claim is about.
- **The prefix trie.** Six tested paths is far too few for the difference to appear.
- **Concurrent multi-session throughput**, still Phase 4's deliverable.

# The gated update, and Phase 5

| | |
|---|---|
| Date | 2026-08-21 |
| Engine | Phases 0-2 and 5 complete; both matchers |
| JDK | 25 (JetBrains Runtime, Linux x86-64) |
| JMH | 1.36, `AverageTime`, 1 fork, 3 warmup + 3 measurement iterations at 2s |

**Three iterations is not much, and the tables below say so per row.** Where a relative error exceeds
its own figure, no multiple is quoted — the rule the Phase 2 section set for itself.

The Phase 1 and Phase 2 benchmarks were re-run at the same time. `oneShotSession` and
`selectiveJoin`'s network arm reproduce (437us and 215us against 455us and 206us recorded above).
The naive `selectiveJoin` arm came in at 222ms against the 305ms recorded above — a 27% move, which
is inside neither error bar and is unexplained. The tables above were left as they were rather than
silently reconciled; treat that row as "hundreds of milliseconds" in both runs.

## §3.4.1's gated update

§9 gives Phase 1 a two-part exit criterion and is careful that the parts test different things. The
correctness part is asserted on a counter in the suite — "assert it, don't infer it", since "no
propagation happened" is trivially satisfied by an engine that never propagates. This is the other
part, which a counter cannot answer: whether the no-op path is actually *cheap*.

```
Benchmark                           (testedPaths)      Score       Error  Units   rel.err
UpdateBenchmarks.untestedPathBatch              2      8 667 ±       789  ns/op       9%
UpdateBenchmarks.untestedPathBatch             40     51 144 ±     4 385  ns/op       9%
UpdateBenchmarks.testedPathBatch                2     25 451 ±       352  ns/op       1%
UpdateBenchmarks.testedPathBatch               40    190 153 ±    26 086  ns/op      14%
```

One op is a batch of 100 updates; divide by 100 for a per-update figure.

**The gate is worth roughly 3x, and that survives the error bars.** 87ns against 255ns per update at
two tested paths, 511ns against 1 902ns at forty — 2.9x and 3.7x, with every row inside 14%. §3.4.1's
diff really does stop before the retract-and-reassert.

**The no-op is linear in the rule set's tested paths, not constant.** Twenty times the paths costs
six times the no-op update. A two-point fit puts it near 11ns per tested path over roughly 64ns of
fixed overhead — two points is a line by definition, so read those as the right order of magnitude
and not as a slope.

That is not a criticism of §3.4.2. The forty paths here are `watched0`..`watched39`: forty *disjoint*
top-level fields, the worst possible case for a prefix trie, because there is no shared prefix to
collapse. This measures the walk, not the pruning. **The trie's actual claim remains unmeasured** —
the shape that would show it is a rule set whose tested paths share deep prefixes.

**What is outside the measured region, and why.** `updateOwned` rather than `update`, so §2.2's
payload copy is not on the clock — it is already measured by `insertBatchCopying` against
`insertBatchOwned`. One session for the trial, since session construction is measured by
`oneShotSession`. And a batch of 100, because the interesting cost is a few hundred nanoseconds and
per-invocation setup cannot be trusted at that scale.

There is deliberately **no matcher parameter**. `DefaultRuleSession` installs its observer
unconditionally and that observer maintains the network's memories on every fact change whatever the
`MatchingStrategy`; the strategy only selects `matchesOf`, which runs at fire time, and this
benchmark never fires. Parameterising on it produced two identical columns — which an earlier draft
of this section reported as a finding about memory-maintenance costs. It was not a finding. It was
the same code run twice.

## Phase 5: what the DSL and the escape hatch cost

Nothing here was measured before; the sections above predate Phase 5.

### Compiling a rule set: parsing is the expensive half

```
Benchmark                            (rules)      Score       Error  Units   rel.err
CompilationBenchmarks.compileFromAst      10     42.520 ±    14.613  us/op      34%
CompilationBenchmarks.compileFromAst     100    424.677 ±    30.610  us/op       7%
CompilationBenchmarks.parseRuleFile       10    319.923 ±    27.576  us/op       9%
CompilationBenchmarks.parseRuleFile      100  3 200.577 ±    59.430  us/op       2%
CompilationBenchmarks.compileRuleFile     10    381.046 ±    15.357  us/op       4%
CompilationBenchmarks.compileRuleFile    100  3 887.018 ±   131.999  us/op       3%
```

**§6.5's pipeline is the cheap stage.** At a hundred rules, validation, accessor and regex
compilation, node sharing, index plans, tested paths, the version hash and the report together cost
0.42ms; getting there from YAML costs 3.20ms. If rule-set loading ever needs to be faster, the DSL is
where the time is. The ratio is about 7.5x at a hundred rules and about 7.5x at ten, so it is at
least stable across the range measured.

**Each column grows about tenfold for ten times the rules.** Two points is not a curve, so that is
consistent with linear rather than evidence of it. Note also what this rule set cannot show: every
rule shares `total > 10000` and the `Customer` join, so there are about four distinct constraints
however many rules there are and **the alpha network is constant in rule count**. §6.5's sublinearity
claim is about network size, and nothing here measures it.

**These are warm figures and a service's first compile is not.** The rule-file schema is compiled
once in a static holder and both Jackson mappers are static finals, so JMH's warmup amortises every
one-time cost to zero. Cold startup is a real question and this table does not answer it; answering
it needs a single-shot benchmark in a fresh fork, which does not exist yet.

### §6.4's escape hatch: the fire cycle, and only the fire cycle

§6.4 makes one quantified claim about the expression escape hatch: "an unindexed CEL condition
against 100 000 facts is 100 000 evaluations per fire cycle. Cheap-per-call is not cheap."

Both rules select the same facts and fire the same right-hand side; only the constraint form differs,
and **the session is loaded in per-invocation setup so that only `fireAllRules` is measured**. That
placement is the benchmark, not a detail — see the note at the end of this file for what happened
when it was not.

```
Benchmark                       (facts)            (form)      Score      Error  Units   rel.err
ExpressionBenchmarks.fireCycle     1000      OPERATOR_MAP      0.603 ±    1.536  us/op     255%
ExpressionBenchmarks.fireCycle     1000        EXPRESSION    400.776 ±   38.705  us/op      10%
ExpressionBenchmarks.fireCycle     1000  EXPRESSION_VALUE      3.136 ±    3.179  us/op     101%
ExpressionBenchmarks.fireCycle    10000      OPERATOR_MAP      2.377 ±   22.200  us/op     934%
ExpressionBenchmarks.fireCycle    10000        EXPRESSION  5 290.520 ±   335.989  us/op      6%
ExpressionBenchmarks.fireCycle    10000  EXPRESSION_VALUE      9.599 ±   50.118  us/op     522%
```

**Read the two cheap columns as noise floor, not as figures.** Their errors run from 100% to 934%,
because there is almost nothing to measure: the operator map's work happened at insert time, and by
fire time the facts that failed it are not candidates. So **no ratio is quoted here.** The honest
statement is that the expression arm is measured in hundreds of microseconds to milliseconds while
the other two sit at or below what three two-second iterations can resolve.

**The expression column is the one with something in it, and it is well determined** — 6% and 10%
relative error. About 400ns per candidate at a thousand facts and 530ns at ten thousand. That is
§6.4's sentence measured: the cost is the *number* of evaluations, one per candidate per cycle.

**It grew 13.2x for 10x the facts**, which is faster than linear and outside both error bars. This
benchmark does not explain why, and it is worth someone's attention: the per-candidate cost rising
from 400ns to 530ns as the memory grows is the kind of thing that is either an allocation effect or
something structural in the post-filter.

**A value expression stays at the floor** — 3.1us and 9.6us, both dominated by their own error — which
is the once-per-firing claim behaving as §6.4 says it should.

**What the operator map pays instead is one alpha test per insert**, which `EngineBenchmarks.comparison`
measures at a little over a nanosecond. That is the other half of the comparison, and it belongs on
the insert clock rather than this one.

## What is still not measured

Carried forward, plus what Phase 5 added:

- **Cold-start compilation.** The table above is warm. A fresh service pays one-time costs JMH
  amortises away, and nothing measures them.
- **Why the expression post-filter grows faster than linearly.** 13.2x for 10x facts, unexplained.
- **The prefix trie's pruning.** The update benchmark uses disjoint top-level paths, the worst case
  for a trie, and so measures the walk. Needs tested paths sharing deep prefixes.
- **Node sharing's effect on network size and insert cost.** `NetworkStructureTest` asserts the
  sharing structurally; no benchmark puts a number on it, and `CompilationBenchmarks`' rule set has a
  constant-size alpha network by construction.
- **The join planner specifically.** Both sides of `selectiveJoin` are the same size, so it measures
  indexed probing rather than the per-fire reordering.
- **Fact-payload schema validation.** §2.3 makes it opt-in and says nothing about its cost, so an
  author choosing whether to register a schema has no figure to weigh.
- **How the concurrency curve moves once §5.3's within-session parallelism exists.** The section
  below measures the across-session model only, which is the only one v1 has.


---

# Phase 4: does it scale across cores?

| | |
|---|---|
| Date | 2026-08-21 |
| Engine | Phases 0-2, 4 and 5 complete; `NETWORK` matcher |
| Machine | AMD Ryzen 7 7840U — **8 physical cores, 16 logical (SMT)**, `powersave` governor, IDE and Gradle daemons resident |
| JDK | 25 (OpenJDK, Linux x86-64) |
| JMH | 1.36, `AverageTime`, **3 forks**, 3 warmup + 5 measurement iterations at 2s (15 samples per row) |

Regenerate exactly:

```
./gradlew :rule-engine-testkit:jmhJar
java -jar rule-engine-testkit/build/libs/*-jmh.jar ConcurrencyBenchmarks -wi 3 -i 5 -r 2s -w 2s
```

**Three forks, not the suite's one, and the class now carries `@Fork(3)` so this is a setting rather
than a request.** JMH's ± is the spread across iterations *within* one fork, and every parameter
combination here is its own fork, so at one fork the error bars say nothing about how much of a gap
between two columns is JIT and layout luck. That is not hypothetical — see the calibration below,
which is what caught it.

## The calibration row, which is the first thing to read

At `threads=1` the `sharing` parameter is a **null experiment**: one thread means one thread-local
rule set, so PRIVATE and SHARED are the same configuration running twice. Whatever separation that
row shows is the floor below which no difference anywhere else in the table means anything.

| forks | SHARED @1 | PRIVATE @1 | apparent difference |
|---|---|---|---|
| 1 | 36.385 ± 0.257 | 37.773 ± 0.659 | 3.8%, error bars **not** overlapping |
| 3 | 37.063 ± 0.435 | 37.118 ± 0.590 | 0.15%, overlapping |

The single-fork run reported a 3.8% difference, with non-overlapping error bars, between a
configuration and itself. An earlier draft of this section read a 6% gap elsewhere in that same table
as a cache-footprint finding. It was inter-fork noise. That claim is retracted, not softened, and
this row exists so the next one gets caught the same way.

## What is inside the measured region

Written down before the numbers, per this file's standing rule. `concurrentBatches` measures
submitting 256 tasks to an already-running pool, each creating a session, inserting 50 order/customer
pairs, firing to completion and closing; then awaiting all of them. Rule compilation, executor
construction and thread creation are all in trial setup. **Total work is fixed at 256 batches and the
thread count varies**, so perfect scaling is `time(N) == time(1) / N`. A benchmark that ran 256
batches *per thread* would have shown a flat line and proved nothing.

## The curve, and the control it must be read against

```
Benchmark                                (sharing)  (threads)  Cnt    Score   Error  Units
ConcurrencyBenchmarks.concurrentBatches     SHARED          1   15   37.063 ± 0.435  ms/op
ConcurrencyBenchmarks.concurrentBatches     SHARED          2   15   20.052 ± 0.167  ms/op
ConcurrencyBenchmarks.concurrentBatches     SHARED          4   15   11.239 ± 0.082  ms/op
ConcurrencyBenchmarks.concurrentBatches     SHARED          8   15    6.398 ± 0.169  ms/op
ConcurrencyBenchmarks.concurrentBatches     SHARED         16   15    6.033 ± 0.082  ms/op
ConcurrencyBenchmarks.sharedNothingBaseline SHARED          1   15   48.504 ± 0.305  ms/op
ConcurrencyBenchmarks.sharedNothingBaseline SHARED          2   15   24.583 ± 0.095  ms/op
ConcurrencyBenchmarks.sharedNothingBaseline SHARED          4   15   12.896 ± 0.166  ms/op
ConcurrencyBenchmarks.sharedNothingBaseline SHARED          8   15    6.779 ± 0.041  ms/op
ConcurrencyBenchmarks.sharedNothingBaseline SHARED         16   15    4.131 ± 0.141  ms/op
```

| threads | engine speedup | efficiency | baseline speedup | efficiency | engine as % of baseline |
|---|---|---|---|---|---|
| 1 | 1.00x | 100% | 1.00x | 100% | 100% |
| 2 | 1.85x | 92% | 1.97x | 99% | 94% |
| 4 | 3.30x | 82% | 3.76x | 94% | 88% |
| 8 | 5.79x | 72% | 7.16x | 89% | 81% |
| 16 | 6.14x | 38% | 11.74x | 73% | 52% |

`sharedNothingBaseline` is the experimental control: the identical harness — same pool, same 256
tasks, same submit-and-await — over arithmetic on locals. It exists because **a raw scaling curve on
a laptop measures the laptop.** Under a `powersave` governor a one-thread run boosts to a clock an
all-core run cannot hold, the 16-thread column is 16 threads on 8 cores, and the machine is not
quiescent. All of that would produce sub-linearity before the engine did anything.

**The control does not excuse the engine, but it accounts for a real share.** At 8 threads the
engine gives up 27.6 points of efficiency and the box gives up 10.6 of them, so **roughly two fifths
of the shortfall is the machine and three fifths is the engine.** (An earlier draft said "about a
tenth"; that was arithmetic error, not a different measurement.) Against its own machine's ceiling
the engine reaches 81% at 8 threads.

**Near-linear holds to 4 threads and degrades from there.** 92% and 82% at two and four threads; 72%
at eight, the physical core count. Whether that satisfies §9's "scale near-linearly for the batch
case" is a judgement call, and the number is recorded rather than graded so a later change has
something to be compared against.

**SMT buys the engine almost nothing** — 1.06x from 8 threads to 16, where the arithmetic baseline
gets 1.64x. That is the most informative row, and it is worth being careful about what it means,
because the obvious reading is backwards.

SMT fills one thread's idle issue slots with a sibling thread's instructions. A workload full of
*memory stalls is therefore the classic SMT beneficiary* — a cache miss is precisely the idle slot
the sibling uses. So "SMT does not help this" cannot mean "this is memory-bound"; if the engine were
merely stalling on memory latency, the 16-thread column would look like the baseline's.

The baseline shows what SMT paying off looks like: `accumulator += index ^ (accumulator >>> 3)` is a
serial dependency chain with very low ILP — each iteration waits on the last — so it leaves slots
idle every iteration and a sibling thread fills them, hence 1.64x. What 1.06x indicates instead is
**saturation of a shared resource** at 8 threads: memory bandwidth, last-level cache capacity, or the
allocator. A second hyperthread on the same core adds demand to something already at its limit and
gets nothing back. The two rows are consistent with each other under that reading and contradictory
under the other one.

§5.5 predicted the ceiling would be "cache-line sharing, not locking". The data agrees it is not
locking — the shared-versus-private table rules that out directly — and points at a saturated shared
resource rather than at contention over the rule set. **Which** resource is exactly what `-prof gc`
would name, and it has not been run.

## Sharing the rule set costs nothing, which is §5.5's actual claim

The `sharing` parameter gives every task one shared rule set, or every *thread* its own — keyed by
thread rather than by batch index, so that "no two concurrent tasks share a rule set" is true by
construction rather than true of whatever order the pool happened to pick.

| threads | SHARED | PRIVATE |
|---|---|---|
| 1 | 37.063 ± 0.435 | 37.118 ± 0.590 |
| 2 | 20.052 ± 0.167 | 19.875 ± 0.164 |
| 4 | 11.239 ± 0.082 | 11.130 ± 0.099 |
| 8 | 6.398 ± 0.169 | 6.557 ± 0.298 |
| 16 | 6.033 ± 0.082 | 6.068 ± 0.060 |

**Every row overlaps.** §5.5 stakes the whole scaling story on "thousands of concurrent virtual
threads reference the same `CompiledRuleSet` with zero contention, because nothing about it mutates
after compile", and this is that claim measured: the private column is what zero contention has to
be compared against, and there is nothing between them at any thread count.

No cache-footprint effect is claimed in either direction, and the threshold for claiming one comes
from the calibration row rather than from taste: its error half-widths are ±1.2% and ±1.6% of score,
so a gap under about 1.5% is inside the noise this method can resolve. None of these gaps reach it.
(The 0.15% *separation* on that row is how far apart two identical configurations landed; the ±1.2%
and ±1.6% are how far apart they could have landed. It is the second number that sets the floor.)

## Session creation: 248ns

```
ConcurrencyBenchmarks.sessionCreation  avgt  15  248.320 ± 3.065  ns/op
```

From the same run as everything above, which needed a method-level `@OutputTimeUnit(NANOSECONDS)`:
JMH's score formatter collapses anything below a thousandth of the chosen unit to `~= 10^-4`, so in
this class's milliseconds the row printed no digits at all. An earlier draft quoted a number taken
from a *separate* invocation and presented it inside this table.

§10 calls this "your concurrency throughput ceiling" — the per-task constant no amount of parallelism
amortises. At 248ns against a 145us batch it is 0.17% of the workload above, so for batches of this
shape it is not the ceiling in practice. One order of magnitude smaller it is still only 1.7%; two
orders down, at a 1.4us batch, it is a sixth of the work and the name §10 gives it starts to earn
itself. That is the reason to have the number rather than assume it.

## What Phase 4 still does not measure

- **Whether the 8-thread shortfall is allocation.** The SMT row points that way but does not prove
  it; `-prof gc` on this same benchmark would, and was not run.
- **The virtual-thread path.** `concurrentBatches` uses platform threads deliberately, so the column
  count means cores. `RuleBatches` ships one virtual thread per task over the carrier pool, so it is
  *expected* to track this curve — a similar shape is a reasonable expectation, not a construction —
  and its own per-task overhead is unmeasured.
- **Swap cost under load.** `RuleSetHolder.publish` is a volatile write and `ConcurrencyTest` proves
  it mixes nothing, but nothing measures whether a publish perturbs in-flight throughput.
- **Drain and replay.** `SessionDrain.restart` is O(asserted facts) by inspection and untimed.
- **Whether the immutability audit stays true.** Two instances of the shallow-copy trap have been
  found by hand and a manual sweep found no third — but a sweep expires the moment someone adds a
  field. The systemic answer is a test that walks everything reachable from a `CompiledRuleSet`
  reflectively and asserts every `Collection` and `Map` it finds rejects mutation, turning "somebody
  checked" into "the build checks". `ImmutabilityTest` covers the literals only.
- **Listener dispatch under concurrency.** `TracingListener` now takes a lock per firing (§7.1's
  premise about per-session listeners being false, see the amendment there). Once per firing is not
  once per candidate, so this is expected to be invisible — but "expected" is what this file exists
  to replace.

## A note on this file's own history

Three versions of the join benchmark: the first measured right-hand sides, the second measured
inserts, the third measures the join. Both earlier ones produced a plausible number and a plausible
story. Neither was measuring what its heading said. If you add a benchmark here, say what is inside
the measured region and what is outside it, and check that the thing you are claiming credit for is
actually the majority of what is on the clock.

Then it happened three more times, which is why this section is no longer a footnote about one
benchmark. Its first version called `update` rather than `updateOwned`, so it measured §2.2's
payload deep copy: cost grew 3.6x with payload size and read *identically* on both matchers, which
cannot happen if a tested-path diff is on the clock. Its second version fixed that but sized the
payload from the `testedPaths` parameter, so the wide arm changed two variables at once and the 6x
growth it reported could not be attributed to either. Only the third version varies one thing.

And `ExpressionBenchmarks` shipped the worst one. Its first version loaded the facts *inside* the
measured region, so `session.insert` was 96-98% of the cheaper arm and the headline was
`(insert + expression) / (insert + almost nothing)`. Every conclusion drawn from it was an artifact
of that shared term: the "flat 4x ratio" was arithmetically guaranteed rather than observed, the
"per-candidate cost of an operator map" compared against an arm with **zero** candidates, and the
number moved when `insert` was swapped for `insertOwned` — a knob with nothing to do with either
constraint form. Hoisting the load into setup changed the finding from "about 4x" to "hundreds of
microseconds against an unmeasurable floor", which is a different claim entirely.

`UpdateBenchmarks` also carried a `matcher` parameter that selected nothing, because the observer
maintains the network whatever the strategy and the benchmark never fires. Two identical columns were
written up as a finding about memory-maintenance cost.

The pattern across all six is the same and it is not subtle: **every wrong version produced a number
and a story that hung together.** Nothing in the output said "this is measuring something else"; each
was caught by reading the code against the claim, or by a reviewer decomposing the measured region.
Three defences have actually worked here — naming what is inside the measured region and what is
outside it, checking that a parameter changes exactly one thing, and checking that a parameter
changes *anything*.



Phase 4 also produced a wrong *inference* over correct numbers, which is a failure mode this note
had not recorded before. The SMT row was read as "SMT cannot help this, therefore it is memory-bound"
-- and that inverts the mechanism, because a workload full of memory stalls is the classic SMT
beneficiary: a cache miss is exactly the idle issue slot the sibling thread fills. The conclusion
happened to survive under a different argument (saturation of a shared resource, which the baseline's
own 1.64x corroborates), but the reasoning printed alongside it contradicted its own premise. Numbers
that reproduce are only half of it; the sentence drawn from them is the other half, and it gets the
same scrutiny.

Phase 4 added a fourth defence, and it is the one the earlier six needed most: **an experimental
control run through the identical harness.** A scaling curve is a ratio between the benchmark's own
columns, so nothing inside it can reveal that the machine, not the code, produced the shape — the
error bars stay tight and the story stays plausible, which is exactly the failure mode above.
`sharedNothingBaseline` is not a benchmark of anything anyone ships; it exists so the concurrency
numbers have something other than a straight line to be compared against, and it changed the reading:
the box scales at 89% where the engine scales at 72%, so about two fifths of the shortfall is the
machine. The first write-up of that put it at a tenth — the control was right and the arithmetic over
it was wrong, which is its own lesson about where to look after a measurement survives review.

Phase 4 also produced the sharpest single correction this file has had, and it was not about a
measured region at all. The concurrency table was run at one fork, and JMH's ± is the spread *within*
a fork while every parameter combination is its own fork — so the error bars said nothing about
inter-fork variance and a 6% gap got written up as a cache-footprint finding. What exposed it was
noticing that one row of the table is a **null experiment**: at `threads=1` the shared-versus-private
parameter compares a configuration with itself, and it was reporting a 3.8% difference with
non-overlapping error bars. **Build a row into the table whose answer you already know**, and read it
first. Nothing else in that section could have caught this, because every other number is a ratio
between two columns that were wrong in the same way.

A seventh entry, of a different kind: the Phase 5 commit left this file with **two copies of its own
tail** — the corrected text and the superseded draft it was meant to replace, one after the other.
The stale copy still asserted the flat 4x expression ratio measured before the facts were moved out
of the measured region, and the "both matchers read identically" non-finding. A reader arriving at
the second copy would have found retracted claims presented as current. Deleted in the Phase 4
commit. Appending to a benchmark log is right; appending a revision instead of replacing what it
revises is how a log grows contradictions.
