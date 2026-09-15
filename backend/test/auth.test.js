'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { extractBearerToken, createTokenVerifier } = require('../src/auth');

test('extractBearerToken parses a well-formed Authorization header', () => {
  assert.equal(extractBearerToken('Bearer abc.def.ghi'), 'abc.def.ghi');
  assert.equal(extractBearerToken('bearer   abc.def.ghi  '), 'abc.def.ghi');
});

test('extractBearerToken returns null for missing or malformed headers', () => {
  assert.equal(extractBearerToken(undefined), null);
  assert.equal(extractBearerToken(''), null);
  assert.equal(extractBearerToken('Basic dXNlcjpwYXNz'), null);
  assert.equal(extractBearerToken('Bearer'), null);
});

test('verifyIdToken resolves with the uid on a valid token', async () => {
  const fakeAuth = { verifyIdToken: async (token) => ({ uid: `uid-for-${token}` }) };
  const verify = createTokenVerifier(fakeAuth);

  const result = await verify('good-token');
  assert.deepEqual(result, { uid: 'uid-for-good-token' });
});

test('verifyIdToken rejects when given no token', async () => {
  const fakeAuth = { verifyIdToken: async () => ({ uid: 'should-not-be-called' }) };
  const verify = createTokenVerifier(fakeAuth);

  await assert.rejects(() => verify(null));
});

test('verifyIdToken propagates errors from the underlying Firebase call', async () => {
  const fakeAuth = { verifyIdToken: async () => { throw new Error('invalid signature'); } };
  const verify = createTokenVerifier(fakeAuth);

  await assert.rejects(() => verify('bad-token'), /invalid signature/);
});
