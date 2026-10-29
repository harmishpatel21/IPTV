// Local bridge between the browser UI and a Stalker/Ministra (MAG) IPTV portal.
// The portal expects MAG set-top-box headers + a MAC cookie and sends no CORS headers,
// so the browser can't talk to it directly — this server does it on the browser's behalf.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { Readable } = require('stream');

const CONFIG_PATH = path.join(__dirname, 'config.json');
if (!fs.existsSync(CONFIG_PATH)) {
  console.error('Missing config.json. Copy config.example.json to config.json and fill in your portal URL and MAC.');
  process.exit(1);
}
const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
const PORT = config.port || 3000;
const HOST = config.host || '127.0.0.1';

const UA = 'Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3';
const CACHE_MS = 10 * 60 * 1000;

class Portal {
  constructor({ portal, mac, timezone, stbModel, serialNumber, deviceId, deviceId2, signature }) {
    this.portalUrl = portal;
    this.mac = mac.toUpperCase();
    this.timezone = timezone || 'Europe/London';
    this.stbModel = stbModel || 'MAG250';
    this.serialNumber = serialNumber || '';
    this.deviceId = deviceId || '';
    this.deviceId2 = deviceId2 || this.deviceId;
    this.signature = signature || '';
    this.token = null;
    this.endpoint = null;
    this.loginPromise = null;
    this.cache = new Map();
  }

  // Portals expose the API at different paths; derive candidates from the /c/ URL.
  candidates() {
    const u = new URL(this.portalUrl);
    const prefix = u.pathname.replace(/\/c\/?$/, '').replace(/\/$/, '');
    const list = [];
    if (prefix) list.push(`${u.origin}${prefix}/server/load.php`);
    list.push(`${u.origin}/portal.php`, `${u.origin}/stalker_portal/server/load.php`, `${u.origin}/server/load.php`);
    return [...new Set(list)];
  }

  headers() {
    const h = {
      'User-Agent': UA,
      'X-User-Agent': `Model: ${this.stbModel}; Link: WiFi`,
      'Referer': this.portalUrl,
      'Accept': '*/*',
      'Cookie': `mac=${encodeURIComponent(this.mac)}; stb_lang=en; timezone=${encodeURIComponent(this.timezone)}`,
    };
    if (this.token) h.Authorization = `Bearer ${this.token}`;
    return h;
  }

  async raw(endpoint, params) {
    const url = `${endpoint}?${new URLSearchParams({ ...params, JsHttpRequest: '1-xml' })}`;
    const res = await fetch(url, { headers: this.headers(), signal: AbortSignal.timeout(15000) });
    const text = await res.text();
    let body;
    try { body = JSON.parse(text); } catch {
      throw new Error(`Portal returned non-JSON (HTTP ${res.status}): ${text.slice(0, 120)}`);
    }
    return body.js;
  }

  login() {
    this.loginPromise ??= this.doLogin().finally(() => { this.loginPromise = null; });
    return this.loginPromise;
  }

  async doLogin() {
    this.token = null;
    let lastErr;
    for (const ep of this.endpoint ? [this.endpoint] : this.candidates()) {
      try {
        const js = await this.raw(ep, { type: 'stb', action: 'handshake', token: '' });
        if (js?.token) { this.endpoint = ep; this.token = js.token; break; }
        lastErr = new Error(`no token in ${JSON.stringify(js)?.slice(0, 120)}`);
      } catch (e) { lastErr = e; }
    }
    if (!this.token) throw new Error(`Handshake failed${lastErr ? `: ${lastErr.message}` : ''}`);

    const profile = await this.raw(this.endpoint, {
      type: 'stb', action: 'get_profile', hd: '1', stb_type: this.stbModel, sn: this.serialNumber,
      device_id: this.deviceId, device_id2: this.deviceId2, signature: this.signature,
      num_banks: '2', image_version: '218',
      hw_version: '1.7-BD-00', not_valid_token: '0', auth_second_step: '1', api_signature: '262',
    });
    if (profile?.blocked === '1' || profile?.status === 1) {
      throw new Error(`Portal rejected this MAC${profile.msg ? `: ${profile.msg}` : ''}`);
    }
    console.log(`Logged in via ${this.endpoint}`);
  }

  // Authenticated call; on any failure re-login once (tokens expire silently).
  async call(params) {
    if (!this.token) await this.login();
    try {
      const js = await this.raw(this.endpoint, params);
      if (js === undefined || js === null) throw new Error('Empty response');
      return js;
    } catch (e) {
      console.warn(`${params.type}/${params.action} failed (${e.message}); re-logging in`);
      await this.login();
      return this.raw(this.endpoint, params);
    }
  }

