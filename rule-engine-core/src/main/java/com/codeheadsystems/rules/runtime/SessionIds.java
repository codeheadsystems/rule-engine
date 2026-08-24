package com.codeheadsystems.rules.runtime;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates the one UUIDv7 a session carries (spec §2.1, RFC 9562 §5.7).
 *
 * <p>One per <em>session</em>, not one per fact: the pair {@code (sessionId, handle.id())} is
 * globally unique, sorts by session creation time, and costs 16 bytes per session rather than per
 * fact. Generating it is a session-construction cost, not a hot-path one.
 *
 * <p><strong>This class is a stopgap with a known expiry.</strong> JDK 25 has no native v7
 * generator; {@code UUID.ofEpochMillis(long)} lands in JDK 26 (JDK-8357251). When the build moves
 * to 26, delete this and call that. The alternative today is a third-party dependency for fifteen
 * lines on a path that runs once per session, which is not a trade worth making.
 *
 * <p>Non-monotonic v7: two sessions created in the same millisecond may sort arbitrarily relative
 * to each other. Nothing in the engine depends on inter-session ordering.
 *
 * <p><strong>Package-private, and in {@code runtime} rather than {@code session}, because of that
 * expiry.</strong> It was public in an exported package purely so a sibling could call it, which is
 * the thing §8.1 is about -- and a stopgap with a scheduled deletion is the worst possible candidate
 * for a published surface: removing it after 1.0.0 would be a binary-incompatible change, so a
 * fifteen-line helper would have dictated a major version. {@code DefaultRuleSession} is its only
 * caller and now shares its package.
 */
final class SessionIds {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int VERSION_7 = 0x7000;
  private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;

  private SessionIds() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Generates a UUIDv7.
   *
   * @return a time-ordered UUID: 48 bits of Unix epoch milliseconds, then version and variant bits,
   *     then 74 bits of randomness
   */
  public static UUID newSessionId() {
    final long timestamp = System.currentTimeMillis();
    final byte[] entropy = new byte[10];
    RANDOM.nextBytes(entropy);

    long high = (timestamp & 0xFFFF_FFFF_FFFFL) << 16;
    high |= VERSION_7;
    high |= ((entropy[0] & 0xFFL) << 8) | (entropy[1] & 0xFFL);

    long low = 0;
    for (int index = 2; index < entropy.length; index++) {
      low = (low << 8) | (entropy[index] & 0xFFL);
    }
    low &= 0x3FFF_FFFF_FFFF_FFFFL;
    low |= VARIANT_RFC_9562;

    return new UUID(high, low);
  }
}
