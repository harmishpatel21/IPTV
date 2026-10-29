const $ = s => document.querySelector(s);
const PAGE = 120;
const ROW_SIZE = 40;
const RECENT_MAX = 30;

const state = {
  genres: [], channels: [], byId: new Map(), genreTitle: new Map(),
  vodCats: [], vodCatTitle: new Map(), vodGroup: load('vodGroup', ''),
  view: 'home', prevView: 'home', genre: 'all', query: '', list: [], rendered: 0,
  vodCat: null, vodSort: 'added',
  mode: 'live', playList: [], playIndex: -1, vod: null,
};
let favs = new Set(load('favs', []));
let vodList = load('vodList', []);        // saved movie/show snapshots
let recent = load('recent', []);          // newest first: { type: 'live', id, at } | { type: 'vod', id, at, item, ep..., pos, dur }
let progress = load('vodProgress', {});   // `${titleId}:${episodeId|'movie'}` → { pos, dur, at }
let hls = null, ts = null, playToken = 0, osdTimer = null, osdTick = null, lastSave = 0, userPaused = false;

function load(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage full or blocked */ }
}

async function api(path) {
  const r = await fetch(path);
  const data = await r.json();
  if (!r.ok) throw new Error(data.error || r.statusText);
  return data;
}

const qs = params => new URLSearchParams(Object.entries(params).filter(([, v]) => v != null && v !== '')).toString();

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text != null) e.textContent = text;
  return e;
}

function setStatus(text) { $('#status').textContent = text; }

async function init() {
  setStatus('Connecting to portal…');
  try {
    const [genres, channels] = await Promise.all([api('/api/genres'), api('/api/channels')]);
    state.genres = genres;
    state.channels = channels;
    for (const c of channels) state.byId.set(c.id, c);
    for (const g of genres) state.genreTitle.set(g.id, g.title);
    setStatus('');
    renderHome();
    renderGenreRows();
  } catch (e) {
    setStatus(`Could not load channels: ${e.message}`);
  }
  try {
    state.vodCats = await api('/api/vod/categories');
    for (const c of state.vodCats) state.vodCatTitle.set(c.id, c.title);
  } catch { /* Video Club is optional */ }
}

// ---- Presentation helpers ----

const BADGE = /\s*[\[(|]?\b(8K|4K|UHD|FHD|HD|SD|HEVC|H\.?265|RAW)\b[\])|]?/gi;

function parseName(name) {
  let badge = '';
  const title = name.replace(BADGE, (_, b) => { badge ||= b.toUpperCase(); return ' '; }).replace(/\s+/g, ' ').trim();
  return { title: title || name, badge };
}

function hue(s) {
  let h = 0;
  for (const ch of s) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return h % 360;
}

function mark(title) {
  return (title.replace(/[^\p{L}\p{N} ]/gu, '').trim().split(' ')[0] || title).slice(0, 4).toUpperCase();
}

const hhmm = ms => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

