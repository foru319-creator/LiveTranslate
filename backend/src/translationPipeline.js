'use strict';

const EventEmitter = require('events');

const DEFAULT_PARTIAL_DEBOUNCE_MS = 400;
const MIN_PARTIAL_WORD_COUNT = 2;
// How many recent valid (non-und) languageCodes feed the majority vote.
// Small on purpose: big enough to smooth a one-off flapped result, small
// enough that a genuine language switch (e.g. a multi-language video) still
// takes over within a handful of utterances instead of being stuck forever
// on whatever language dominated earlier in the session.
const DEFAULT_LANGUAGE_HISTORY_SIZE = 5;

// Turns Chirp 3's raw partial/final transcripts into translated text, one
// instance per WebSocket connection.
//
// - A final is translated immediately and is always authoritative.
// - A partial is debounced (and needs a couple of words) so a fast run of
//   interim updates collapses into one translation call instead of one per
//   tiny fragment — keeping the on-screen text a coherent phrase without
//   adding real latency.
// - Every event gets a sequence number; a translation that resolves late
//   (e.g. a debounced partial finishing after a newer final already went
//   out) is dropped instead of overwriting the newer result — 'translated'
//   and 'translationError' only ever fire in non-decreasing seq order.
// - If source and target language already match, the text is passed
//   through untouched (no wasted API call, no risk of mangling it).
// - Chirp 3 re-runs language auto-ID independently per utterance (see
//   speechSession.js), which flaps on short/ambiguous audio and sometimes
//   returns 'und' outright — which Cloud Translation rejects as a source
//   language. Source language actually sent to Translation is therefore a
//   majority vote over the last few valid (non-und) languageCodes, not the
//   raw per-utterance value — see _resolveSourceLanguage().
class TranslationPipeline extends EventEmitter {
  constructor({
    translationClient,
    targetLanguage = 'he',
    partialDebounceMs = DEFAULT_PARTIAL_DEBOUNCE_MS,
    minPartialWordCount = MIN_PARTIAL_WORD_COUNT,
    languageHistorySize = DEFAULT_LANGUAGE_HISTORY_SIZE,
    label = 'session',
  }) {
    super();
    this.translationClient = translationClient;
    this.targetLanguage = targetLanguage;
    this.partialDebounceMs = partialDebounceMs;
    this.minPartialWordCount = minPartialWordCount;
    this.languageHistorySize = languageHistorySize;
    this.label = label;

    this.sequence = 0;
    this.lastAppliedSeq = -1;
    // Rolling window of recent valid (non-empty, non-und) languageCodes, in
    // arrival order — 'und' is never pushed here (see _resolveSourceLanguage).
    this.languageHistory = [];
    // Majority language over languageHistory — this, not the raw per-result
    // languageCode, is what's actually sent to Cloud Translation whenever the
    // current result is 'und' or disagrees with the session's majority.
    this.stableSourceLanguage = null;
    this.pendingPartialTimer = null;
    this.closed = false;
  }

  // Lets a future UI-selected target language override the default without
  // needing a new pipeline instance.
  setTargetLanguage(targetLanguage) {
    if (targetLanguage) this.targetLanguage = targetLanguage;
  }

  // sttReceivedAtMs (optional): Date.now() when Chirp 3 returned this exact
  // result, forwarded from SpeechSession — latency-diagnostic only, lets
  // every downstream log line below report elapsed time since Chirp 3
  // actually produced the text, not just since we started working on it.
  // extraTiming (optional): { speechBeginAtMs, speechEndAtMs }, also from
  // SpeechSession (voice-activity events) — passed straight through to the
  // emitted 'translated'/'translationError' events untouched, purely so
  // server.js and, from there, the Android client can log latency measured
  // from when the person actually started/stopped talking. Never read or
  // branched on here.
  handleTranscript(text, languageCode, isFinal, sttReceivedAtMs, extraTiming = {}) {
    if (this.closed) return;
    if (!text || !text.trim()) return;
    const trimmed = text.trim();
    const seq = ++this.sequence;
    const receivedAtMs = Date.now();
    const { speechBeginAtMs = null, speechEndAtMs = null } = extraTiming;

    const sourceLanguage = this._resolveSourceLanguage(languageCode, seq);

    this._cancelPendingPartial();

    if (isFinal) {
      this._translateAndEmit(trimmed, sourceLanguage, seq, true, sttReceivedAtMs, receivedAtMs, speechBeginAtMs, speechEndAtMs);
      return;
    }

    if (wordCount(trimmed) < this.minPartialWordCount) {
      this._log(`partial too short to translate yet, waiting seq=${seq}`);
      return;
    }

    this.pendingPartialTimer = setTimeout(() => {
      this.pendingPartialTimer = null;
      this._translateAndEmit(trimmed, sourceLanguage, seq, false, sttReceivedAtMs, receivedAtMs, speechBeginAtMs, speechEndAtMs);
    }, this.partialDebounceMs);
  }