  async cached(key, fn, ttl = CACHE_MS) {
    const hit = this.cache.get(key);
    if (hit && Date.now() - hit.at < ttl) return hit.value;
    const value = await fn();
    this.cache.set(key, { at: Date.now(), value });
    return value;
  }

  genres() {
    return this.cached('genres', async () => {
      const js = await this.call({ type: 'itv', action: 'get_genres' });
      return js.filter(g => g.id !== '*').map(g => ({ id: String(g.id), title: g.title }));
    });
  }

  channels() {
    return this.cached('channels', async () => {
      const js = await this.call({ type: 'itv', action: 'get_all_channels' });
      return (js.data || []).map(c => ({
        id: String(c.id),
        name: c.name,
        number: Number(c.number) || 0,
        genre: String(c.tv_genre_id),
        logo: /^https?:\/\//.test(c.logo || '') ? c.logo : null,
        cmd: c.cmd,
      })).sort((a, b) => a.number - b.number);
    });
  }

  async streamUrl(cmd) {
    const js = await this.call({ type: 'itv', action: 'create_link', cmd, series: '', forced_storage: 'undefined', disable_ad: '0', download: '0' });
    const url = String(js.cmd || '').replace(/^(ffmpeg|ffrt\d*|auto)\s+/i, '').trim();
    if (!/^https?:\/\//.test(url)) throw new Error('Portal did not return a playable link');
    return url;
  }

  epg(chId) {
    return this.cached(`epg:${chId}`, async () => {
      const js = await this.call({ type: 'itv', action: 'get_short_epg', ch_id: chId, size: '4' });
      return (Array.isArray(js) ? js : []).map(p => ({
        title: p.name, descr: p.descr || '', start: Number(p.start_timestamp) * 1000, end: Number(p.stop_timestamp) * 1000,
      }));
    }, 60_000);
  }

  // ---- Video Club (VOD). Series live in the same API: title → seasons → episodes → files. ----

  vodCategories() {
    return this.cached('vod:cats', async () => {
      const js = await this.call({ type: 'vod', action: 'get_categories' });
      return js.filter(c => c.id !== '*').map(c => ({ id: String(c.id), title: c.title.replace(/\s+/g, ' ').trim() }));
    });
  }

  vodItem(v) {
    const origin = new URL(this.portalUrl).origin;
    const pic = v.screenshot_uri || '';
    return {
      id: String(v.id), name: v.name, year: v.year || '', descr: v.description || '',
      poster: pic ? new URL(pic, origin).href : null,
      rating: Number(v.rating_imdb) || Number(v.rating_kinopoisk) || 0,
      genres: v.genres_str || '', director: v.director || '', actors: v.actors || '', country: v.country || '',
      isSeries: v.is_series === '1' || v.is_series === 1, cat: String(v.category_id || ''), cmd: v.cmd || '',
    };
  }

  vodList({ cat, page = 1, search = '', sort = 'added' }) {
    return this.cached(`vod:list:${cat}:${page}:${search}:${sort}`, async () => {
      const js = await this.call({ type: 'vod', action: 'get_ordered_list', category: cat || '*', genre: '*',
        sortby: sort, search, p: String(page) });
      const total = Number(js.total_items) || 0;
      const per = Number(js.max_page_items) || 14;
      return { total, pages: Math.ceil(total / per), page: Number(page), items: (js.data || []).map(v => this.vodItem(v)) };
    });
  }

  // Seasons/episodes/files are paged like everything else; fetch every page.
  async vodAll(params) {
    const out = [];
    for (let p = 1; p <= 50; p++) {
      const js = await this.call({ type: 'vod', action: 'get_ordered_list', genre: '*', ...params, p: String(p) });
      out.push(...(js.data || []));
      const per = Number(js.max_page_items) || 14;
      if (!js.data?.length || p * per >= Number(js.total_items)) break;
    }
    return out;
  }

  vodSeasons(id, cat) {
    return this.cached(`vod:seasons:${id}`, async () => {
      const data = await this.vodAll({ movie_id: id, season_id: '0', episode_id: '0', category: cat || '*' });
      return data.filter(s => s.is_season).map(s => ({ id: String(s.id), number: Number(s.season_number) || 0, name: s.name }))
        .sort((a, b) => a.number - b.number);
    });
  }

  vodEpisodes(id, season, cat) {
    return this.cached(`vod:eps:${id}:${season}`, async () => {
      const data = await this.vodAll({ movie_id: id, season_id: season, episode_id: '0', category: cat || '*' });
      return data.filter(e => e.is_episode).map(e => ({
        id: String(e.id), number: Number(e.series_number) || 0, name: e.series_name || e.name,
      })).sort((a, b) => a.number - b.number);
    });
  }

  // Movies and episodes resolve the same way: list the title's files, then create_link on a file's cmd.
  async vodLink({ id, season = '0', episode = '0', cat, cmd }) {
    const files = (await this.vodAll({ movie_id: id, season_id: season, episode_id: episode, category: cat || '*' }))
      .filter(f => f.is_file && f.cmd);
    const cmds = [...files.map(f => f.cmd), cmd].filter(Boolean);
    for (const c of cmds) {
      const js = await this.call({ type: 'vod', action: 'create_link', cmd: c, series: '', forced_storage: '', disable_ad: '0', download: '0' });
      const url = String(js.cmd || '').replace(/^(ffmpeg|ffrt\d*|auto)\s+/i, '').trim();
      if (/^https?:\/\//.test(url)) return url;
    }
    throw new Error('Portal did not return a playable link for this title');
  }
}

const portal = new Portal(config);

// HLS playlists reference segments by (often relative) URL; route those through /proxy too.
function rewritePlaylist(text, baseUrl) {
  const wrap = u => `/proxy?url=${encodeURIComponent(new URL(u, baseUrl).href)}`;
  return text.split('\n').map(line => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${wrap(u)}"`);
    return wrap(t);
  }).join('\n');
}

async function proxy(req, res, target) {
  const abort = new AbortController();
  res.on('close', () => abort.abort());
  const headers = { 'User-Agent': UA };
  if (req.headers.range) headers.Range = req.headers.range; // lets the browser seek in MP4/MKV movies
  const upstream = await fetch(target, { headers, signal: abort.signal });
  const type = upstream.headers.get('content-type') || '';
  if (/mpegurl/i.test(type) || /\.m3u8?(\?|$)/i.test(new URL(upstream.url).pathname)) {
    const text = rewritePlaylist(await upstream.text(), upstream.url);
    res.writeHead(upstream.status, { 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-cache' });
    return res.end(text);
  }
  const out = { 'Content-Type': type || 'video/mp2t' };
  for (const h of ['content-length', 'content-range', 'accept-ranges']) {
    if (upstream.headers.has(h)) out[h] = upstream.headers.get(h);
  }
  res.writeHead(upstream.status, out);
  if (!upstream.body) return res.end();
  Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
}

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };
const PUBLIC = path.join(__dirname, 'public');

function serveStatic(res, pathname) {
  const file = path.normalize(path.join(PUBLIC, pathname === '/' ? 'index.html' : pathname));
  if (!file.startsWith(PUBLIC) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404); return res.end('Not found');
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

http.createServer(async (req, res) => {
  const { pathname, searchParams: q } = new URL(req.url, 'http://localhost');
  try {
    switch (pathname) {
      case '/api/genres': return json(res, 200, await portal.genres());
      case '/api/channels': return json(res, 200, await portal.channels());
      case '/api/link': return json(res, 200, { url: await portal.streamUrl(q.get('cmd')) });
      case '/api/epg': return json(res, 200, await portal.epg(q.get('id')));
      case '/api/vod/categories': return json(res, 200, await portal.vodCategories());
      case '/api/vod/list': return json(res, 200, await portal.vodList({
        cat: q.get('cat'), page: q.get('page') || 1, search: q.get('search') || '', sort: q.get('sort') || 'added' }));
      case '/api/vod/seasons': return json(res, 200, await portal.vodSeasons(q.get('id'), q.get('cat')));
      case '/api/vod/episodes': return json(res, 200, await portal.vodEpisodes(q.get('id'), q.get('season'), q.get('cat')));
      case '/api/vod/link': return json(res, 200, { url: await portal.vodLink({
        id: q.get('id'), season: q.get('season') || '0', episode: q.get('episode') || '0', cat: q.get('cat'), cmd: q.get('cmd') }) });
      case '/proxy': return await proxy(req, res, q.get('url'));
      default: return serveStatic(res, pathname);
    }
  } catch (e) {
    console.error(`${pathname}: ${e.message}`);
    if (!res.headersSent) json(res, 502, { error: e.message });
    else res.destroy();
  }
}).listen(PORT, HOST, () => console.log(`Open http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`));
