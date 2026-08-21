/**
 * Fixtures, the correctness oracle, and the determinism harness.
 *
 * <p>Spec section 8 is explicit that this module is not optional, and gives three reasons: Phase 0's
 * naive matcher is the correctness oracle for every later phase, the chosen update semantics have
 * oracle-equivalence as a Phase 1 exit criterion, and the determinism contract needs shuffle tests.
 * All three are things consumers want too -- a rule author testing their own rule set wants the same
 * fixtures and the same oracle. Left in another module's test sources they would be unusable from
 * outside and would quietly rot.
 */
package com.codeheadsystems.rules.testkit;
