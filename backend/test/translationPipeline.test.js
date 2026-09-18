'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const TranslationPipeline = require('../src/translationPipeline');

// A fake translationClient whose translateText() resolves on demand, so
// tests can control exactly when an in-flight translation completes
// (needed to exercise the stale-result/sequence-guard behavior). Calls are
// resolved/rejected by index (not FIFO), since a test may need to settle a
// later call before an earlier still-pending one.
function makeFakeTranslationClient() {
  const calls = [];
  const pending = [];
  return {
    calls,
    translateText(text, sourceLanguage, targetLanguage) {
      return new Promise((resolve, reject) => {
        calls.push({ text, sourceLanguage, targetLanguage });
        pending.push({ resolve, reject });
      });
    },
    resolveNext(translatedText) {
      pending.shift().resolve(translatedText);
    },
    rejectNext(err) {
      pending.shift().reject(err);
    },
    resolveCall(index, translatedText) {
      pending[index].resolve(translatedText);
    },
    rejectCall(index, err) {
      pending[index].reject(err);
    },
  };
}

// Latency-diagnostic fields (sttReceivedAtMs/translateStartedAtMs/
// translateFinishedAtMs/speechBeginAtMs/speechEndAtMs) are real wall-clock
// timestamps (or null, when the test didn't pass them), not deterministic
// under mocked timers — strip them before deepEqual and check separately
// where their presence/shape matters.
function stripTiming({ sttReceivedAtMs, translateStartedAtMs, translateFinishedAtMs, speechBeginAtMs, speechEndAtMs, ...rest }) {
  return rest;
}

function makePipeline(overrides = {}) {
  const translationClient = makeFakeTranslationClient();
  const pipeline = new TranslationPipeline({
    translationClient,
    targetLanguage: 'he',
    partialDebounceMs: 400,
    ...overrides,
  });
  return { pipeline, translationClient };
}

test('a final is translated immediately, with no debounce wait', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();

  const events = [];
  pipeline.on('translated', (e) => events.push(e));

  pipeline.handleTranscript('hello there', 'en-US', true);
  assert.equal(translationClient.calls.length, 1);
  translationClient.resolveNext('שלום');

  return Promise.resolve().then(() => {
    assert.equal(typeof events[0].translateFinishedAtMs, 'number');
    assert.deepEqual(events.map(stripTiming), [
      { text: 'hello there', translatedText: 'שלום', sourceLanguage: 'en-US', targetLanguage: 'he', isFinal: true, seq: 1 },
    ]);
  });
});

test('a partial waits for the debounce before translating', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('hello there', 'en-US', false);
  assert.equal(translationClient.calls.length, 0);

  t.mock.timers.tick(399);
  assert.equal(translationClient.calls.length, 0);

  t.mock.timers.tick(1);
  assert.equal(translationClient.calls.length, 1);
});

test('a new partial resets the debounce timer for the previous one', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('hello', 'en-US', false);
  t.mock.timers.tick(300);
  pipeline.handleTranscript('hello there', 'en-US', false);
  t.mock.timers.tick(300);
  assert.equal(translationClient.calls.length, 0); // only 300ms since the second partial

  t.mock.timers.tick(100);
  assert.equal(translationClient.calls.length, 1);
  assert.equal(translationClient.calls[0].text, 'hello there');
});

test('a partial shorter than the minimum word count is never translated', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('hi', 'en-US', false);
  t.mock.timers.tick(10000);
  assert.equal(translationClient.calls.length, 0);
});

test('source language equal to target language is passed through untranslated', () => {
  const { pipeline, translationClient } = makePipeline();
  const events = [];
  pipeline.on('translated', (e) => events.push(e));

  pipeline.handleTranscript('שלום עולם', 'he-IL', true);

  assert.equal(translationClient.calls.length, 0);
  assert.deepEqual(events.map(stripTiming), [
    { text: 'שלום עולם', translatedText: 'שלום עולם', sourceLanguage: 'he-IL', targetLanguage: 'he', isFinal: true, seq: 1 },
  ]);
});

test('a stale in-flight translation cannot overwrite a newer result', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();
  const events = [];
  pipeline.on('translated', (e) => events.push(e));

  // A partial starts translating (call #1) ...
  pipeline.handleTranscript('hello there my', 'en-US', false);
  t.mock.timers.tick(400);
  assert.equal(translationClient.calls.length, 1);

  // ... but before it resolves, a final for the same utterance arrives and
  // translates immediately (call #2), and resolves first.
  pipeline.handleTranscript('hello there my friend', 'en-US', true);
  assert.equal(translationClient.calls.length, 2);
  translationClient.resolveCall(1, 'שלום חבר'); // the final's call (index 1)
  await Promise.resolve();

  assert.deepEqual(events.map(stripTiming), [
    { text: 'hello there my friend', translatedText: 'שלום חבר', sourceLanguage: 'en-US', targetLanguage: 'he', isFinal: true, seq: 2 },
  ]);

  // Now the stale partial's translation (call #1, index 0) finally
  // resolves — it must not produce a second (out-of-order) 'translated'.
  translationClient.resolveCall(0, 'שלום שם');
  await Promise.resolve();
  assert.equal(events.length, 1);
});

