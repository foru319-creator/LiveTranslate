'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const SessionLimiter = require('../src/sessionLimiter');

function makeLimiter(overrides = {}) {
  return new SessionLimiter({
    maxConcurrentPerUid: 1,
    maxConnectsPerWindow: 3,
    windowMs: 60000,
    ...overrides,
  });
}

test('allows a session for a fresh uid', () => {
  const limiter = makeLimiter();
  assert.deepEqual(limiter.tryAcquire('u1'), { allowed: true });
});

test('blocks a second concurrent session for the same uid', () => {
  const limiter = makeLimiter({ maxConcurrentPerUid: 1 });
  assert.equal(limiter.tryAcquire('u1').allowed, true);
  const second = limiter.tryAcquire('u1');
  assert.equal(second.allowed, false);
  assert.equal(second.reason, 'too_many_concurrent_sessions');
});

test('a different uid is unaffected by another uid being at its concurrency cap', () => {
  const limiter = makeLimiter({ maxConcurrentPerUid: 1 });
  limiter.tryAcquire('u1');
  assert.equal(limiter.tryAcquire('u2').allowed, true);
});

test('release() frees up a concurrency slot', () => {
  const limiter = makeLimiter({ maxConcurrentPerUid: 1 });
  limiter.tryAcquire('u1');
  assert.equal(limiter.tryAcquire('u1').allowed, false);
  limiter.release('u1');
  assert.equal(limiter.tryAcquire('u1').allowed, true);
});

test('rate limits repeated connections within the window even with concurrency freed each time', () => {
  const limiter = makeLimiter({ maxConcurrentPerUid: 1, maxConnectsPerWindow: 3, windowMs: 60000 });
  const now = 1_000_000;

  for (let i = 0; i < 3; i++) {
    const result = limiter.tryAcquire('u1', now + i);
    assert.equal(result.allowed, true, `attempt ${i} should be allowed`);
    limiter.release('u1');
  }

  const fourth = limiter.tryAcquire('u1', now + 3);
  assert.equal(fourth.allowed, false);
  assert.equal(fourth.reason, 'rate_limited');
});

test('rate limit window slides: old attempts age out', () => {
  const limiter = makeLimiter({ maxConcurrentPerUid: 1, maxConnectsPerWindow: 2, windowMs: 1000 });
  const now = 1_000_000;

  limiter.tryAcquire('u1', now);
  limiter.release('u1');
  limiter.tryAcquire('u1', now + 100);
  limiter.release('u1');

  // Third attempt inside the window is blocked...
  assert.equal(limiter.tryAcquire('u1', now + 200).allowed, false);

  // ...but once the window has fully elapsed, older attempts age out.
  assert.equal(limiter.tryAcquire('u1', now + 1300).allowed, true);
});
