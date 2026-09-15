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
  }) {
    super();
    this.speechClient = speechClient;
    this.recognizer = `projects/${projectId}/locations/${location}/recognizers/_`;
    this.model = model;
    this.sampleRateHertz = sampleRateHertz;
    this.audioChannelCount = audioChannelCount;
    this.audioEncoding = audioEncoding;
    this.restartMs = restartMs;

    this.currentStream = null;
    this.restartTimer = null;
    this.pendingAudio = [];
    this.closed = false;
    this.restartAttempts = 0;
  }

  start() {
    if (this.closed) return;
    this._openStream();
    this._armRestartTimer();
  }

  pushAudio(buffer) {
    if (this.closed) return;
    if (this.currentStream) {
      this.currentStream.write({ audio: buffer });
    } else {
      this.pendingAudio.push(buffer);
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
          },
        },
        streamingFeatures: {
          interimResults: true,
        },
      },
    };
  }

  _openStream() {
    const stream = this.speechClient._streamingRecognize();

    stream.on('data', (response) => this._handleResponse(response));
    stream.on('error', (err) => this._handleStreamError(err, stream));
    stream.on('end', () => this._handleStreamEnd(stream));

    stream.write(this._buildInitialRequest());

    this.currentStream = stream;

    if (this.pendingAudio.length > 0) {
      const queued = this.pendingAudio;
      this.pendingAudio = [];
      for (const buffer of queued) {
        stream.write({ audio: buffer });
      }
    }

    this.emit('ready');
  }

  _handleResponse(response) {
    // A real response proves the stream recovered; only now is it safe to
    // reset the crash-loop counter (resetting merely on stream *open* would
    // let a stream that fails immediately after each reopen retry forever).
    this.restartAttempts = 0;
    if (!response || !response.results) return;
    for (const result of response.results) {
      const alternative = result.alternatives && result.alternatives[0];
      if (!alternative) continue;
      const payload = {
        text: alternative.transcript || '',
        languageCode: result.languageCode || '',
      };
      this.emit(result.isFinal ? 'final' : 'partial', payload);
    }
  }

  _handleStreamError(err, stream) {
    if (this.currentStream !== stream) return; // stale stream from a completed restart
    this.currentStream = null;
    this.emit('streamError', err);

    if (this.closed) return;

    // Transient network/session errors: retry a few times with backoff
    // instead of killing the whole session over one hiccup.
    this.restartAttempts += 1;
    if (this.restartAttempts > 5) {
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
    const old = this.currentStream;
    this.currentStream = null;
    if (old) {
      try { old.end(); } catch (_ignored) { /* already closed */ }
    }
    this._openStream();
    this._armRestartTimer();
  }
}

module.exports = SpeechSession;
