'use strict';

function required(name, fallback) {
  const value = process.env[name];
  if (value !== undefined && value !== '') return value;
  if (fallback !== undefined) return fallback;
  throw new Error(`Missing required environment variable: ${name}`);
}

const config = {
  port: parseInt(process.env.PORT || '8080', 10),

  projectId: required('GOOGLE_CLOUD_PROJECT', 'livetranslate-508621'),
  // Chirp 3 is only GA in the "us" and "eu" multi-region locations (not
  // per-region locations like "us-central1") as of the current Speech-to-Text
  // docs. This is independent of which Cloud Run region the service itself
  // runs in.
  speechLocation: process.env.SPEECH_LOCATION || 'us',
  speechModel: process.env.SPEECH_MODEL || 'chirp_3',

  sampleRateHertz: parseInt(process.env.SAMPLE_RATE_HZ || '16000', 10),
  audioChannelCount: 1,
  audioEncoding: 'LINEAR16',

  // Google Cloud Speech-to-Text streaming sessions are capped at 5 minutes
  // (300s) of audio per stream. Restart with a fresh stream well before that
  // limit so the swap is invisible to the client instead of racing the
  // server-side cutoff.
  streamRestartMs: parseInt(process.env.STREAM_RESTART_MS || String(4 * 60 * 1000 + 45 * 1000), 10),

  // Firebase project used to verify ID tokens presented by the Android app.
  // Defaults to the same GCP project (Firebase is added on top of it), so no
  // separate project ID normally needs to be configured.
  firebaseProjectId: process.env.FIREBASE_PROJECT_ID || process.env.GOOGLE_CLOUD_PROJECT || 'livetranslate-508621',

  // Cost/abuse controls: how many concurrent sessions one authenticated user
  // may hold, how often they may open new ones, and a hard ceiling on how
  // long any single session is allowed to run regardless of activity.
  maxConcurrentSessionsPerUid: parseInt(process.env.MAX_CONCURRENT_SESSIONS_PER_UID || '1', 10),
  maxConnectsPerWindow: parseInt(process.env.MAX_CONNECTS_PER_WINDOW || '10', 10),
  rateLimitWindowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS || String(5 * 60 * 1000), 10),
  maxSessionDurationMs: parseInt(process.env.MAX_SESSION_DURATION_MS || String(30 * 60 * 1000), 10),
};

module.exports = config;
