'use strict';

const EventEmitter = require('events');

// Wraps one logical, indefinitely-long transcription session on top of the
// Cloud Speech-to-Text V2 streamingRecognize bidi stream, which itself is
// capped at 5 minutes of audio. Emits 'partial'/'final' transcript events
// with the auto-detected language, and transparently swaps to a fresh
// underlying stream before the cap is hit so the caller never has to care.
class SpeechSession extends EventEmitter {
  constructor({
    speechClient,
    projectId,
    location,
    model,
    sampleRateHertz,
    audioChannelCount,
    audioEncoding,
    restartMs,
    // Chirp 3's default endpointing (ENDPOINTING_SENSITIVITY_STANDARD) is
    // tuned for offline-style accuracy, not live captioning — it can take
    // ~2.4s of silence before finalizing even a one-word utterance. SHORT
    // cuts that down for short sentences/conversational speech without the
    // fragmentation risk SUPERSHORT carries for longer natural phrases.
    endpointingSensitivity = 'ENDPOINTING_SENSITIVITY_SHORT',
    // How long Chirp 3 waits in silence before finalizing the current
    // utterance. Only takes effect together with endpointingSensitivity /
    // enableVoiceActivityEvents above.
    speechEndTimeoutSeconds = 1,
    // How much recently-*sent* audio (already written to a live stream, not
    // just queued) to keep around for replay into the next stream if the
    // current one dies before finishing recognition on it — see
    // _recordSentAudio()/_openStream(). Deliberately shorter than
    // speechEndTimeoutSeconds: a server-initiated close only ever happens
    // after that many seconds of confirmed silence, so a window this much
    // shorter can never contain the tail of an utterance that already got a
    // 'final' — only trailing silence (harmless to replay) or a new
    // utterance's un-transcribed onset (exactly what needs recovering).
    audioReplayWindowMs = 3000,
    // Diagnostic-only tag (e.g. the caller's uid) prefixed to this session's
    // log lines so concurrent sessions can be told apart in Cloud Run logs.
    // Never read back for any logic decision.
    label,
  }) {
    super();
    this.speechClient = speechClient;
    this.recognizer = `projects/${projectId}/locations/${location}/recognizers/_`;
    this.model = model;
    this.sampleRateHertz = sampleRateHertz;
    this.audioChannelCount = audioChannelCount;
    this.audioEncoding = audioEncoding;
    this.restartMs = restartMs;
    this.endpointingSensitivity = endpointingSensitivity;
    this.speechEndTimeoutSeconds = speechEndTimeoutSeconds;
    this.audioReplayWindowMs = audioReplayWindowMs;
    this.label = label || 'session';

    this.currentStream = null;
    this.restartTimer = null;
    this.pendingAudio = [];
    // Rolling window of { buffer, atMs } for audio already written to
    // whichever stream was current at the time — NOT audio still waiting in
    // pendingAudio. Replayed into a new stream on reconnect (see
    // _openStream()) since a dead stream may have never finished recognizing
    // it. Cleared on every 'final' (that audio is confirmed safe) and
    // trimmed by age otherwise — see _recordSentAudio().
    this.recentSentAudio = [];
    this.closed = false;
    this.restartAttempts = 0;

    // Diagnostic-only counters, never read back for any logic decision.
    this.streamOpenCount = 0;
    this.frameCount = 0;
    // Wall-clock time the current underlying stream was opened — word
    // offsets (WordInfo.startOffset/endOffset) are relative to this, not to
    // epoch time, so this is what converts them back to a wall-clock ms
    // for logging. Diagnostic-only.
    this.currentStreamStartedAtMs = null;
    // Set from SPEECH_ACTIVITY_BEGIN/END voice-activity events and attached
    // to every partial/final of the utterance they precede, then cleared
    // once a final is emitted (see _handleResponse) so they can't leak into
    // the next, unrelated utterance. Diagnostic-only — never read back for
    // any logic/routing decision.
    this.pendingSpeechBeginAtMs = null;
    this.pendingSpeechEndAtMs = null;
  }

  _log(message) {
    console.log(`[SpeechSession:${this.label}] ${message}`);
  }

  start() {
    if (this.closed) return;
    this._openStream();
    this._armRestartTimer();
  }

  pushAudio(buffer) {
    if (this.closed) return;
    this.frameCount += 1;
    // Log every 20th frame rather than every one, so a live session doesn't
    // flood Cloud Run logs.
    if (this.frameCount % 20 === 0) {
      this._log(`received ${this.frameCount} audio frames so far (last frame ${buffer.length} bytes)`);
    }
    if (this.currentStream) {
      this.currentStream.write({ audio: buffer });
      this._recordSentAudio(buffer);
    } else {
      this.pendingAudio.push(buffer);
    }
  }

