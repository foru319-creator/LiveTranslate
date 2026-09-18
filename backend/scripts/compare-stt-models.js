'use strict';

// Standalone, local-only diagnostic tool — NOT part of the deployed service
// (the Dockerfile only COPYs src/, so this never ships to Cloud Run).
//
// Streams the same WAV file through Speech-to-Text V2 streamingRecognize
// once per model given (e.g. chirp_3 vs latest_long), using the exact same
// SpeechSession code path as production (same endpointingSensitivity/
// speechEndTimeout/restart logic), and reports how many partial vs final
// results each model produced and when — to empirically check whether
// Chirp 3's sparse/late interim results (see backend investigation,
// 2026-09-17) are specific to that model or shared by others.
//
// Usage:
//   node scripts/compare-stt-models.js <audio.wav> [options]
//
// Options:
//   --models <a,b,...>        Comma-separated model names (default: chirp_3,latest_long)
//   --endpointing <SENS>      SHORT | STANDARD | SUPERSHORT (default: SHORT, matches prod)
//   --speech-end-timeout <s>  Seconds (default: 10, matches prod)
//   --project <id>            GCP project (default: $GOOGLE_CLOUD_PROJECT or livetranslate-508621)
//   --location <loc>          Speech API location (default: $SPEECH_LOCATION or us)
//
// The WAV must be uncompressed 16-bit PCM, mono. Any sample rate is fine —
// it's read from the WAV header and passed straight to explicitDecodingConfig.
// Needs Application Default Credentials available locally
// (`gcloud auth application-default login`) with access to the Speech-to-Text
// API on the target project — the same auth Cloud Run gets automatically via
// its attached service account, just not automatic on a local machine.

const fs = require('fs');
const { SpeechClient } = require('@google-cloud/speech').v2;
const SpeechSession = require('../src/speechSession');

function parseArgs() {
  const args = process.argv.slice(2);
  const opts = {
    models: ['chirp_3', 'latest_long'],
    endpointingSensitivity: 'ENDPOINTING_SENSITIVITY_SHORT',
    speechEndTimeoutSeconds: 10,
    projectId: process.env.GOOGLE_CLOUD_PROJECT || 'livetranslate-508621',
    location: process.env.SPEECH_LOCATION || 'us',
  };
  let audioPath = null;
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--models') opts.models = args[++i].split(',').map((s) => s.trim());
    else if (a === '--endpointing') opts.endpointingSensitivity = `ENDPOINTING_SENSITIVITY_${args[++i].toUpperCase()}`;
    else if (a === '--speech-end-timeout') opts.speechEndTimeoutSeconds = parseFloat(args[++i]);
    else if (a === '--project') opts.projectId = args[++i];
    else if (a === '--location') opts.location = args[++i];
    else if (!audioPath) audioPath = a;
  }
  if (!audioPath) {
    console.error('Usage: node scripts/compare-stt-models.js <audio.wav> [--models chirp_3,latest_long] [--endpointing SHORT] [--speech-end-timeout 10] [--project id] [--location us]');
    process.exit(1);
  }
  opts.audioPath = audioPath;
  return opts;
}

