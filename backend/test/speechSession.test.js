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
  // Endpointing config: defaults to the faster-than-STANDARD SHORT
  // sensitivity with a 1s silence timeout, so Chirp 3 doesn't sit on a
  // finalization for seconds like its own STANDARD default does.
  assert.equal(initial.streamingConfig.streamingFeatures.endpointingSensitivity, 'ENDPOINTING_SENSITIVITY_SHORT');
  assert.equal(initial.streamingConfig.streamingFeatures.enableVoiceActivityEvents, true);
  assert.deepEqual(initial.streamingConfig.streamingFeatures.voiceActivityTimeout.speechEndTimeout, { seconds: 1, nanos: 0 });
});

test('endpointing settings are configurable per session', () => {
  const { session, streams } = makeSession({
    endpointingSensitivity: 'ENDPOINTING_SENSITIVITY_SUPERSHORT',
    speechEndTimeoutSeconds: 0.5,
  });
  session.start();

  const [initial] = streams[0].written;
  assert.equal(initial.streamingConfig.streamingFeatures.endpointingSensitivity, 'ENDPOINTING_SENSITIVITY_SUPERSHORT');
  assert.deepEqual(initial.streamingConfig.streamingFeatures.voiceActivityTimeout.speechEndTimeout, { seconds: 0, nanos: 500000000 });
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

  for (const e of events) {
    assert.equal(typeof e.sttReceivedAtMs, 'number');
    // No voice-activity events or word offsets were simulated for this test.
    assert.equal(e.speechBeginAtMs, null);
    assert.equal(e.speechEndAtMs, null);
    assert.equal(e.wordsStartAtMs, null);
    assert.equal(e.wordsEndAtMs, null);
  }
  assert.deepEqual(events.map(({ sttReceivedAtMs, speechBeginAtMs, speechEndAtMs, wordsStartAtMs, wordsEndAtMs, ...rest }) => rest), [
    { kind: 'partial', text: 'hel', languageCode: 'en-US' },
    { kind: 'final', text: 'hello', languageCode: 'en-US' },
  ]);
});

test('SPEECH_ACTIVITY_BEGIN/END attach speechBeginAtMs/speechEndAtMs to the utterance that follows, and clear after the final', () => {
  const { session, streams } = makeSession();
  session.start();

  const events = [];
  session.on('partial', (e) => events.push({ kind: 'partial', ...e }));
  session.on('final', (e) => events.push({ kind: 'final', ...e }));

  streams[0].emit('data', { speechEventType: 'SPEECH_ACTIVITY_BEGIN', results: [] });
  streams[0].emit('data', {
    results: [{ isFinal: false, languageCode: 'en-US', alternatives: [{ transcript: 'hel' }] }],
  });
  streams[0].emit('data', { speechEventType: 'SPEECH_ACTIVITY_END', results: [] });
  streams[0].emit('data', {
    results: [{ isFinal: true, languageCode: 'en-US', alternatives: [{ transcript: 'hello' }] }],
  });
  // A later, unrelated utterance with no new BEGIN event yet — must not
  // inherit the previous utterance's begin/end.
  streams[0].emit('data', {
    results: [{ isFinal: false, languageCode: 'en-US', alternatives: [{ transcript: 'world' }] }],
  });

  assert.equal(events.length, 3);
  const [partial, final, laterPartial] = events;

  assert.equal(typeof partial.speechBeginAtMs, 'number');
  assert.equal(partial.speechEndAtMs, null); // END hasn't happened yet when this partial arrived

  assert.equal(final.speechBeginAtMs, partial.speechBeginAtMs); // same utterance, same begin time
  assert.equal(typeof final.speechEndAtMs, 'number');

  assert.equal(laterPartial.speechBeginAtMs, null);
  assert.equal(laterPartial.speechEndAtMs, null);
});

test('word time offsets (if present) are converted to wall-clock ms relative to stream start', () => {
  const { session, streams } = makeSession();
  const beforeStart = Date.now();
  session.start();
  const afterStart = Date.now();

  const events = [];
  session.on('final', (e) => events.push(e));

  streams[0].emit('data', {
    results: [{
      isFinal: true,
      languageCode: 'en-US',
      alternatives: [{
        transcript: 'hello there',
        words: [
          { word: 'hello', startOffset: { seconds: 1, nanos: 0 }, endOffset: { seconds: 1, nanos: 500000000 } },
          { word: 'there', startOffset: { seconds: 1, nanos: 500000000 }, endOffset: { seconds: 2, nanos: 0 } },
        ],
      }],
    }],
  });

  assert.equal(events.length, 1);
  const [e] = events;
  assert.equal(typeof e.wordsStartAtMs, 'number');
  assert.equal(typeof e.wordsEndAtMs, 'number');
  // wordsStartAtMs = stream-open wall-clock time + the first word's
  // startOffset (1000ms); wordsEndAtMs = + the last word's endOffset (2000ms).
  assert.ok(e.wordsStartAtMs >= beforeStart + 1000 && e.wordsStartAtMs <= afterStart + 1050);
  assert.ok(e.wordsEndAtMs >= beforeStart + 2000 && e.wordsEndAtMs <= afterStart + 2050);
  assert.ok(e.wordsEndAtMs > e.wordsStartAtMs);
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

test('audio already sent to a stream that dies before finalizing is replayed into the new stream, before audio queued during the gap', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { session, streams } = makeSession({ restartMs: 100000 });
  session.start();

  // Sent to stream #1, no 'final' ever arrives for it before it dies.
  session.pushAudio(Buffer.from([1]));
  session.pushAudio(Buffer.from([2]));
  streams[0].emit('error', new Error('boom'));
  t.mock.timers.tick(500); // reconnect backoff

  // Arrives during the gap (currentStream is null) — queued via pendingAudio.
  session.pushAudio(Buffer.from([3]));

  assert.equal(streams.length, 2);
  const audioBytes = streams[1].written.filter((w) => w.audio).map((w) => w.audio[0]);
  // Replayed (already-sent, unconfirmed) audio first, in order, then
  // whatever queued up after the stream died — nothing duplicated, nothing
  // dropped.
  assert.deepEqual(audioBytes, [1, 2, 3]);
});

test('a confirmed final clears the replay buffer, so already-transcribed audio is never replayed', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { session, streams } = makeSession({ restartMs: 100000 });
  session.start();

  session.pushAudio(Buffer.from([1]));
  streams[0].emit('data', {
    results: [{ isFinal: true, languageCode: 'en-US', alternatives: [{ transcript: 'one' }] }],
  });

  // Sent after the final — this is the only audio still "at risk".
  session.pushAudio(Buffer.from([2]));
  streams[0].emit('error', new Error('boom'));
  t.mock.timers.tick(500);

  const audioBytes = streams[1].written.filter((w) => w.audio).map((w) => w.audio[0]);
  assert.deepEqual(audioBytes, [2]); // [1] was already safely transcribed, must not come back
});

test('a stream with no audio sent yet (e.g. dies immediately) triggers no replay', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { session, streams } = makeSession({ restartMs: 100000 });
  session.start();

  streams[0].emit('error', new Error('boom'));
  t.mock.timers.tick(500);

  assert.equal(streams.length, 2);
  const audioBytes = streams[1].written.filter((w) => w.audio);
  assert.deepEqual(audioBytes, []);
});

test('stop() ends the stream and further pushAudio is a no-op', () => {
  const { session, streams } = makeSession();
  session.start();
  session.stop();

  assert.equal(streams[0].ended, true);
  session.pushAudio(Buffer.from([1]));
  assert.equal(streams.length, 1);
});