function clock(s) {
  s = Math.max(0, Math.floor(s || 0));
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

function ago(at) {
  if (!at) return '';
  const m = Math.round((Date.now() - at) / 60000);
  if (m < 1) return 'Watched just now';
  if (m < 60) return `Watched ${m} min ago`;
  const h = Math.round(m / 60);
  return h < 24 ? `Watched ${h} h ago` : `Watched ${Math.round(h / 24)} d ago`;
}

const epLabel = e => e.epId ? `S${e.seasonNum}:E${e.epNum}` : '';
const left = (pos, dur) => dur ? `${Math.max(1, Math.round((dur - pos) / 60))} min left` : '';

// ---- EPG (cached client-side, fetched one at a time to stay gentle on the portal) ----

const epgCache = new Map();
let epgQueue = Promise.resolve();

function getEpg(id) {
  const hit = epgCache.get(id);
  if (hit && Date.now() - hit.at < 120_000) return hit.promise;
  const promise = epgQueue = epgQueue.then(() => api(`/api/epg?id=${encodeURIComponent(id)}`)).catch(() => []);
  epgCache.set(id, { at: Date.now(), promise });
  return promise;
}

function nowNext(progs) {
  const now = Date.now();
  const cur = progs.find(p => p.start <= now && now < p.end);
  const next = progs.find(p => p.start >= (cur ? cur.end : now));
  const pct = cur ? Math.min(100, (now - cur.start) / (cur.end - cur.start) * 100) : 0;
  return { cur, next, pct };
}

// ---- Channel tiles ----

function actionButton(cls, text, title, onClick) {
  const b = el('button', cls, text);
  b.title = title;
  b.tabIndex = -1; // remote users use F / the details panel instead
  b.onclick = e => { e.stopPropagation(); onClick(); };
  return b;
}

const favButton = (on, onClick) => actionButton(`icon-btn${on ? ' on' : ''}`, on ? '★' : '+', 'My List (F)', onClick);
const removeButton = onClick => actionButton('icon-btn', '✕', 'Remove from Continue Watching', onClick);

// Tile button + its action buttons side by side in a wrapper (a button inside a button can swallow the click).
function tileWrap(button, actions, data) {
  const wrap = el('div', 'tile-wrap');
  Object.assign(wrap.dataset, data);
  const box = el('div', 'tile-actions');
  box.append(...actions);
  wrap.append(button, box);
  return wrap;
}

function progressBar(pct) {
  const bar = el('div', 'bar');
  const fill = el('i');
  fill.style.width = `${pct}%`;
  bar.append(fill);
  return { bar, fill };
}

function tile(ch, list, { recentRow = false } = {}) {
  const { title, badge } = parseName(ch.name);
  const b = el('button', 'tile');
  b.style.setProperty('--h', hue(ch.name));
  b.dataset.id = ch.id;

  const art = el('div', 'art');
  art.append(el('span', 'mark', mark(title)), el('span', 'num', ch.number || ''), el('span', 'title', title));
  if (badge) art.append(el('span', 'badge', badge));
  b.append(art);

  const actions = [favButton(favs.has(ch.id), () => toggleFav(ch.id))];
  if (recentRow) actions.push(removeButton(() => removeRecent(ch.id)));

  if (recentRow) {
    const { bar, fill } = progressBar(0);
    art.append(bar);
    const sub = el('div', 'sub', ' ');
    b.append(sub);
    getEpg(ch.id).then(progs => {
      const { cur, pct } = nowNext(progs);
      fill.style.width = `${pct}%`;
      if (cur) { sub.textContent = 'Now: '; sub.append(el('b', '', cur.title)); }
      else sub.textContent = ago(recent.find(r => r.id === ch.id)?.at);
    });
  }

  b.onclick = () => play(list, list.indexOf(ch));
  return tileWrap(b, actions, { id: ch.id });
}

// Continue-watching tile for a movie or episode.
function vodTile(entry) {
  const { item } = entry;
  const b = el('button', 'tile');
  b.style.setProperty('--h', hue(item.name));
  b.dataset.vod = item.id;
  const art = el('div', 'art');
  if (item.poster) {
    const img = el('img', 'shot');
    img.loading = 'lazy';
    img.src = item.poster;
    img.onerror = () => { img.remove(); art.classList.remove('has-shot'); };
    art.classList.add('has-shot');
    art.append(img);
  } else art.append(el('span', 'mark', mark(item.name)));
  art.append(el('span', 'title', item.name));
  if (entry.epId) art.append(el('span', 'badge', epLabel(entry)));
  art.append(progressBar(entry.dur ? entry.pos / entry.dur * 100 : 0).bar);
  b.append(art);

  const sub = el('div', 'sub');
  if (entry.epName) { sub.append(el('b', '', entry.epName), ` · ${left(entry.pos, entry.dur)}`); }
  else sub.textContent = left(entry.pos, entry.dur) || ago(entry.at);
  b.append(sub);
  b.vodItem = item;
  b.onclick = () => resumeVod(entry);
  return tileWrap(b, [removeButton(() => removeRecent(item.id))], { vod: item.id });
}

// ---- Movie / show posters ----

function poster(item) {
  const b = el('button', 'poster');
  b.style.setProperty('--h', hue(item.name));
  b.dataset.vod = item.id;
  const pic = el('div', 'pic', item.name);
  if (item.poster) {
    const img = el('img');
    img.loading = 'lazy';
    img.alt = '';
    img.src = item.poster;
    img.onerror = () => img.remove();
    pic.append(img);
  }
  if (item.isSeries) pic.append(el('span', 'kind', 'SERIES'));
  b.append(pic, el('div', 'sub', [item.name, item.year].filter(Boolean).join(' · ')));
  b.vodItem = item;
  b.onclick = () => openDetail(item);
  return b;
}

// ---- Favourites & history ----

const favChannels = () => [...favs].map(id => state.byId.get(id)).filter(Boolean);
const liveRecent = () => recent.filter(r => r.type !== 'vod').map(r => state.byId.get(r.id)).filter(Boolean);
const inVodList = id => vodList.some(v => v.id === id);

function toggleFav(id) {
  favs.has(id) ? favs.delete(id) : favs.add(id);
  save('favs', [...favs]);
  for (const t of document.querySelectorAll(`.tile-wrap[data-id="${CSS.escape(id)}"] .tile-actions .icon-btn:first-child`)) {
    t.classList.toggle('on', favs.has(id));
    t.textContent = favs.has(id) ? '★' : '+';
  }
  refreshPersonal();
}

function toggleVodList(item) {
  vodList = inVodList(item.id) ? vodList.filter(v => v.id !== item.id) : [snapshot(item), ...vodList];
  save('vodList', vodList);
  refreshPersonal();
}

function snapshot(item) {
  const { id, name, year, poster, isSeries, cat, cmd, descr, genres, rating } = item;
  return { id, name, year, poster, isSeries, cat, cmd, descr: descr.slice(0, 400), genres, rating };
}

function addRecent(entry) {
  recent = [{ ...entry, at: Date.now() }, ...recent.filter(r => r.id !== entry.id)].slice(0, RECENT_MAX);
  save('recent', recent);
}

function removeRecent(id) {
  recent = recent.filter(r => r.id !== id);
  save('recent', recent);
  refreshPersonal();
}

// Re-render only the parts that depend on favourites/history.
function refreshPersonal() {
  if (!state.channels.length) return;
  renderHero();
  renderPersonalRows();
  if (state.view === 'mylist') applyFilter();
}

// ---- Home ----

function renderHome() {
  const personal = el('div');
  personal.id = 'personal-rows';
  const genres = el('div');
  genres.id = 'genre-rows';
  $('#rows').replaceChildren(personal, genres);
  renderHero();
  renderPersonalRows();
}

function heroShell(h, markText, image) {
  const bg = el('div', 'hero-bg');
  bg.style.setProperty('--h', h);
  bg.dataset.mark = image ? '' : markText;
  if (image) {
    const img = el('img', 'hero-shot');
    img.src = image;
    img.onerror = () => img.remove();
    bg.append(img);
  }
  return bg;
}

function heroButton(cls, text, onClick, icon = false) {
  const b = el('button', `btn ${cls}`);
  if (icon) b.innerHTML = '<svg viewBox="0 0 24 24"><path d="M7 4v16l13-8z"/></svg>';
  b.append(text);
  b.onclick = onClick;
  return b;
}

function renderHero() {
  const last = recent[0];
  if (last?.type === 'vod') return renderVodHero(last);
  const rc = liveRecent();
  const ch = (last && state.byId.get(last.id)) || favChannels()[0] || state.channels[0];
  const hero = $('#hero');
  if (!ch) { hero.replaceChildren(); return; }
  const { title, badge } = parseName(ch.name);
  const resume = rc[0] === ch;

  const body = el('div', 'hero-body');
  const kicker = el('div', 'hero-kicker');
  kicker.innerHTML = resume ? '<b>●</b> Continue watching' : 'Featured channel';
  const meta = el('div', 'hero-meta');
  if (ch.number) meta.append(el('span', '', `CH ${ch.number}`));
  if (badge) meta.append(el('span', 'badge', badge));
  const genre = state.genreTitle.get(ch.genre);
  if (genre) meta.append(el('span', '', genre));
  const now = el('div', 'hero-now');
  const descr = el('div', 'hero-descr');
  const prog = el('div', 'progress hero-progress');
  const fill = el('i');
  prog.append(fill);
  prog.hidden = true;

  const actions = el('div', 'hero-actions');
  const list = resume ? rc : [ch];
  actions.append(
    heroButton('primary', resume ? 'Resume' : 'Play', () => play(list, list.indexOf(ch)), true),
    heroButton('secondary', favs.has(ch.id) ? '✓  My List' : '+  My List', () => toggleFav(ch.id)),
  );

  body.append(kicker, el('h1', 'hero-title', title), meta, now, prog, descr, actions);
  hero.replaceChildren(heroShell(hue(ch.name), mark(title)), body);

  getEpg(ch.id).then(progs => {
    const { cur, next, pct } = nowNext(progs);
    if (cur) {
      now.textContent = `Now · ${cur.title}`;
      descr.textContent = cur.descr || (next ? `Up next at ${hhmm(next.start)}: ${next.title}` : '');
      fill.style.width = `${pct}%`;
      prog.hidden = false;
    }
  });
}

function renderVodHero(entry) {
  const { item } = entry;
  const body = el('div', 'hero-body');
  const kicker = el('div', 'hero-kicker');
  kicker.innerHTML = '<b>●</b> Continue watching';
  const meta = el('div', 'hero-meta');
  if (item.year) meta.append(el('span', '', item.year));
  if (entry.epId) meta.append(el('span', 'badge', epLabel(entry)));
  if (item.genres) meta.append(el('span', '', item.genres));
  const now = el('div', 'hero-now', entry.epName ? `${epLabel(entry)} · ${entry.epName}` : left(entry.pos, entry.dur));
  const prog = el('div', 'progress hero-progress');
  const fill = el('i');
  fill.style.width = `${entry.dur ? entry.pos / entry.dur * 100 : 0}%`;
  prog.append(fill);
  const actions = el('div', 'hero-actions');
  actions.append(
    heroButton('primary', 'Resume', () => resumeVod(entry), true),
    heroButton('secondary', 'ⓘ  More info', () => openDetail(item)),
  );
  body.append(kicker, el('h1', 'hero-title', item.name), meta, now, prog, el('div', 'hero-descr', item.descr || ''), actions);
  $('#hero').replaceChildren(heroShell(hue(item.name), '', item.poster), body);
}

function makeRow(title, onSeeAll) {
  const row = el('section', 'row');
  const head = el('div', 'row-head');
  head.append(el('h3', '', title));
  if (onSeeAll) {
    const see = el('button', 'see-all', 'See all ›');
    see.onclick = onSeeAll;
    head.append(see);
  }
  const wrap = el('div', 'row-wrap');
  const track = el('div', 'track');
  const l = el('button', 'arrow left', '‹');
  const r = el('button', 'arrow right', '›');
  l.tabIndex = r.tabIndex = -1;
  l.onclick = () => track.scrollBy({ left: -track.clientWidth * 0.85 });
  r.onclick = () => track.scrollBy({ left: track.clientWidth * 0.85 });
  wrap.append(l, track, r);
  row.append(head, wrap);
  return { row, track };
}

function renderPersonalRows() {
  const box = $('#personal-rows');
  if (!box) return;
  const parts = [];
  const cw = recent.map(r => {
    if (r.type === 'vod') return vodTile(r);
    const ch = state.byId.get(r.id);
    return ch && tile(ch, liveRecent(), { recentRow: true });
  }).filter(Boolean);
  if (cw.length) {
    const { row, track } = makeRow('Continue Watching');
    track.append(...cw);
    parts.push(row);
  }
  const fc = favChannels();
  if (fc.length) {
    const { row, track } = makeRow('My Channels', () => showView('mylist'));
    track.append(...fc.slice(0, ROW_SIZE).map(c => tile(c, fc)));
    parts.push(row);
  }
  if (vodList.length) {
    const { row, track } = makeRow('My Movies & Shows', () => showView('mylist'));
    track.append(...vodList.slice(0, ROW_SIZE).map(poster));
    parts.push(row);
  }
  box.replaceChildren(...parts);
}

// Rows fill in lazily as they approach the viewport.
const rowObserver = new IntersectionObserver(entries => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    rowObserver.unobserve(e.target);
    e.target.fill();
  }
}, { rootMargin: '500px 0px' });

