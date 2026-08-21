/**
 * Right-hand-side execution: staging, commit, and what actually landed.
 *
 * <p>Spec §4.6: actions within one RHS are applied in declaration order, with propagation deferred
 * to the end. Propagating after each action would let action 2 observe state action 1 created,
 * making a rule's behaviour depend on action ordering in ways invisible in the rule file. Deferred
 * commit gives every action one consistent view and matches the author's mental model: the rule
 * fires, then the world changes.
 *
 * <p>The cost is that an action cannot read the result of an earlier action in the same RHS. That
 * is the right limitation -- needing it means the rule should be two rules.
 */
package com.codeheadsystems.rules.rhs;