  // Remembers a chunk just written to the (now current) stream, in case that
  // stream dies before Chirp 3 finishes recognizing it — see the
  // audioReplayWindowMs constructor comment and _openStream().
  _recordSentAudio(buffer) {
    const now = Date.now();
    this.recentSentAudio.push({ buffer, atMs: now });
    const cutoff = now - this.audioReplayWindowMs;
    while (this.recentSentAudio.length > 0 && this.recentSentAudio[0].atMs < cutoff) {
      this.recentSentAudio.shift();
    }
  }

  stop() {
    if (this.closed) return;
    this.closed = true;
    this._clearRestartTimer();
    if (this.currentStream) {
      try { this.currentStream.end(); } catch (_ignored) { /* already closed */ }
      this.currentStream = null;
    }
    this.pendingAudio = [];
    this.recentSentAudio = [];
  }

  _buildInitialRequest() {
    return {
      recognizer: this.recognizer,
      streamingConfig: {
        config: {
          explicitDecodingConfig: {
            encoding: this.audioEncoding,
            sampleRateHertz: this.sampleRateHertz,
            audioChannelCount: this.audioChannelCount,
          },
          languageCodes: ['auto'],
          model: this.model,
          features: {
            enableAutomaticPunctuation: true,
            // NOT enableWordTimeOffsets: Chirp 3 streaming rejects it
            // outright — "Chirp 3 only supports word timestamps in
            // Recognize and BatchRecognize requests" (confirmed via a live
            // INVALID_ARGUMENT from the API, 2026-09-16). Word-level
            // offsets (see _extractWordOffsets()) are simply unavailable in
            // streaming mode for this model; that function now always
            // returns null, which is correct, not a bug.
          },
        },
        streamingFeatures: {
          interimResults: true,
          // See the constructor comment: SHORT (not the STANDARD default)
          // is what actually cuts Chirp 3's finalization latency down for
          // live captioning, per Google's own V2 StreamingRecognitionFeatures
          // config.
          endpointingSensitivity: this.endpointingSensitivity,
          enableVoiceActivityEvents: true,
          voiceActivityTimeout: {
            speechEndTimeout: secondsToDuration(this.speechEndTimeoutSeconds),
          },
        },
      },
    };
  }

  _openStream() {
    this.streamOpenCount += 1;
    this.currentStreamStartedAtMs = Date.now();
    this._log(`opening STT stream (#${this.streamOpenCount})`);
    const stream = this.speechClient._streamingRecognize();

    stream.on('data', (response) => this._handleResponse(response));
    stream.on('error', (err) => this._handleStreamError(err, stream));
    stream.on('end', () => this._handleStreamEnd(stream));

    stream.write(this._buildInitialRequest());

    this.currentStream = stream;

    // Reconnect only (empty on the very first open): replay audio already
    // sent to the previous, now-dead stream — it may never have finished
    // recognition there (see the lost-utterance investigation this fixes).
    // Written directly via stream.write(), not pushAudio(), so it doesn't
    // re-enter pendingAudio or get double-recorded into recentSentAudio.
    // Goes *before* pendingAudio to preserve chronological order: this is
    // older audio (sent to the dead stream) than whatever queued up after it
    // died.
    if (this.recentSentAudio.length > 0) {
      this._log(`replaying ${this.recentSentAudio.length} recently-sent audio chunk(s) into the new stream (previous stream may not have finished recognizing them)`);
      for (const { buffer } of this.recentSentAudio) {
        stream.write({ audio: buffer });
      }
    }

    if (this.pendingAudio.length > 0) {
      const queued = this.pendingAudio;
      this.pendingAudio = [];
      for (const buffer of queued) {
        stream.write({ audio: buffer });
      }
    }

    this._log(`stream ready (#${this.streamOpenCount})`);
    this.emit('ready');
  }

