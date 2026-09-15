'use strict';

const http = require('http');
const { URL } = require('url');
const { WebSocketServer } = require('ws');
const { initializeApp } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { SpeechClient } = require('@google-cloud/speech').v2;
const SpeechSession = require('./speechSession');
const SessionLimiter = require('./sessionLimiter');
const { extractBearerToken, createTokenVerifier } = require('./auth');
const config = require('./config');

// On Cloud Run this picks up the attached service account automatically via
// the metadata server (same identity used for Speech-to-Text) — no key file.
const firebaseApp = initializeApp({ projectId: config.firebaseProjectId });
const verifyIdToken = createTokenVerifier(getAuth(firebaseApp));

const speechClient = new SpeechClient({
  apiEndpoint: `${config.speechLocation}-speech.googleapis.com`,
});

const sessionLimiter = new SessionLimiter({
  maxConcurrentPerUid: config.maxConcurrentSessionsPerUid,
  maxConnectsPerWindow: config.maxConnectsPerWindow,
  windowMs: config.rateLimitWindowMs,
});

const httpServer = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('ok');
});

// noServer + a manual 'upgrade' handler (rather than the `path`/`server`
// shorthand) so an unauthenticated or rate-limited client is rejected
// during the HTTP upgrade itself — before a WebSocket, let alone a Speech
// session, is ever created for it.
const wss = new WebSocketServer({ noServer: true });

function rejectUpgrade(socket, statusCode, statusText) {
  socket.write(`HTTP/1.1 ${statusCode} ${statusText}\r\n\r\n`);
  socket.destroy();
}

httpServer.on('upgrade', async (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname !== '/stream') {
    rejectUpgrade(socket, 404, 'Not Found');
    return;
  }

  const token = extractBearerToken(req.headers['authorization']);
  if (!token) {
    rejectUpgrade(socket, 401, 'Unauthorized');
    return;
  }

  let uid;
  try {
    ({ uid } = await verifyIdToken(token));
  } catch (err) {
    console.error('token verification failed:', err.message || err);
    rejectUpgrade(socket, 401, 'Unauthorized');
    return;
  }

  const { allowed, reason } = sessionLimiter.tryAcquire(uid);
  if (!allowed) {
    console.warn(`connection rejected for uid=${uid}: ${reason}`);
    rejectUpgrade(socket, 429, 'Too Many Requests');
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.uid = uid;
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  console.log(`client connected uid=${ws.uid}`);

  const session = new SpeechSession({
    speechClient,
    projectId: config.projectId,
    location: config.speechLocation,
    model: config.speechModel,
    sampleRateHertz: config.sampleRateHertz,
    audioChannelCount: config.audioChannelCount,
    audioEncoding: config.audioEncoding,
    restartMs: config.streamRestartMs,
  });

  const send = (payload) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
  };

  session.on('partial', ({ text, languageCode }) => send({ type: 'partial', text, languageCode }));
  session.on('final', ({ text, languageCode }) => send({ type: 'final', text, languageCode }));
  session.on('streamError', (err) => console.error('speech stream error:', err.message || err));
  session.on('fatal', (err) => {
    console.error('speech session fatal error:', err.message || err);
    send({ type: 'error', message: 'speech_session_failed' });
    ws.close();
  });

  session.start();

  // Hard ceiling on session lifetime, independent of the internal 5-minute
  // Speech-to-Text stream restarts, so a forgotten/stuck client can't rack
  // up cost indefinitely.
  const maxDurationTimer = setTimeout(() => {
    console.warn(`closing session uid=${ws.uid}: max session duration exceeded`);
    send({ type: 'error', message: 'session_duration_exceeded' });
    ws.close();
  }, config.maxSessionDurationMs);

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      session.pushAudio(data);
    }
    // Non-binary (text/JSON) control messages are reserved for future use
    // (e.g. renegotiating sample rate); ignored for now.
  });

  const cleanup = () => {
    clearTimeout(maxDurationTimer);
    session.stop();
    sessionLimiter.release(ws.uid);
  };

  ws.on('close', () => {
    console.log(`client disconnected uid=${ws.uid}`);
    cleanup();
  });

  ws.on('error', (err) => {
    console.error('websocket error:', err.message || err);
    cleanup();
  });
});

// Cloud Run requires the ingress container to listen on 0.0.0.0 (not
// 127.0.0.1) on the port given by the PORT env var.
httpServer.listen(config.port, '0.0.0.0', () => {
  console.log(`LiveTranslate backend listening on port ${config.port}`);
});