function renderGenreRows() {
  const byGenre = new Map();
  for (const c of state.channels) {
    if (!byGenre.has(c.genre)) byGenre.set(c.genre, []);
    byGenre.get(c.genre).push(c);
  }
  const rows = state.genres.filter(g => byGenre.has(g.id)).map(g => {
    const list = byGenre.get(g.id);
    const { row, track } = makeRow(g.title, () => { state.genre = g.id; showView('browse'); });
    row.fill = () => track.append(...list.slice(0, ROW_SIZE).map(c => tile(c, list)));
    rowObserver.observe(row);
    return row;
  });
  $('#genre-rows').replaceChildren(...rows);
}

// ---- Views ----

const VIEWS = { home: 'home', browse: 'browse', mylist: 'browse', vod: 'vod', vodcat: 'vodcat', search: 'results' };

function showView(view) {
  if (view !== state.view && state.view !== 'search') state.prevView = state.view;
  state.view = view;
  for (const id of new Set(Object.values(VIEWS))) $(`#${id}`).hidden = VIEWS[view] !== id;
  const nav = view === 'vodcat' ? 'vod' : view;
  for (const a of document.querySelectorAll('.links a')) a.classList.toggle('active', a.dataset.view === nav);
  if (view !== 'search') { state.query = ''; $('#search').value = ''; }
  if (view === 'browse' || view === 'mylist') { renderChips(); applyFilter(); }
  if (view === 'vod') renderVodHome();
  if (view === 'vodcat') renderVodCat();
  if (view === 'search') renderResults();
  window.scrollTo(0, 0);
}

