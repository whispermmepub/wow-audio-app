import http from 'node:http';

const PORT = Number(process.env.PORT || 3000);
const HF_BASE = 'https://freococo-f5-myanmar-tts-demo.hf.space';
const MAX_TEXT = 1800;

function sendJson(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.length),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff'
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > 32 * 1024) throw new Error('request-too-large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 300000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function compact(value) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  return text.length <= 240 ? text : text.slice(0, 240) + '…';
}

function outputUrlFromSse(raw) {
  let event = '';
  let lastData = '';
  const lines = String(raw).replace(/\r\n/g, '\n').split('\n');
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
      continue;
    }
    if (!line.startsWith('data:')) continue;
    const data = line.slice(5).trim();
    lastData = data;
    if (event === 'error') throw new Error(`hf-error: ${compact(data)}`);
    if (event !== 'complete') continue;
    const arr = JSON.parse(data);
    const first = Array.isArray(arr) ? arr[0] : null;
    if (first && typeof first === 'object') {
      if (first.url) return String(first.url);
      if (first.path) return `${HF_BASE}/gradio_api/file=${first.path}`;
    }
    if (typeof first === 'string' && first) return first.startsWith('http') ? first : `${HF_BASE}/${first.replace(/^\/+/, '')}`;
  }
  if (lastData) {
    const arr = JSON.parse(lastData);
    const first = Array.isArray(arr) ? arr[0] : null;
    if (first && typeof first === 'object') {
      if (first.url) return String(first.url);
      if (first.path) return `${HF_BASE}/gradio_api/file=${first.path}`;
    }
  }
  throw new Error('hf-no-output');
}

async function synthesize(text, speed) {
  const payload = {
    text,
    ref_audio: null,
    ref_text: '',
    speed
  };

  let post = await fetchWithTimeout(`${HF_BASE}/gradio_api/call/v2/generate_speech`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'accept': 'application/json',
      'user-agent': 'WoW-Audio-F5-Proxy/1.0'
    },
    body: JSON.stringify(payload)
  }, 60000);
  let raw = await post.text();
  if (!post.ok) throw new Error(`hf-start-${post.status}: ${compact(raw)}`);
  const eventId = String(JSON.parse(raw).event_id || '').trim();
  if (!eventId) throw new Error('hf-no-event-id');

  const result = await fetchWithTimeout(`${HF_BASE}/gradio_api/call/generate_speech/${encodeURIComponent(eventId)}`, {
    headers: {
      'accept': 'text/event-stream',
      'user-agent': 'WoW-Audio-F5-Proxy/1.0'
    }
  }, 300000);
  raw = await result.text();
  if (!result.ok) throw new Error(`hf-result-${result.status}: ${compact(raw)}`);
  const audioUrl = outputUrlFromSse(raw);

  const audio = await fetchWithTimeout(audioUrl, {
    headers: { 'user-agent': 'WoW-Audio-F5-Proxy/1.0' }
  }, 120000);
  if (!audio.ok) throw new Error(`hf-audio-${audio.status}`);
  const bytes = Buffer.from(await audio.arrayBuffer());
  if (bytes.length < 1000 || bytes.length > 80 * 1024 * 1024) throw new Error(`hf-audio-size-${bytes.length}`);
  return bytes;
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      return sendJson(res, 200, { ok: true, service: 'wow-audio-f5-proxy' });
    }
    if (req.method !== 'POST' || req.url !== '/tts') {
      return sendJson(res, 404, { error: 'not-found' });
    }

    const body = await readJson(req);
    const text = String(body?.text ?? '').trim();
    if (!text) return sendJson(res, 400, { error: 'text-required' });
    if (text.length > MAX_TEXT) return sendJson(res, 413, { error: 'text-too-long', max: MAX_TEXT });
    const speedInput = Number(body?.speed ?? 1.0);
    const speed = Number.isFinite(speedInput) ? Math.min(1.5, Math.max(0.7, speedInput)) : 1.0;

    let lastError;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const wav = await synthesize(text, speed);
        res.writeHead(200, {
          'content-type': 'audio/wav',
          'content-length': String(wav.length),
          'cache-control': 'no-store',
          'x-content-type-options': 'nosniff'
        });
        return res.end(wav);
      } catch (error) {
        lastError = error;
      }
    }
    console.error('F5 synthesis failed:', lastError);
    return sendJson(res, 502, { error: 'f5-unavailable', detail: compact(lastError?.message || lastError) });
  } catch (error) {
    console.error('Request failed:', error);
    const message = error?.message === 'request-too-large' ? 'request-too-large' : 'proxy-error';
    return sendJson(res, message === 'request-too-large' ? 413 : 500, { error: message, detail: compact(error?.message || error) });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`WoW Audio F5 proxy listening on ${PORT}`);
});
