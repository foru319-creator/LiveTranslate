'use strict';

const { Translate } = require('@google-cloud/translate').v2;

// Thin wrapper around Cloud Translation v2 so the rest of the backend
// depends on a small, easily-mockable interface instead of the Google
// client directly (see translationPipeline.test.js).
class TranslationClient {
  constructor({ projectId } = {}) {
    // On Cloud Run this picks up the attached service account automatically
    // via the metadata server (same pattern as the Speech-to-Text client) —
    // no key file. Requires roles/cloudtranslate.user on that identity.
    this.translate = new Translate({ projectId });
  }

  async translateText(text, sourceLanguage, targetLanguage) {
    const options = { to: shortLanguageCode(targetLanguage) };
    if (sourceLanguage) options.from = shortLanguageCode(sourceLanguage);
    const [translated] = await this.translate.translate(text, options);
    return translated;
  }
}

// Cloud Translation expects ISO-639-1 codes ("en", "he"), while Chirp 3
// reports BCP-47 tags with a region ("en-US", "he-IL").
function shortLanguageCode(languageCode) {
  return String(languageCode).split(/[-_]/)[0].toLowerCase();
}

module.exports = TranslationClient;
module.exports.shortLanguageCode = shortLanguageCode;