function goBack() {
  if (state.view === 'vodcat') showView('vod');
  else if (state.view !== 'home') showView('home');
}

// ---- Live TV browse / My List ----

function renderChips() {
  const chips = $('#chips');
  chips.hidden = state.view !== 'browse';
  if (chips.hidden) return;
  const counts = new Map();
  for (const c of state.channels) counts.set(c.genre, (counts.get(c.genre) || 0) + 1);
  const items = [{ id: 'all', title: 'All' }, ...state.genres.filter(g => counts.has(g.id))];
  chips.replaceChildren(...items.map(g => {
    const b = el('button', `chip${g.id === state.genre ? ' active' : ''}`, g.title);
    b.onclick = () => { state.genre = g.id; renderChips(); applyFilter(); b.focus(); };
    return b;
  }));
}

function applyFilter() {
  const mylist = state.view === 'mylist';
  let title;
  if (mylist) {
    state.list = favChannels();
    title = 'My List';
  } else {
    state.list = state.genre === 'all' ? state.channels : state.channels.filter(c => c.genre === state.genre);
    title = state.genre === 'all' ? 'All Channels' : state.genreTitle.get(state.genre) || 'Channels';
  }
  const h = $('#browse-title');
  h.textContent = title;
  h.append(el('small', '', mylist
    ? `${state.list.length} channels · ${vodList.length} movies & shows`
    : `${state.list.length.toLocaleString()} channels`));
  state.rendered = 0;
  $('#grid').replaceChildren();
  if (!state.list.length && !(mylist && vodList.length)) {
    $('#grid').append(el('div', 'empty', mylist
      ? 'Your list is empty. Hover a channel, movie or show and press + (or F) to add it.'
      : 'No channels found.'));
  }
  renderMore();
  $('#mylist-vod').hidden = !mylist || !vodList.length;
  if (mylist) $('#mylist-vod-grid').replaceChildren(...vodList.map(poster));
}

function renderMore() {
  const end = Math.min(state.rendered + PAGE, state.list.length);
  const frag = document.createDocumentFragment();
  for (let i = state.rendered; i < end; i++) frag.append(tile(state.list[i], state.list));
  state.rendered = end;
  $('#grid').append(frag);
}

new IntersectionObserver(entries => {
  if (entries[0].isIntersecting && state.rendered < state.list.length) renderMore();
}, { rootMargin: '800px' }).observe($('#sentinel'));

// ---- Movies & Shows ----