test('translation failure emits translationError with the original text', async () => {
  const { pipeline, translationClient } = makePipeline();
  const errors = [];
  pipeline.on('translationError', (e) => errors.push(e));

  pipeline.handleTranscript('hello there', 'en-US', true);
  translationClient.rejectNext(new Error('quota exceeded'));
  await Promise.resolve();

  assert.equal(errors.length, 1);
  assert.equal(errors[0].text, 'hello there');
  assert.equal(errors[0].sourceLanguage, 'en-US');
  assert.equal(errors[0].isFinal, true);
});

test('speechBeginAtMs/speechEndAtMs pass straight through to the translated event', async () => {
  const { pipeline, translationClient } = makePipeline();
  const events = [];
  pipeline.on('translated', (e) => events.push(e));

  pipeline.handleTranscript('hello there', 'en-US', true, 12345, { speechBeginAtMs: 100, speechEndAtMs: 200 });
  translationClient.resolveNext('שלום');
  await Promise.resolve();

  assert.equal(events.length, 1);
  assert.equal(events[0].speechBeginAtMs, 100);
  assert.equal(events[0].speechEndAtMs, 200);
});

test('missing extraTiming defaults speechBeginAtMs/speechEndAtMs to null', async () => {
  const { pipeline, translationClient } = makePipeline();
  const events = [];
  pipeline.on('translated', (e) => events.push(e));

  pipeline.handleTranscript('hello there', 'en-US', true);
  translationClient.resolveNext('שלום');
  await Promise.resolve();

  assert.equal(events[0].speechBeginAtMs, null);
  assert.equal(events[0].speechEndAtMs, null);
});

test('a partial without a language code reuses the last known one', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('hello there', 'en-US', false);
  t.mock.timers.tick(400);
  assert.equal(translationClient.calls[0].sourceLanguage, 'en-US');

  pipeline.handleTranscript('hello there my friend', null, false);
  t.mock.timers.tick(400);
  assert.equal(translationClient.calls[1].sourceLanguage, 'en-US');
});

test('und is never stored as the stable language and never sent to Cloud Translation', () => {
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('hello there', 'en-US', true);
  assert.equal(translationClient.calls[0].sourceLanguage, 'en-US');

  // A later 'und' result must fall back to the already-established stable
  // language, not be forwarded as-is.
  pipeline.handleTranscript('77000', 'und', true);
  assert.equal(translationClient.calls[1].sourceLanguage, 'en-US');
});

test('the very first result is und: falls through with no source language (Translation auto-detects) instead of erroring', () => {
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('77000', 'und', true);
  assert.equal(translationClient.calls.length, 1);
  // No stable language established yet — falsy (not the literal 'und'), so
  // translationClient.translateText's caller omits `from` and Cloud
  // Translation auto-detects instead of erroring on an invalid source.
  assert.equal(translationClient.calls[0].sourceLanguage, null);
});

test('a one-off flapped languageCode surrounded by a consistent majority is suppressed', () => {
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('birinci', 'tr', true);
  pipeline.handleTranscript('ikinci', 'tr', true);
  pipeline.handleTranscript('ucuncu', 'tr', true);
  pipeline.handleTranscript('dorduncu', 'tr', true);
  // A single flapped result in an otherwise all-Turkish run.
  pipeline.handleTranscript('one word', 'ko', true);

  assert.deepEqual(translationClient.calls.map((c) => c.sourceLanguage), ['tr', 'tr', 'tr', 'tr', 'tr']);
});

test('a genuine, sustained language switch (multi-language video) still takes over the stable language', () => {
  const { pipeline, translationClient } = makePipeline({ languageHistorySize: 5 });

  pipeline.handleTranscript('a', 'tr', true);
  pipeline.handleTranscript('b', 'tr', true);
  pipeline.handleTranscript('c', 'tr', true);
  pipeline.handleTranscript('d', 'tr', true);
  // Three consecutive, consistent Korean results after that.
  pipeline.handleTranscript('e', 'ko', true);
  pipeline.handleTranscript('f', 'ko', true);
  pipeline.handleTranscript('g', 'ko', true);

  assert.deepEqual(translationClient.calls.map((c) => c.sourceLanguage), [
    'tr', 'tr', 'tr', 'tr',
    'tr', // 1st ko: window is now [tr,tr,tr,tr,ko] — tr still an outright majority (4 vs 1), suppressed
    'tr', // 2nd ko: window is now [tr,tr,tr,ko,ko] — tr still an outright majority (3 vs 2), suppressed
    'ko', // 3rd ko: window is now [tr,tr,ko,ko,ko] — ko now has the outright majority (3 vs 2)
  ]);
});

test('stop() cancels a pending debounced partial', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { pipeline, translationClient } = makePipeline();

  pipeline.handleTranscript('hello there', 'en-US', false);
  pipeline.stop();
  t.mock.timers.tick(10000);

  assert.equal(translationClient.calls.length, 0);
});
