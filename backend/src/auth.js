'use strict';

// Pure/injectable helpers so authentication logic is unit-testable without a
// real Firebase project or network access. server.js wires the real
// firebase-admin Auth instance in; tests inject a fake one.

function extractBearerToken(authorizationHeader) {
  if (!authorizationHeader) return null;
  const match = /^Bearer\s+(.+)$/i.exec(authorizationHeader.trim());
  return match ? match[1].trim() : null;
}

// Wraps firebaseAuth.verifyIdToken() so callers get a small, predictable
// { uid } result or a thrown Error, regardless of which Admin SDK version
// (or fake) is behind it.
function createTokenVerifier(firebaseAuth) {
  return async function verifyIdToken(token) {
    if (!token) {
      throw new Error('missing token');
    }
    const decoded = await firebaseAuth.verifyIdToken(token);
    if (!decoded || !decoded.uid) {
      throw new Error('token missing uid');
    }
    return { uid: decoded.uid };
  };
}

module.exports = { extractBearerToken, createTokenVerifier };