// Categories look like "HINDI MOVIES | LATEST"; the first word groups them by language/section.
const groupOf = title => title.split(/[\s|]+/)[0].toUpperCase();
const pretty = s => s.toLowerCase().replace(/(^|[\s(|/-])\p{L}/gu, m => m.toUpperCase());

function renderVodHome() {
  if (!state.vodCats.length) {
    $('#vod-groups').replaceChildren(el('div', 'empty', 'Loading Movies & Shows…'));
    $('#vod-rows').replaceChildren();
    api('/api/vod/categories').then(cats => {
      state.vodCats = cats;
      for (const c of cats) state.vodCatTitle.set(c.id, c.title);
      if (state.view === 'vod') renderVodHome();
    }).catch(e => $('#vod-groups').replaceChildren(el('div', 'empty', `Could not load: ${e.message}`)));
    return;
  }
  const groups = [...new Set(state.vodCats.map(c => groupOf(c.title)))];
  if (!groups.includes(state.vodGroup)) state.vodGroup = groups[0];
  $('#vod-groups').replaceChildren(...groups.map(g => {
    const b = el('button', `chip${g === state.vodGroup ? ' active' : ''}`, pretty(g));
    b.onclick = () => { state.vodGroup = g; save('vodGroup', g); renderVodHome(); b.focus(); };
    return b;
  }));
  const rows = state.vodCats.filter(c => groupOf(c.title) === state.vodGroup).map(cat => {
    const { row, track } = makeRow(pretty(cat.title), () => openVodCat(cat.id));
    row.fill = async () => {
      track.append(el('div', 'loading', 'Loading…'));
      try {
        const { items } = await api(`/api/vod/list?${qs({ cat: cat.id, page: 1 })}`);
        track.replaceChildren(...items.map(poster));
        if (!items.length) track.append(el('div', 'loading', 'Nothing here yet.'));
      } catch (e) { track.replaceChildren(el('div', 'loading', `Could not load: ${e.message}`)); }
    };
    rowObserver.observe(row);
    return row;
  });
  $('#vod-rows').replaceChildren(...rows);
}

function openVodCat(id) {
  state.vodCat = id;
  state.vodSort = 'added';
  showView('vodcat');
}

// Infinite paging over a portal list (one portal page at a time).
let pager = null;

function startPager(grid, sentinel, fetchPage, onFirst) {
  const p = { page: 0, pages: 1, busy: false };
  pager = p;
  grid.replaceChildren();
  p.next = async () => {
    if (pager !== p || p.busy || p.page >= p.pages) return;
    p.busy = true;
    const spinner = el('div', 'loading', 'Loading…');
    grid.append(spinner);
    try {
      const r = await fetchPage(p.page + 1);
      if (pager !== p) return;
      if (p.page === 0) onFirst?.(r);
      p.page = r.page;
      p.pages = r.pages;
      spinner.replaceWith(...r.items.map(poster));
      if (p.page === 1 && !r.items.length) grid.append(el('div', 'empty', 'Nothing found.'));
    } catch (e) {
      spinner.textContent = `Could not load: ${e.message}`;
      p.pages = 0;
    } finally {
      p.busy = false;
    }
    if (sentinel.getBoundingClientRect().top < innerHeight + 800) p.next();
  };
  p.next();
}

const pageObserver = new IntersectionObserver(entries => {
  if (entries.some(e => e.isIntersecting)) pager?.next();
}, { rootMargin: '800px' });
pageObserver.observe($('#vodcat-sentinel'));
pageObserver.observe($('#results-sentinel'));

const SORTS = [['added', 'Recently added'], ['name', 'A–Z'], ['rating', 'Top rated']];

function renderVodCat() {
  const title = $('#vodcat-title');
  title.textContent = pretty(state.vodCatTitle.get(state.vodCat) || 'Movies & Shows');
  $('#vodcat-sort').replaceChildren(...SORTS.map(([key, label]) => {
    const b = el('button', `chip${key === state.vodSort ? ' active' : ''}`, label);
    b.onclick = () => { state.vodSort = key; renderVodCat(); b.focus(); };
    return b;
  }));
  startPager($('#vodcat-grid'), $('#vodcat-sentinel'),
    page => api(`/api/vod/list?${qs({ cat: state.vodCat, page, sort: state.vodSort })}`),
    r => title.append(el('small', '', `${r.total.toLocaleString()} titles`)));
}

// ---- Search (channels + movies & shows) ----

function renderResults() {
  const q = state.query.trim();
  const ql = q.toLowerCase();
  $('#results-title').textContent = `Results for “${q}”`;
  const chans = state.channels.filter(c => c.name.toLowerCase().includes(ql) || String(c.number) === ql);
  const box = $('#results-channels');
  if (chans.length) {
    const { row, track } = makeRow(`Live TV · ${chans.length.toLocaleString()}`, chans.length > ROW_SIZE
      ? () => showResultsGrid(chans) : null);
    track.append(...chans.slice(0, ROW_SIZE).map(c => tile(c, chans)));
    box.replaceChildren(row);
  } else box.replaceChildren();
  const vt = $('#results-vod-title');
  vt.textContent = 'Movies & Shows';
  startPager($('#results-grid'), $('#results-sentinel'),
    page => api(`/api/vod/list?${qs({ search: q, page })}`),
    r => vt.append(el('small', '', `  ${r.total.toLocaleString()}`)));
}

function showResultsGrid(chans) {
  showView('browse');
  state.list = chans;
  state.rendered = 0;
  $('#chips').hidden = true;
  $('#browse-title').textContent = 'Matching channels';
  $('#grid').replaceChildren();
  renderMore();
}

let searchTimer;
$('#search').addEventListener('input', e => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    const q = e.target.value;
    if (!q.trim()) { if (state.view === 'search') showView(state.prevView); return; }
    state.query = q;
    if (state.view !== 'search') showView('search');
    else renderResults();
  }, 300);
});

for (const a of document.querySelectorAll('[data-view]')) {
  a.addEventListener('click', e => {
    e.preventDefault();
    if (a.dataset.view === 'browse') state.genre = 'all';
    showView(a.dataset.view);
  });
}

addEventListener('scroll', () => $('#topbar').classList.toggle('solid', scrollY > 40), { passive: true });

// ---- Title details (movie or series) ----

let detailToken = 0;

function closeDetail() {
  detailToken++;
  $('#detail').hidden = true;
  document.body.classList.remove('modal');
}