  // Turns Chirp 3's raw per-result languageCode into the source language
  // actually sent to Cloud Translation, stabilized across the session:
  //  - 'und' (or empty) never becomes/updates the stable language, and is
  //    never itself sent to Translation — Cloud Translation errors out on
  //    it ("Invalid Value"), so a single 'und' result would otherwise drop
  //    that utterance's translation outright.
  //  - Any other valid code is added to the rolling languageHistory window
  //    and the majority over that window becomes the new stableSourceLanguage
  //    (see majorityLanguage()) — this is what actually gets returned,
  //    *not* the raw current-result code, so one flapped/misheard result
  //    surrounded by consistent results doesn't get sent to Translation on
  //    its own. If enough *consecutive* results genuinely agree on a
  //    different language (e.g. the source video itself switches language),
  //    the majority — and so the stable language — naturally follows within
  //    languageHistorySize results; nothing here pins it permanently.
  //  - Only STT's own per-utterance detection is used as input; this never
  //    changes what's sent to Chirp 3 (still languageCodes:['auto']).
  _resolveSourceLanguage(languageCode, seq) {
    const detectedLanguage = languageCode || null;
    const isValid = detectedLanguage && !isUndeterminedLanguage(detectedLanguage);

    if (isValid) {
      this.languageHistory.push(detectedLanguage);
      if (this.languageHistory.length > this.languageHistorySize) this.languageHistory.shift();
      this.stableSourceLanguage = majorityLanguage(this.languageHistory, this.stableSourceLanguage);
    }

    const agreesWithStable = isValid && this.stableSourceLanguage === detectedLanguage;
    const usedFallback = !agreesWithStable;
    const sourceLanguage = agreesWithStable ? detectedLanguage : this.stableSourceLanguage;

    this._log(`language decision seq=${seq}: detectedLanguage=${detectedLanguage || '(none)'}`
      + ` stableLanguage=${this.stableSourceLanguage || '(none)'} usedFallback=${usedFallback}`
      + ` historyCount=${this.languageHistory.length} -> sourceLanguage=${sourceLanguage || '(none, will auto-detect)'}`);

    return sourceLanguage;
  }

