package com.codeheadsystems.rules.network;

import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.JoinTest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The order one rule's patterns are bound in, chosen fresh for each recomputation (spec §3.3).
 *
 * <p>§3.3 calls indexed join probing "the single biggest lever for join-heavy rule sets, and exactly
 * what hand-rolled 'simple' engines skip and then can't scale", and it says which side to probe:
 * the smaller one, decided per fire cycle, because under TREAT both memory sizes are known.
 *
 * <p>Binding in the rule's written order throws that away. A rule reading "for each pending order,
 * find its customer" enumerates every pending order and probes for one customer each. If there are
 * ten thousand pending orders and four customers, binding customers first and probing for their
 * orders does a fraction of the work — and it is the <em>same rule</em>, matched the same way, with
 * the same result.
 *
 * <p>Two constraints on the order, and the second is the one that matters:
 *
 * <ul>
 *   <li>Smaller memories first, so each step starts from the fewest candidates.
 *   <li><strong>Connected before disconnected, once anything is bound.</strong> A pattern with no
 *       join edge to anything already bound cannot be narrowed, so it multiplies. The total number
 *       of leaves is the same wherever it sits -- multiplication commutes -- but placing it after
 *       the joins means it is only expanded for prefixes that already survived them, which is far
 *       fewer. A large disconnected pattern therefore drifts to the end, while a tiny one is
 *       harmless anywhere and may well be picked first on size alone.
 * </ul>
 *
 * <p>Connectivity does not apply to the <em>first</em> pick, because nothing is bound yet for
 * anything to be connected to. That step is decided on size alone.</p>
 *
 * <p>Reordering does not change what matches, only the order matches are <em>discovered</em>. Which
 * is safe precisely because §4.2 made conflict resolution a total order on the match itself: firing
 * order is a function of the data, not of the order the rebuild loop happened to construct things.
 * The differential suite against the naive oracle is what holds that claim honest.
 */
public final class JoinPlan {

  private final List<Step> steps;

  private JoinPlan(final List<Step> steps) {
    this.steps = steps;
  }

  /**
   * Plans the binding order for one rule against the current memory sizes.
   *
   * @param rule the rule being recomputed
   * @param sizes each pattern's current memory size, by position
   * @return the plan
   */
  public static JoinPlan of(final CompiledRule rule, final int[] sizes) {
    final int arity = rule.patterns().size();
    final List<List<Edge>> edgesByPosition = edges(rule);
    final BitSet bound = new BitSet(arity);
    final List<Step> steps = new ArrayList<>(arity);

    for (int taken = 0; taken < arity; taken++) {
      final int next = choose(arity, sizes, edgesByPosition, bound, taken == 0);
      bound.set(next);
      steps.add(step(rule, next, edgesByPosition.get(next), bound));
    }
    return new JoinPlan(List.copyOf(steps));
  }

  /**
   * The steps, in binding order.
   *
   * @return the plan's steps
   */
  public List<Step> steps() {
    return steps;
  }

  /**
   * Picks the next pattern to bind.
   *
   * @param arity how many patterns the rule has
   * @param sizes each pattern's memory size
   * @param edgesByPosition each pattern's join edges
   * @param bound which positions are already bound
   * @param first whether this is the first pick, where connectivity cannot apply
   * @return the position to bind next
   */
  private static int choose(final int arity, final int[] sizes,
      final List<List<Edge>> edgesByPosition, final BitSet bound, final boolean first) {
    int best = -1;
    boolean bestConnected = false;
    for (int position = 0; position < arity; position++) {
      if (bound.get(position)) {
        continue;
      }
      final boolean connected = !first && isConnected(edgesByPosition.get(position), bound);
      if (best < 0
          || connected && !bestConnected
          || connected == bestConnected && sizes[position] < sizes[best]) {
        best = position;
        bestConnected = connected;
      }
    }
    return best;
  }

  /**
   * Whether a pattern has any join edge to an already-bound pattern.
   *
   * @param edges the pattern's edges
   * @param bound which positions are already bound
   * @return true when binding it can be narrowed by something already known
   */
  private static boolean isConnected(final List<Edge> edges, final BitSet bound) {
    return edges.stream().anyMatch(edge -> bound.get(edge.otherPosition()));
  }

  /**
   * Builds the step for one position: which tests become applicable when it is bound.
   *
   * <p>A join test is applied at the step where its <em>second</em> endpoint is bound, which is the
   * first moment both payloads exist. Under a fixed left-to-right order that is always the
   * constraint-bearing pattern; under a chosen order it can be either end, which is why the tests
   * are gathered here rather than read off the pattern.
   *
   * @param rule the rule
   * @param position the position being bound
   * @param edges that position's join edges
   * @param bound which positions are bound after this step
   * @return the step
   */
  private static Step step(final CompiledRule rule, final int position, final List<Edge> edges,
      final BitSet bound) {
    final List<Edge> applicable = edges.stream()
        .filter(edge -> bound.get(edge.otherPosition()))
        .toList();
    final CompiledPattern pattern = rule.patterns().get(position);
    final int[] distinct = new int[pattern.distinctFrom().length];
    int count = 0;
    for (final int other : pattern.distinctFrom()) {
      if (bound.get(other)) {
        distinct[count++] = other;
      }
    }
    // Same-type inequalities are symmetric, so one bound only ever needs checking from whichever
    // end is bound second. Trimming to the bound side keeps every check meaningful.
    final int[] trimmed = new int[count];
    System.arraycopy(distinct, 0, trimmed, 0, count);
    return new Step(position, applicable, trimmed);
  }