async function openDetail(item) {
  const token = ++detailToken;
  $('#detail').hidden = false;
  document.body.classList.add('modal');
  $('#detail').scrollTop = 0;

  const top = el('div', 'detail-top');
  if (item.poster) top.style.setProperty('--bgimg', `url("${item.poster}")`);
  const pic = el('div', 'detail-poster');
  if (item.poster) { const img = el('img'); img.src = item.poster; img.alt = ''; pic.append(img); }

  const info = el('div', 'detail-info');
  const meta = el('div', 'detail-meta');
  if (item.rating) meta.append(el('span', 'rating', `★ ${Number(item.rating).toFixed(1)}`));
  if (item.year) meta.append(el('span', '', item.year));
  if (item.isSeries) meta.append(el('span', 'badge', 'SERIES'));
  if (item.genres) meta.append(el('span', '', item.genres));
  const actions = el('div', 'hero-actions');
  const credits = el('div', 'detail-credits');
  for (const [k, v] of [['Cast', item.actors], ['Director', item.director], ['Country', item.country]]) {
    if (!v) continue;
    const line = el('div');
    line.append(el('b', '', `${k}: `), v);
    credits.append(line);
  }
  info.append(el('h2', '', item.name), meta, el('p', 'detail-descr', item.descr || ''), actions, credits);
  top.append(pic, info);

  const listBtn = () => heroButton('secondary', inVodList(item.id) ? '✓  My List' : '+  My List', () => {
    toggleVodList(item);
    actions.lastChild.replaceWith(listBtn());
  });

  const episodesBox = el('div', 'detail-episodes');
  $('#detail-body').replaceChildren(top, episodesBox);

  if (!item.isSeries) {
    const p = progress[`${item.id}:movie`];
    const resume = p && p.pos > 30 && p.pos < p.dur - 60;
    actions.append(
      heroButton('primary', resume ? `Resume · ${left(p.pos, p.dur)}` : 'Play', () => playVod({ item, resumeAt: resume ? p.pos : 0 }), true),
      listBtn(),
    );
    actions.firstChild.focus({ preventScroll: true });
    return;
  }

  // Series: seasons → episodes.
  const last = recent.find(r => r.id === item.id);
  const playBtn = heroButton('primary', 'Loading…', () => {}, true);
  actions.append(playBtn, listBtn());
  episodesBox.append(el('div', 'loading', 'Loading seasons…'));
  let seasons;
  try {
    seasons = await api(`/api/vod/seasons?${qs({ id: item.id, cat: item.cat })}`);
  } catch (e) {
    if (token === detailToken) episodesBox.replaceChildren(el('div', 'empty', `Could not load seasons: ${e.message}`));
    return;
  }
  if (token !== detailToken) return;
  if (!seasons.length) { episodesBox.replaceChildren(el('div', 'empty', 'No episodes available.')); playBtn.remove(); return; }

  if (last?.epId) {
    playBtn.lastChild.textContent = `Resume ${epLabel(last)}`;
    playBtn.onclick = () => resumeVod(last);
  } else {
    playBtn.lastChild.textContent = `Play S${seasons[0].number}:E1`;
    playBtn.onclick = async () => {
      const eps = await api(`/api/vod/episodes?${qs({ id: item.id, season: seasons[0].id, cat: item.cat })}`);
      playVod({ item, season: seasons[0], episodes: eps, index: 0 });
    };
  }
  playBtn.focus({ preventScroll: true });

  const chips = el('div', 'chips');
  const list = el('div');
  episodesBox.replaceChildren(el('h3', 'section-title', 'Episodes'), chips, list);

  const showSeason = async season => {
    for (const c of chips.children) c.classList.toggle('active', c.dataset.id === season.id);
    list.replaceChildren(el('div', 'loading', 'Loading episodes…'));
    try {
      const eps = await api(`/api/vod/episodes?${qs({ id: item.id, season: season.id, cat: item.cat })}`);
      if (token !== detailToken) return;
      list.replaceChildren(...eps.map((ep, i) => {
        const b = el('button', 'ep');
        const p = progress[`${item.id}:${ep.id}`];
        b.append(el('span', 'n', ep.number), el('span', 't', ep.name));
        b.append(progressBar(p?.dur ? p.pos / p.dur * 100 : 0).bar);
        b.onclick = () => playVod({ item, season, episodes: eps, index: i, resumeAt: resumable(p) });
        return b;
      }));
      if (!eps.length) list.append(el('div', 'empty', 'No episodes in this season.'));
    } catch (e) { list.replaceChildren(el('div', 'empty', `Could not load episodes: ${e.message}`)); }
  };
  chips.append(...seasons.map(s => {
    const b = el('button', 'chip', `Season ${s.number}`);
    b.dataset.id = s.id;
    b.onclick = () => showSeason(s);
    return b;
  }));
  showSeason(seasons.find(s => s.id === last?.seasonId) || seasons[0]);
}

const resumable = p => (p && p.pos > 30 && p.pos < p.dur - 60 ? p.pos : 0);

$('#detail-close').onclick = closeDetail;
$('#detail').addEventListener('click', e => { if (e.target.id === 'detail') closeDetail(); });

// ---- Playback ----

function stopPlayback() {
  if (hls) { hls.destroy(); hls = null; }
  if (ts) { ts.destroy(); ts = null; }
  const v = $('#video');
  v.removeAttribute('src');
  v.load();
}

function showOsd() {
  $('#osd').classList.remove('hide');
  clearTimeout(osdTimer);
  // Stays up only while the viewer has paused a movie/episode (or an error is shown).
  osdTimer = setTimeout(() => { if (!userPaused) $('#osd').classList.add('hide'); }, 4000);
}

function togglePause() {
  userPaused = !video.paused;
  userPaused ? video.pause() : video.play().catch(() => {});
  showOsd();
}

function osdError(msg) {
  $('#osd-now').replaceChildren(el('span', 'err', msg));
  $('#osd').classList.remove('hide');
  clearTimeout(osdTimer);
}

function openPlayer(mode, name) {
  state.mode = mode;
  userPaused = false;
  stopPlayback();
  clearInterval(osdTick);
  $('#player').hidden = false;
  $('#osd-live').hidden = mode !== 'live';
  $('#osd-name').textContent = name;
  $('#osd-now').textContent = '';
  $('#osd-next').textContent = '';
  $('#osd-time').textContent = '';
  $('#osd-bar').style.width = '0';
  $('#osd-hint').textContent = mode === 'live'
    ? '↑ ↓ change channel · Esc back'
    : '← → seek 10 s · Space pause · N next episode · Esc back';
  showOsd();
}