  async _translateAndEmit(text, sourceLanguage, seq, isFinal, sttReceivedAtMs, receivedAtMs, speechBeginAtMs, speechEndAtMs) {
    if (seq < this.lastAppliedSeq) {
      this._log(`SKIP seq=${seq} superseded by lastAppliedSeq=${this.lastAppliedSeq}`);
      return;
    }

    if (sourceLanguage && shortCode(sourceLanguage) === shortCode(this.targetLanguage)) {
      const now = Date.now();
      this._log(`source already matches target (${sourceLanguage}), passthrough seq=${seq}`
        + timingSuffix(sttReceivedAtMs, receivedAtMs, now, now));
      this.lastAppliedSeq = seq;
      this.emit('translated', {
        text, translatedText: text, sourceLanguage, targetLanguage: this.targetLanguage, isFinal, seq,
        sttReceivedAtMs, translateStartedAtMs: now, translateFinishedAtMs: now,
        speechBeginAtMs, speechEndAtMs,
      });
      return;
    }

    const translateStartedAtMs = Date.now();
    this._log(`translating seq=${seq} isFinal=${isFinal} sourceLanguage=${sourceLanguage || '(none)'}`
      + ` (waited ${translateStartedAtMs - receivedAtMs}ms since queued`
      + (sttReceivedAtMs ? `, ${translateStartedAtMs - sttReceivedAtMs}ms since STT` : '')
      + `) text="${text}"`);
    try {
      const translatedText = await this.translationClient.translateText(text, sourceLanguage, this.targetLanguage);
      const translateFinishedAtMs = Date.now();
      if (this.closed) return;
      if (seq < this.lastAppliedSeq) {
        this._log(`SKIP seq=${seq} superseded while translating (lastAppliedSeq=${this.lastAppliedSeq})`);
        return;
      }
      this.lastAppliedSeq = seq;
      this._log(`translated seq=${seq} in ${translateFinishedAtMs - translateStartedAtMs}ms (Cloud Translation API call)`
        + timingSuffix(sttReceivedAtMs, receivedAtMs, translateStartedAtMs, translateFinishedAtMs)
        + `: "${translatedText}"`);
      this.emit('translated', {
        text, translatedText, sourceLanguage, targetLanguage: this.targetLanguage, isFinal, seq,
        sttReceivedAtMs, translateStartedAtMs, translateFinishedAtMs,
        speechBeginAtMs, speechEndAtMs,
      });
    } catch (err) {
      const translateFinishedAtMs = Date.now();
      if (this.closed) return;
      this._log(`translation FAILED seq=${seq} after ${translateFinishedAtMs - translateStartedAtMs}ms: ${err.message || err}`);
      if (seq < this.lastAppliedSeq) return;
      // Still bump the floor on failure too, so a stale success/failure for
      // an older seq can't land after this one and override it.
      this.lastAppliedSeq = seq;
      this.emit('translationError', {
        text, sourceLanguage, targetLanguage: this.targetLanguage, isFinal, seq, error: err,
        sttReceivedAtMs, translateStartedAtMs, translateFinishedAtMs,
        speechBeginAtMs, speechEndAtMs,
      });
    }
  }

  _cancelPendingPartial() {
    if (this.pendingPartialTimer) {
      clearTimeout(this.pendingPartialTimer);
      this.pendingPartialTimer = null;
    }
  }

  _log(message) {
    console.log(`[TranslationPipeline:${this.label}] ${message}`);
  }

  stop() {
    this.closed = true;
    this._cancelPendingPartial();
  }
}

function wordCount(text) {
  return text.split(/\s+/).filter(Boolean).length;
}

function shortCode(languageCode) {
  return String(languageCode || '').split(/[-_]/)[0].toLowerCase();
}

// Chirp 3 reports this literal code when it can't identify the utterance's
// language at all — Cloud Translation has no "auto" source-language value
// and rejects 'und' outright, so it must never be forwarded as-is.
function isUndeterminedLanguage(languageCode) {
  return !languageCode || languageCode.toLowerCase() === 'und';
}

// Picks the most frequent code in `history` (oldest first). Ties prefer
// `previousStable` if it's one of the tied codes (keeps the stable language
// from flapping between equally-represented options), otherwise the most
// recently seen tied code (lets a fresh, sustained shift win faster).
function majorityLanguage(history, previousStable) {
  if (!history.length) return null;
  const counts = new Map();
  for (const code of history) counts.set(code, (counts.get(code) || 0) + 1);
  const maxCount = Math.max(...counts.values());
  const tied = [...counts.keys()].filter((code) => counts.get(code) === maxCount);
  if (tied.length === 1) return tied[0];
  if (tied.includes(previousStable)) return previousStable;
  for (let i = history.length - 1; i >= 0; i--) {
    if (tied.includes(history[i])) return history[i];
  }
  return tied[0];
}

// Latency-diagnostic only: formats the elapsed-ms breakdown appended to log
// lines — total pipeline time since Chirp 3's result (if known) and since
// this pipeline queued it, both up to `finishedAtMs`.
function timingSuffix(sttReceivedAtMs, receivedAtMs, startedAtMs, finishedAtMs) {
  const sincePipeline = finishedAtMs - receivedAtMs;
  const sinceStt = sttReceivedAtMs ? finishedAtMs - sttReceivedAtMs : null;
  return ` [pipelineMs=${sincePipeline}${sinceStt !== null ? ` sinceSttMs=${sinceStt}` : ''}]`;
}

module.exports = TranslationPipeline;
