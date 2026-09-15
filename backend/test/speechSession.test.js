'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const EventEmitter = require('events');
const SpeechSession = require('../src/speechSession');

class FakeStream extends EventEmitter {
  constructor() {
    super();
    this.written = [];
    this.ended = false;
  }
  write(msg) {
    this.written.push(msg);
  }
  end() {
    this.ended = true;
  }
}

function makeClient(streams) {
  return {
    _streamingRecognize() {
      const stream = new FakeStream();
      streams.push(stream);
      return stream;
    },
  };
}

function makeSession(overrides = {}) {
  const streams = [];
  const client = makeClient(streams);
  const session = new SpeechSession({
    speechClient: client,
    projectId: 'proj',
    location: 'us-central1',
    model: 'chirp_3',
    sampleRateHertz: 16000,
    audioChannelCount: 1,
    audioEncoding: 'LINEAR16',
    restartMs: 1000,
    ...overrides,
  });
  return { session, streams };
}

test('start() opens a stream and sends the correct initial config', () => {
  const { session, streams } = makeSession();
  session.start();

  assert.equal(streams.length, 1);
  const [initial] = streams[0].written;
  assert.equal(initial.recognizer, 'projects/proj/locations/us-central1/recognizers/_');
  assert.equal(initial.streamingConfig.config.model, 'chirp_3');
  assert.deepEqual(initial.streamingConfig.config.languageCodes, ['auto']);
  assert.equal(initial.streamingConfig.config.explicitDecodingConfig.sampleRateHertz, 16000);
  assert.equal(initial.streamingConfig.streamingFeatures.interimResults, true);
});

test('pushAudio writes audio chunks to the active stream', () => {
  const { session, streams } = makeSession();
  session.start();

  const chunk = Buffer.from([1, 2, 3]);
  session.pushAudio(chunk);

  const stream = streams[0];
  assert.equal(stream.written.length, 2); // initial config + audio
  assert.equal(stream.written[1].audio, chunk);
});

test('pushAudio before start() queues audio and flushes once the stream opens', () => {
  const { session, streams } = makeSession();
  const chunk = Buffer.from([9, 9]);

  session.pushAudio(chunk);
  assert.equal(streams.length, 0);

  session.start();
  const stream = streams[0];
  assert.equal(stream.written[1].audio, chunk);
});

test('emits "partial" and "final" events from streaming responses', () => {
  const { session, streams } = makeSession();
  session.start();

  const events = [];
  session.on('partial', (e) => events.push({ kind: 'partial', ...e }));
  session.on('final', (e) => events.push({ kind: 'final', ...e }));

  streams[0].emit('data', {
    results: [{ isFinal: false, languageCode: 'en-US', alternatives: [{ transcript: 'hel' }] }],
  });
  streams[0].emit('data', {
    results: [{ isFinal: true, languageCode: 'en-US', alternatives: [{ transcript: 'hello' }] }],
  });

  assert.deepEqual(events, [
    { kind: 'partial', text: 'hel', languageCode: 'en-US' },
    { kind: 'final', text: 'hello', languageCode: 'en-US' },
  ]);
});

test('restarts the stream before the restart interval elapses, without losing audio', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { session, streams } = makeSession({ restartMs: 1000 });
  session.start();

  t.mock.timers.tick(1000);

  assert.equal(streams.length, 2);
  assert.equal(streams[0].ended, true);

  // Audio pushed after the restart goes to the new stream.
  session.pushAudio(Buffer.from([7]));
  const lastWrite = streams[1].written[streams[1].written.length - 1];
  assert.equal(lastWrite.audio.toString(), Buffer.from([7]).toString());
});

test('reconnects with backoff after a stream error, and gives up after too many attempts', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { session, streams } = makeSession({ restartMs: 100000 });
  session.start();

  let fatal = null;
  session.on('fatal', (err) => { fatal = err; });

  for (let i = 0; i < 5; i++) {
    streams[streams.length - 1].emit('error', new Error('boom'));
    t.mock.timers.tick(5000);
  }
  assert.equal(fatal, null);
  assert.equal(streams.length, 6); // initial + 5 reconnects

  streams[streams.length - 1].emit('error', new Error('boom'));
  assert.ok(fatal instanceof Error);
});

test('stop() ends the stream and further pushAudio is a no-op', () => {
  const { session, streams } = makeSession();
  session.start();
  session.stop();

  assert.equal(streams[0].ended, true);
  session.pushAudio(Buffer.from([1]));
  assert.equal(streams.length, 1);
});