function attachSource(url, live) {
  const src = `${location.origin}/proxy?url=${encodeURIComponent(url)}`;
  const video = $('#video');
  if (/\.m3u8(\?|$)/i.test(url) && Hls.isSupported()) {
    hls = new Hls({ lowLatencyMode: false });
    hls.on(Hls.Events.ERROR, (_, d) => d.fatal && osdError(`Stream error: ${d.details}`));
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
    hls.loadSource(src);
    hls.attachMedia(video);
  } else if (live && mpegts.isSupported()) {
    ts = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: src }, { enableStashBuffer: false });
    ts.on(mpegts.Events.ERROR, (type, detail) => osdError(`Stream error: ${type} ${detail}`));
    ts.attachMediaElement(video);
    ts.load();
    ts.play();
  } else {
    video.src = src;
    video.play().catch(() => {});
  }
}

async function play(list, i) {
  const ch = list[i];
  if (!ch) return;
  state.playList = list;
  state.playIndex = i;
  addRecent({ type: 'live', id: ch.id });
  const token = ++playToken;
  openPlayer('live', `${ch.number ? `${ch.number}  ·  ` : ''}${parseName(ch.name).title}`);
  try {
    const { url } = await api(`/api/link?cmd=${encodeURIComponent(ch.cmd)}`);
    if (token !== playToken) return;
    attachSource(url, true);
  } catch (e) {
    if (token === playToken) osdError(e.message);
    return;
  }
  updateOsdEpg(ch, token);
  osdTick = setInterval(() => updateOsdEpg(ch, token), 30_000);
}

async function updateOsdEpg(ch, token) {
  const progs = await getEpg(ch.id);
  if (token !== playToken) return;
  const { cur, next, pct } = nowNext(progs);
  $('#osd-now').textContent = cur ? `${cur.title}   ${hhmm(cur.start)} – ${hhmm(cur.end)}` : '';
  $('#osd-next').textContent = next ? `Next  ${hhmm(next.start)}  ${next.title}` : '';
  $('#osd-bar').style.width = `${pct}%`;
}

// ctx: { item, resumeAt } for a movie, or { item, season, episodes, index, resumeAt } for an episode.
async function playVod(ctx) {
  closeDetail();
  const { item, season, episodes, index } = ctx;
  const ep = episodes?.[index];
  state.vod = ctx;
  const token = ++playToken;
  openPlayer('vod', item.name);
  if (ep) $('#osd-now').textContent = `S${season.number}:E${ep.number}  ·  ${ep.name}`;
  if (episodes?.[index + 1]) $('#osd-next').textContent = `Next: E${episodes[index + 1].number}  ${episodes[index + 1].name}`;
  recordVod(0, 0);
  try {
    const { url } = await api(`/api/vod/link?${qs({ id: item.id, season: season?.id, episode: ep?.id, cat: item.cat, cmd: ep ? '' : item.cmd })}`);
    if (token !== playToken) return;
    attachSource(url, false);
    if (ctx.resumeAt) {
      $('#video').addEventListener('loadedmetadata', () => { $('#video').currentTime = ctx.resumeAt; }, { once: true });
    }
  } catch (e) {
    if (token === playToken) osdError(e.message);
  }
}

// Save position for resume + Continue Watching.
function recordVod(pos, dur) {
  const { item, season, episodes, index } = state.vod;
  const ep = episodes?.[index];
  const prev = progress[`${item.id}:${ep ? ep.id : 'movie'}`];
  if (!dur && prev) ({ pos, dur } = prev);
  progress[`${item.id}:${ep ? ep.id : 'movie'}`] = { pos, dur, at: Date.now() };
  const keys = Object.keys(progress);
  if (keys.length > 600) for (const k of keys.sort((a, b) => progress[a].at - progress[b].at).slice(0, 100)) delete progress[k];
  save('vodProgress', progress);
  addRecent({
    type: 'vod', id: item.id, item: snapshot(item), pos, dur,
    ...(ep && { seasonId: season.id, seasonNum: season.number, epId: ep.id, epNum: ep.number, epName: ep.name }),
  });
}

async function resumeVod(entry) {
  const p = progress[`${entry.id}:${entry.epId || 'movie'}`] || entry;
  if (!entry.epId) return playVod({ item: entry.item, resumeAt: resumable(p) });
  try {
    const eps = await api(`/api/vod/episodes?${qs({ id: entry.id, season: entry.seasonId, cat: entry.item.cat })}`);
    const index = Math.max(0, eps.findIndex(e => e.id === entry.epId));
    playVod({ item: entry.item, season: { id: entry.seasonId, number: entry.seasonNum }, episodes: eps, index, resumeAt: resumable(p) });
  } catch (e) { alert(`Could not resume: ${e.message}`); }
}

async function nextEpisode() {
  const ctx = state.vod;
  if (!ctx?.episodes) return false;
  if (ctx.episodes[ctx.index + 1]) { playVod({ ...ctx, index: ctx.index + 1, resumeAt: 0 }); return true; }
  try {
    const seasons = await api(`/api/vod/seasons?${qs({ id: ctx.item.id, cat: ctx.item.cat })}`);
    const nextSeason = seasons[seasons.findIndex(s => s.id === ctx.season.id) + 1];
    if (!nextSeason) return false;
    const eps = await api(`/api/vod/episodes?${qs({ id: ctx.item.id, season: nextSeason.id, cat: ctx.item.cat })}`);
    if (!eps.length) return false;
    playVod({ item: ctx.item, season: nextSeason, episodes: eps, index: 0 });
    return true;
  } catch { return false; }
}