// Minimal RIFF/WAVE parser — just enough to find sampleRate/channels/
// bitsPerSample and the 'data' chunk's raw bytes. No dependency added just
// for this one-off script.
function parseWav(buffer) {
  if (buffer.toString('ascii', 0, 4) !== 'RIFF' || buffer.toString('ascii', 8, 12) !== 'WAVE') {
    throw new Error('Not a RIFF/WAVE file. Export/convert your test audio to uncompressed 16-bit PCM WAV first.');
  }
  let offset = 12;
  let fmt = null;
  let data = null;
  while (offset + 8 <= buffer.length) {
    const chunkId = buffer.toString('ascii', offset, offset + 4);
    const chunkSize = buffer.readUInt32LE(offset + 4);
    const chunkStart = offset + 8;
    if (chunkId === 'fmt ') {
      fmt = {
        audioFormat: buffer.readUInt16LE(chunkStart),
        channels: buffer.readUInt16LE(chunkStart + 2),
        sampleRate: buffer.readUInt32LE(chunkStart + 4),
        bitsPerSample: buffer.readUInt16LE(chunkStart + 14),
      };
    } else if (chunkId === 'data') {
      data = buffer.subarray(chunkStart, chunkStart + chunkSize);
    }
    offset = chunkStart + chunkSize + (chunkSize % 2); // chunks are word-aligned
  }
  if (!fmt || !data) throw new Error('WAV file is missing a fmt or data chunk.');
  if (fmt.audioFormat !== 1) throw new Error(`Only uncompressed PCM WAV is supported (audioFormat=${fmt.audioFormat}). Re-export as PCM.`);
  if (fmt.bitsPerSample !== 16) throw new Error(`Only 16-bit PCM is supported (got ${fmt.bitsPerSample}-bit). Re-export as 16-bit.`);
  if (fmt.channels !== 1) {
    throw new Error(`Only mono WAV is supported (got ${fmt.channels} channels) — the production service always sends mono, so a fair comparison needs mono input. Re-export as mono.`);
  }
  return { sampleRate: fmt.sampleRate, data };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Streams the PCM in ~500ms chunks at real-time pace, matching how
// AudioCaptureService feeds the live production pipeline — VAD/endpointing
// timing is wall-clock based, so sending everything instantly would make
// speechEndTimeout/endpointingSensitivity comparisons meaningless.
async function streamAudioRealtime(session, pcm, sampleRate) {
  const bytesPerSecond = sampleRate * 2; // mono, 16-bit
  const chunkMs = 500;
  const chunkBytes = Math.round(bytesPerSecond * (chunkMs / 1000));
  for (let i = 0; i < pcm.length; i += chunkBytes) {
    session.pushAudio(Buffer.from(pcm.subarray(i, Math.min(i + chunkBytes, pcm.length))));
    await sleep(chunkMs);
  }
}

async function runOneModel(model, opts, sampleRate, pcm) {
  const speechClient = new SpeechClient({ apiEndpoint: `${opts.location}-speech.googleapis.com` });
  const events = [];
  const session = new SpeechSession({
    speechClient,
    projectId: opts.projectId,
    location: opts.location,
    model,
    sampleRateHertz: sampleRate,
    audioChannelCount: 1,
    audioEncoding: 'LINEAR16',
    restartMs: 4 * 60 * 1000 + 45 * 1000,
    endpointingSensitivity: opts.endpointingSensitivity,
    speechEndTimeoutSeconds: opts.speechEndTimeoutSeconds,
    label: `compare:${model}`,
  });

  const startedAtMs = Date.now();
  session.on('partial', (e) => events.push({ kind: 'partial', atMs: Date.now() - startedAtMs, ...e }));
  session.on('final', (e) => events.push({ kind: 'final', atMs: Date.now() - startedAtMs, ...e }));
  session.on('streamError', (err) => console.error(`  [${model}] streamError: ${err.message || err}`));
  session.on('fatal', (err) => console.error(`  [${model}] fatal: ${err.message || err}`));

  session.start();
  await streamAudioRealtime(session, pcm, sampleRate);
  // Let the last utterance finish finalizing after audio ends.
  await sleep(4000);
  session.stop();

  return events;
}

async function main() {
  const opts = parseArgs();
  const buffer = fs.readFileSync(opts.audioPath);
  const { sampleRate, data } = parseWav(buffer);
  const durationS = (data.length / (sampleRate * 2)).toFixed(1);
  console.log(`Loaded ${opts.audioPath}: ${durationS}s @ ${sampleRate}Hz mono`);
  console.log(`Comparing models: ${opts.models.join(', ')} (endpointing=${opts.endpointingSensitivity}, speechEndTimeout=${opts.speechEndTimeoutSeconds}s)\n`);

  const results = {};
  for (const model of opts.models) {
    console.log(`--- Running ${model} (real-time, ~${durationS}s) ---`);
    results[model] = await runOneModel(model, opts, sampleRate, data);
    console.log(`${model}: ${results[model].length} result(s) total\n`);
  }

  console.log('=== Summary ===');
  for (const model of opts.models) {
    const events = results[model];
    const partials = events.filter((e) => e.kind === 'partial');
    const finals = events.filter((e) => e.kind === 'final');
    const firstPartialMs = partials.length ? partials[0].atMs : null;
    console.log(`${model}: ${partials.length} partial(s), ${finals.length} final(s)`
      + (firstPartialMs !== null ? `, first partial at t=${firstPartialMs}ms` : ', no partials at all'));
  }

  console.log('\n=== Full event log ===');
  for (const model of opts.models) {
    console.log(`\n[${model}]`);
    for (const e of results[model]) {
      console.log(`  t=${e.atMs}ms ${e.kind} "${e.text}"`);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