  _handleResponse(response) {
    // A real response proves the stream recovered; only now is it safe to
    // reset the crash-loop counter (resetting merely on stream *open* would
    // let a stream that fails immediately after each reopen retry forever).
    this.restartAttempts = 0;
    const sttReceivedAtMs = Date.now();

    // enableVoiceActivityEvents (see _buildInitialRequest) makes Chirp 3
    // send separate response messages carrying only a speechEventType, no
    // results — these mark when the *person* actually started/stopped
    // talking, distinct from when a transcript result arrives. Stashed on
    // the session and attached to every partial/final of the utterance
    // that follows (see below), so "speech begin/end -> STT result" is
    // measurable per utterance, not just "STT result -> STT result".
    const eventType = normalizeSpeechEventType(response && response.speechEventType);
    if (eventType === 'SPEECH_ACTIVITY_BEGIN') {
      this.pendingSpeechBeginAtMs = sttReceivedAtMs;
      this.pendingSpeechEndAtMs = null; // a new utterance is starting
      this._log('SPEECH_ACTIVITY_BEGIN');
    } else if (eventType === 'SPEECH_ACTIVITY_END') {
      this.pendingSpeechEndAtMs = sttReceivedAtMs;
      this._log('SPEECH_ACTIVITY_END');
    } else if (eventType === 'END_OF_SINGLE_UTTERANCE') {
      this._log('END_OF_SINGLE_UTTERANCE');
    }

    const results = (response && response.results) || [];
    const hasResults = results.length > 0;
    // TEMPORARY diagnostic logging (partials/lost-utterance investigation):
    // confirms whether Chirp 3 ever sends isFinal=false results at all in
    // this config, and surfaces results with no transcript alternative that
    // the loop below would otherwise silently `continue` past unlogged.
    // Remove once that question is settled.
    this.rawResponseCount = (this.rawResponseCount || 0) + 1;
    if (hasResults) {
      this._log(`RAW STT response #${this.rawResponseCount}: resultCount=${results.length} `
        + `[${results.map((r, i) => `#${i}:isFinal=${!!r.isFinal},hasAlternative=${!!(r.alternatives && r.alternatives.length)}`
          + `,stability=${r.stability !== undefined ? r.stability : '(n/a)'}`).join('; ')}]`);
    }

    this._log(`STT response received (hasResults=${hasResults}, resultCount=${results.length}${eventType ? `, speechEventType=${eventType}` : ''})`);
    if (!hasResults) return;
    for (const result of results) {
      const alternative = result.alternatives && result.alternatives[0];
      if (!alternative) {
        this._log(`RAW STT result with no alternative: isFinal=${!!result.isFinal} keys=${Object.keys(result).join(',')}`);
        continue;
      }

      const wordOffsets = this._extractWordOffsets(alternative);
      const speechBeginAtMs = this.pendingSpeechBeginAtMs;
      const speechEndAtMs = this.pendingSpeechEndAtMs;

      const payload = {
        text: alternative.transcript || '',
        languageCode: result.languageCode || '',
        // Latency-diagnostic only: lets downstream stages (translation,
        // send-to-client) log elapsed time since Chirp 3 actually returned
        // this result, without needing a shared clock object.
        sttReceivedAtMs,
        // Also diagnostic-only — wall-clock ms (Date.now()-based, so
        // comparable to sttReceivedAtMs), null if no corresponding
        // voice-activity event has been seen yet for this utterance.
        speechBeginAtMs,
        speechEndAtMs,
        wordsStartAtMs: wordOffsets ? wordOffsets.startAtMs : null,
        wordsEndAtMs: wordOffsets ? wordOffsets.endAtMs : null,
      };

      let timingLog = '';
      if (speechBeginAtMs) timingLog += ` speechBeginToSttMs=${sttReceivedAtMs - speechBeginAtMs}`;
      if (speechEndAtMs) timingLog += ` speechEndToSttMs=${sttReceivedAtMs - speechEndAtMs}`;
      if (wordOffsets) {
        // Independent cross-check for speechBeginToSttMs/speechEndToSttMs
        // above, derived from Chirp 3's own audio-relative word timing
        // instead of wall-clock voice-activity events — see requirement 4.
        timingLog += ` wordsStartToSttMs=${Math.round(sttReceivedAtMs - wordOffsets.startAtMs)}`
          + ` wordsEndToSttMs=${Math.round(sttReceivedAtMs - wordOffsets.endAtMs)}`;
      }

      this._log(`result: isFinal=${!!result.isFinal} languageCode=${payload.languageCode || '(none)'} transcript="${payload.text}"${timingLog}`);
      this.emit(result.isFinal ? 'final' : 'partial', payload);

      if (result.isFinal) {
        // This utterance is done. Clearing here (rather than the instant a
        // result consumes it) is what lets *every* partial and the final
        // final of one utterance share the same speechBeginAtMs/
        // speechEndAtMs, instead of only the first result after the event.
        this.pendingSpeechBeginAtMs = null;
        this.pendingSpeechEndAtMs = null;
        // Everything sent up to this confirmed final is safely transcribed —
        // nothing left that a dead stream could have lost, so there's
        // nothing left worth replaying into a future reconnect.
        this.recentSentAudio = [];
      }
    }
  }

  // Converts WordInfo.startOffset/endOffset (protobuf Duration, relative to
  // this stream's start) into wall-clock ms comparable to sttReceivedAtMs —
  // an independent check on speechBeginAtMs/speechEndAtMs, which instead
  // come from voice-activity events. Diagnostic-only.
  _extractWordOffsets(alternative) {
    if (!alternative.words || !alternative.words.length || this.currentStreamStartedAtMs == null) return null;
    const first = alternative.words[0];
    const last = alternative.words[alternative.words.length - 1];
    const startAtMs = this._durationToWallClockMs(first.startOffset);
    const endAtMs = this._durationToWallClockMs(last.endOffset);
    if (startAtMs == null || endAtMs == null) return null;
    return { startAtMs, endAtMs };
  }

  _durationToWallClockMs(duration) {
    if (!duration) return null;
    return this.currentStreamStartedAtMs + toNumber(duration.seconds) * 1000 + toNumber(duration.nanos) / 1e6;
  }

  _handleStreamError(err, stream) {
    if (this.currentStream !== stream) return; // stale stream from a completed restart
    this.currentStream = null;
    this._log(`stream error (restart attempt ${this.restartAttempts + 1}): ${err.message || err}`);
    this.emit('streamError', err);

    if (this.closed) return;

    // Transient network/session errors: retry a few times with backoff
    // instead of killing the whole session over one hiccup.
    this.restartAttempts += 1;
    if (this.restartAttempts > 5) {
      this._log('giving up after too many restart attempts');
      this.emit('fatal', err);
      return;
    }
    const delay = Math.min(500 * this.restartAttempts, 5000);
    setTimeout(() => {
      if (!this.closed) this._openStream();
    }, delay);
  }

  _handleStreamEnd(stream) {
    if (this.currentStream === stream) {
      // The active stream ended on its own (not as part of a planned
      // restart) — treat it the same as an error so we reconnect.
      this._handleStreamError(new Error('stream ended unexpectedly'), stream);
    }
  }

  _armRestartTimer() {
    this._clearRestartTimer();
    this.restartTimer = setTimeout(() => this._restart(), this.restartMs);
  }

  _clearRestartTimer() {
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
  }

  // Retires the old stream before opening its replacement, matching Google's
  // own documented "infinite streaming" restart pattern for this API (close
  // fully, then reopen) rather than briefly overlapping two live streams on
  // the same client, which streamingRecognize is not documented to support.
  // Audio arriving during the gap is queued (via pushAudio) and flushed as
  // soon as the new stream opens, so nothing is dropped.
  _restart() {
    if (this.closed) return;
    this._log(`scheduled restart (closing stream #${this.streamOpenCount})`);
    const old = this.currentStream;
    this.currentStream = null;
    if (old) {
      try { old.end(); } catch (_ignored) { /* already closed */ }
    }
    this._openStream();
    this._armRestartTimer();
  }
}