const video = $('#video');
video.addEventListener('timeupdate', () => {
  if (state.mode !== 'vod' || !video.duration || !isFinite(video.duration)) return;
  $('#osd-bar').style.width = `${video.currentTime / video.duration * 100}%`;
  $('#osd-time').textContent = `${clock(video.currentTime)} / ${clock(video.duration)}`;
  if (Date.now() - lastSave > 5000) { lastSave = Date.now(); recordVod(video.currentTime, video.duration); }
});
video.addEventListener('ended', async () => {
  if (state.mode !== 'vod') return;
  recordVod(video.duration, video.duration);
  if (!(await nextEpisode())) {
    const { item, episodes } = state.vod;
    closePlayer();
    if (!episodes) removeRecent(item.id); // a finished movie leaves Continue Watching
  }
});
// Show the info when the picture actually starts, then let it fade.
video.addEventListener('playing', showOsd);
video.addEventListener('click', () => (state.mode === 'vod' ? togglePause() : showOsd()));

$('#osd-progress').addEventListener('click', e => {
  if (state.mode !== 'vod' || !video.duration) return;
  const r = e.currentTarget.getBoundingClientRect();
  video.currentTime = (e.clientX - r.left) / r.width * video.duration;
});

function closePlayer() {
  if (state.mode === 'vod' && video.duration && isFinite(video.duration)) recordVod(video.currentTime, video.duration);
  playToken++;
  clearInterval(osdTick);
  stopPlayback();
  $('#player').hidden = true;
  refreshPersonal();
}

function zap(step) {
  const n = state.playList.length;
  if (n) play(state.playList, (state.playIndex + step + n) % n);
}

$('#back').onclick = closePlayer;
$('#player').addEventListener('mousemove', showOsd);

// ---- Keyboard / remote navigation ----

const FOCUSABLE = '#topbar a, .hero-actions .btn, .see-all, .chip, .tile, .poster, .ep, #detail-close';
const DIRS = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };

function moveFocus([dx, dy]) {
  const scope = $('#detail').hidden ? document : $('#detail');
  const cur = document.activeElement;
  const els = [...scope.querySelectorAll(FOCUSABLE)].filter(e => e.offsetParent && !e.closest('[hidden]'));
  if (!els.includes(cur)) return focusEl(scope.querySelector('.hero-actions .btn') || els[0]);
  const a = cur.getBoundingClientRect();
  const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bestScore = Infinity;
  for (const e of els) {
    if (e === cur) continue;
    const b = e.getBoundingClientRect();
    const ox = b.left + b.width / 2 - ax, oy = b.top + b.height / 2 - ay;
    const along = ox * dx + oy * dy;
    if (along <= 4) continue;
    const across = Math.abs(dx ? oy : ox);
    const score = along + across * (dx ? 4 : 0.6);
    if (score < bestScore) { bestScore = score; best = e; }
  }
  if (best) focusEl(best);
}

function focusEl(e) {
  if (!e) return;
  e.focus({ preventScroll: true });
  e.scrollIntoView({ block: e.closest('#hero, #topbar') ? 'nearest' : 'center', inline: 'nearest', behavior: 'smooth' });
  if (e.closest('#hero')) window.scrollTo({ top: 0, behavior: 'smooth' });
}

document.addEventListener('keydown', e => {
  if (!$('#player').hidden) {
    const vod = state.mode === 'vod';
    if (e.key === 'Escape' || e.key === 'Backspace') closePlayer();
    else if (!vod && (e.key === 'ArrowUp' || e.key === 'PageUp')) zap(-1);
    else if (!vod && (e.key === 'ArrowDown' || e.key === 'PageDown')) zap(1);
    else if (vod && e.key === 'ArrowLeft') { video.currentTime = Math.max(0, video.currentTime - 10); showOsd(); }
    else if (vod && e.key === 'ArrowRight') { video.currentTime += 10; showOsd(); }
    else if (vod && (e.key === ' ' || e.key === 'Enter')) togglePause();
    else if (vod && (e.key === 'n' || e.key === 'N')) nextEpisode();
    else if (e.key === 'i' || e.key === 'Enter' || e.key === ' ' || e.key.startsWith('Arrow')) showOsd();
    else return;
    e.preventDefault();
    return;
  }

  const inSearch = document.activeElement === $('#search');
  if (inSearch) {
    if (e.key === 'ArrowDown' || e.key === 'Enter') { e.preventDefault(); $('#search').blur(); moveFocus([0, 1]); }
    else if (e.key === 'Escape') { $('#search').value = ''; $('#search').blur(); showView(state.prevView); }
    return;
  }

  if (!$('#detail').hidden && (e.key === 'Escape' || e.key === 'Backspace')) { e.preventDefault(); closeDetail(); return; }
  if (e.key === '/') { e.preventDefault(); $('#search').focus(); }
  else if (DIRS[e.key]) { e.preventDefault(); moveFocus(DIRS[e.key]); }
  else if (e.key === 'f' || e.key === 'F') {
    const a = document.activeElement;
    const data = a?.closest('.tile-wrap')?.dataset || a?.dataset || {};
    if (data.id) toggleFav(data.id);
    else if (data.vod) {
      const item = vodList.find(v => v.id === data.vod) || a.vodItem;
      if (item) toggleVodList(item);
    }
  }
  else if ((e.key === 'Escape' || e.key === 'Backspace') && state.view !== 'home') { e.preventDefault(); goBack(); }
});

init();
