import http from 'node:http';

const port = Number(process.env.PORT || 3000);
const speechKey = process.env.AZURE_SPEECH_KEY || '';
const speechRegion = process.env.AZURE_SPEECH_REGION || '';
const appToken = process.env.WOW_AUDIO_APP_TOKEN || '';

const VOICES = new Set(['my-MM-NilarNeural', 'my-MM-ThihaNeural']);

function sendJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': data.length,
    'cache-control': 'no-store'
  });
  res.end(data);
}

function escapeXml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

async function readJson(req) {
  let total = 0;
  const chunks = [];
  for await (const chunk of req) {
    total += chunk.length;
    if (total > 32 * 1024) throw new Error('Request too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      return sendJson(res, 200, {
        ok: true,
        speechConfigured: Boolean(speechKey && speechRegion)
      });
    }

    if (req.method !== 'POST' || req.url !== '/v1/tts') {
      return sendJson(res, 404, { error: 'Not found' });
    }

    if (appToken && req.headers.authorization !== `Bearer ${appToken}`) {
      return sendJson(res, 401, { error: 'Unauthorized' });
    }
    if (!speechKey || !speechRegion) {
      return sendJson(res, 503, { error: 'Natural voice service is not configured' });
    }

    const body = await readJson(req);
    const text = typeof body.text === 'string' ? body.text.trim() : '';
    const voice = VOICES.has(body.voice) ? body.voice : 'my-MM-NilarNeural';
    const rate = Number.isFinite(Number(body.rate))
      ? Math.max(0.75, Math.min(1.5, Number(body.rate)))
      : 1.0;

    if (!text || text.length > 2500) {
      return sendJson(res, 400, { error: 'Text must contain 1-2500 characters' });
    }

    const percent = Math.round((rate - 1.0) * 100);
    const rateSsml = `${percent >= 0 ? '+' : ''}${percent}%`;
    const ssml = `<speak version="1.0" xml:lang="my-MM"><voice name="${voice}"><prosody rate="${rateSsml}">${escapeXml(text)}</prosody></voice></speak>`;

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    let upstream;
    try {
      upstream = await fetch(`https://${speechRegion}.tts.speech.microsoft.com/cognitiveservices/v1`, {
        method: 'POST',
        headers: {
          'Ocp-Apim-Subscription-Key': speechKey,
          'Content-Type': 'application/ssml+xml; charset=utf-8',
          'X-Microsoft-OutputFormat': 'audio-24khz-48kbitrate-mono-mp3',
          'User-Agent': 'WoW-Audio-TTS-Proxy/1.0'
        },
        body: ssml,
        signal: controller.signal
      });
    } finally {
      clearTimeout(timeout);
    }

    if (!upstream.ok) {
      const requestId = upstream.headers.get('x-requestid') || upstream.headers.get('x-microsoft-requestid');
      return sendJson(res, upstream.status, {
        error: 'Speech provider request failed',
        requestId: requestId || undefined
      });
    }

    res.writeHead(200, {
      'content-type': 'audio/mpeg',
      'cache-control': 'private, no-store',
      'x-content-type-options': 'nosniff'
    });
    for await (const chunk of upstream.body) res.write(chunk);
    res.end();
  } catch (error) {
    const message = error?.name === 'AbortError' ? 'Speech provider timed out' : 'Request failed';
    sendJson(res, error?.name === 'AbortError' ? 504 : 500, { error: message });
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`WoW Audio TTS proxy listening on ${port}`);
});