// Converts a plain seconds number into the protobuf Duration shape the V2
// API's voiceActivityTimeout.speechEndTimeout field expects.
function secondsToDuration(seconds) {
  const whole = Math.floor(seconds);
  return { seconds: whole, nanos: Math.round((seconds - whole) * 1e9) };
}

const SPEECH_EVENT_TYPE_NAMES = {
  0: 'SPEECH_EVENT_TYPE_UNSPECIFIED',
  1: 'END_OF_SINGLE_UTTERANCE',
  2: 'SPEECH_ACTIVITY_BEGIN',
  3: 'SPEECH_ACTIVITY_END',
};

// The gRPC client may hand back speechEventType as either the numeric enum
// value or its string name depending on protobuf.js's decoding path — this
// normalizes to the string name either way.
function normalizeSpeechEventType(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  return SPEECH_EVENT_TYPE_NAMES[value] || String(value);
}

// protobuf Duration's seconds field is an int64, which protobuf.js may hand
// back as a plain number, a numeric string, or a Long-like object depending
// on configuration — this normalizes any of those to a plain number.
function toNumber(value) {
  if (value === null || value === undefined) return 0;
  if (typeof value === 'number') return value;
  if (typeof value.toNumber === 'function') return value.toNumber();
  return Number(value) || 0;
}

module.exports = SpeechSession;