  /**
   * Collects every join edge, indexed by the position it touches, in both directions.
   *
   * @param rule the rule
   * @return per position, the edges that touch it
   */
  private static List<List<Edge>> edges(final CompiledRule rule) {
    final List<List<Edge>> byPosition = new ArrayList<>(rule.patterns().size());
    for (int position = 0; position < rule.patterns().size(); position++) {
      byPosition.add(new ArrayList<>());
    }
    for (int position = 0; position < rule.patterns().size(); position++) {
      for (final JoinTest test : rule.patterns().get(position).joinTests()) {
        // The edge is recorded from both ends, because either may be bound first.
        byPosition.get(position).add(new Edge(test, position, test.otherIndex(), true));
        byPosition.get(test.otherIndex()).add(new Edge(test, position, test.otherIndex(), false));
      }
    }
    return byPosition;
  }

  /**
   * One binding step.
   *
   * @param position the pattern to bind
   * @param edges the join edges that become applicable here, because their other end is now bound
   * @param distinctFrom the already-bound positions this pattern's fact must differ from
   */
  public record Step(int position, List<Edge> edges, int[] distinctFrom) {

    /**
     * Canonical constructor. Defensively copies both components.
     *
     * @param position the pattern to bind
     * @param edges the applicable join edges
     * @param distinctFrom the applicable inequalities
     */
    public Step {
      edges = List.copyOf(edges);
      distinctFrom = distinctFrom.clone();
    }

    /**
     * The already-bound positions this pattern's fact must differ from.
     *
     * @return a copy of the positions
     */
    @Override
    public int[] distinctFrom() {
      return distinctFrom.clone();
    }

    /**
     * Whether binding a candidate here would violate §1's implicit inequality.
     *
     * @param bound the handles bound so far
     * @param candidateHandleId the handle being considered
     * @return true when the candidate is already bound to an alias this one must differ from
     */
    public boolean conflicts(final long[] bound, final long candidateHandleId) {
      for (final int other : distinctFrom) {
        if (bound[other] == candidateHandleId) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * One join constraint, tagged with which end is being bound.
   *
   * @param test the compiled cross-fact test
   * @param constraintPosition the pattern the constraint is written on
   * @param referencedPosition the pattern the constraint references
   * @param bindingConstraintSide whether the pattern being bound is the one the constraint is
   *     written on. That decides which way round the two payloads go, and whether a probe uses the
   *     operator as written or reversed
   */
  public record Edge(JoinTest test, int constraintPosition, int referencedPosition,
      boolean bindingConstraintSide) {

    /**
     * The position at the other end of this edge from the one being bound.
     *
     * @return the other endpoint
     */
    public int otherPosition() {
      return bindingConstraintSide ? referencedPosition : constraintPosition;
    }

    /**
     * Evaluates the join.
     *
     * <p>No operator inversion is needed to <em>evaluate</em> -- only to probe. The test reads
     * "constraint-side value {@code op} referenced-side value", so evaluating from either end is a
     * matter of passing the two payloads the right way round.
     *
     * @param candidate the payload of the fact being considered
     * @param other the payload of the already-bound fact at the other end
     * @return whether the join holds
     */
    public boolean holds(final com.fasterxml.jackson.databind.JsonNode candidate,
        final com.fasterxml.jackson.databind.JsonNode other) {
      return bindingConstraintSide ? test.test(candidate, other) : test.test(other, candidate);
    }

    /**
     * The path on the pattern being bound -- the one an index would be built on.
     *
     * @return the compiled path
     */
    public com.fasterxml.jackson.core.JsonPointer probePath() {
      return bindingConstraintSide ? test.path() : test.otherAccessor().pointer();
    }

    /**
     * The value to probe for, read from the already-bound fact.
     *
     * @param other the payload of the fact at the other end
     * @return the value
     */
    public com.fasterxml.jackson.databind.JsonNode probeValue(
        final com.fasterxml.jackson.databind.JsonNode other) {
      return bindingConstraintSide
          ? test.otherAccessor().get(other)
          : test.accessor().get(other);
    }

    /**
     * The operator to probe with, read from the side being bound.
     *
     * @return the operator, or empty when the relation cannot be read backwards and this end
     *     therefore cannot be probed
     */
    public java.util.Optional<com.codeheadsystems.rules.rule.Operator> probeOperator() {
      return bindingConstraintSide
          ? java.util.Optional.of(test.source().op())
          : test.source().op().reversed();
    }
  }

  /**
   * The set of positions a plan binds, for assertions that every pattern is covered exactly once.
   *
   * @return the positions, in binding order
   */
  public Set<Integer> positions() {
    final Set<Integer> positions = new LinkedHashSet<>();
    steps.forEach(step -> positions.add(step.position()));
    return positions;
  }
}
