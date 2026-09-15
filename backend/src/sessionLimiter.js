'use strict';

// In-memory per-user session limiter: caps how many concurrent
// sessions one authenticated Firebase user (uid) can hold open, and how
// often they may open new ones, to bound Speech-to-Text cost/abuse from a
// single account. This state is per Cloud Run instance, not global across
// instances/replicas — good enough as a basic first line of defense, not a
// substitute for a shared store (e.g. Firestore/Redis) if the service is
// ever scaled to multiple concurrent instances.
class SessionLimiter {
  constructor({ maxConcurrentPerUid, maxConnectsPerWindow, windowMs }) {
    this.maxConcurrentPerUid = maxConcurrentPerUid;
    this.maxConnectsPerWindow = maxConnectsPerWindow;
    this.windowMs = windowMs;
    this.activeCountByUid = new Map();
    this.connectTimestampsByUid = new Map();
  }

  // Call when a client with this uid is about to be granted a session.
  // Returns { allowed: true } or { allowed: false, reason }.
  tryAcquire(uid, now = Date.now()) {
    const active = this.activeCountByUid.get(uid) || 0;
    if (active >= this.maxConcurrentPerUid) {
      return { allowed: false, reason: 'too_many_concurrent_sessions' };
    }

    const recent = (this.connectTimestampsByUid.get(uid) || [])
      .filter((t) => now - t < this.windowMs);
    if (recent.length >= this.maxConnectsPerWindow) {
      this.connectTimestampsByUid.set(uid, recent);
      return { allowed: false, reason: 'rate_limited' };
    }

    recent.push(now);
    this.connectTimestampsByUid.set(uid, recent);
    this.activeCountByUid.set(uid, active + 1);
    return { allowed: true };
  }

  // Call exactly once when a session granted by tryAcquire() ends.
  release(uid) {
    const active = this.activeCountByUid.get(uid) || 0;
    if (active <= 1) {
      this.activeCountByUid.delete(uid);
    } else {
      this.activeCountByUid.set(uid, active - 1);
    }
  }
}

module.exports = SessionLimiter;
