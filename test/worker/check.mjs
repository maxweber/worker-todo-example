// The app inside workerd: `npm run test:worker` builds both bundles and
// runs this against `wrangler dev`, with a storage of its own. No network.
// It reads the frame stream of a list as a browser does, a POST held open,
// and posts commands next to the page.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import http from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import transit from 'transit-js';

// BASE=https://... runs the checks against a deployed Worker instead
const remote = process.env.BASE;
const port = 8798;
const base = remote || `http://127.0.0.1:${port}`;
const lib = base.startsWith('https') ? await import('node:https') : http;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const writer = transit.writer('json');
const reader = transit.reader('json');
const kw = transit.keyword;

// transit data as plain JSON text, keywords as ":name", to match against
const plain = (x) => {
  if (Array.isArray(x)) return x.map(plain);
  if (transit.isKeyword(x)) return x.toString();
  // a seq, what `for` answers, travels as a list
  if (transit.isTaggedValue(x)) return plain(x.rep);
  if (transit.isMap(x)) {
    const out = {};
    x.forEach((v, k) => { out[plain(k)] = plain(v); });
    return out;
  }
  return x;
};

// a tab on the list at `page`: its frame stream, read as it comes
function open(page, tab, acceptEncoding = 'gzip, deflate, br') {
  const state = { frames: [], wire: 0, text: '', ended: false, headers: null, status: null };
  let decoder = null;
  const feed = (text) => {
    state.text += text;
    for (;;) {
      const end = state.text.indexOf('\n\n');
      if (end < 0) return;
      const block = state.text.slice(0, end);
      state.text = state.text.slice(end + 2);
      const data = block.split('\n').filter((line) => line.startsWith('data: ')).map((line) => line.slice(6)).join('\n');
      if (block.startsWith('event: frame')) state.frames.push(JSON.stringify(plain(reader.read(data))));
    }
  };
  const request = lib.request(`${base}${page}`, { method: 'POST', headers: { 'Accept-Encoding': acceptEncoding } }, (response) => {
    state.headers = response.headers;
    state.status = response.statusCode;
    const encoding = response.headers['content-encoding'];
    if (encoding === 'br') decoder = zlib.createBrotliDecompress();
    if (encoding === 'gzip') decoder = zlib.createGunzip();
    if (decoder) decoder.on('data', (chunk) => feed(chunk.toString('utf8')));
    response.on('data', (chunk) => {
      state.wire += chunk.length;
      if (decoder) decoder.write(chunk); else feed(chunk.toString('utf8'));
    });
    response.on('close', () => { state.ended = true; });
  });
  request.on('error', () => { state.ended = true; });
  request.end(writer.write(transit.map([kw('tabid'), tab])));
  state.close = () => request.destroy();
  state.last = () => state.frames.at(-1);
  // the first frame from `from` on that matches, or a failure after `wait` ms
  state.frame = async (pattern, from = 0, wait = 3000) => {
    for (let i = 0; i < wait / 5; i++) {
      const found = state.frames.slice(from).find((frame) => pattern.test(frame));
      if (found) return found;
      await sleep(5);
    }
    assert.fail(`tab ${tab}: no frame matched ${pattern}; the last one:\n${state.last()}`);
  };
  return state;
}

async function command(page, kind, data) {
  const pairs = [kw('command/kind'), kw(kind)];
  if (data) pairs.push(kw('command/data'), transit.map(Object.entries(data).flatMap(([k, v]) => [kw(k), v])));
  const response = await fetch(`${base}${page}command`, { method: 'POST', body: writer.write(transit.map(pairs)) });
  assert.equal(response.status, 200);
  return plain(reader.read(await response.text()));
}

const wokeAt = (frame) => frame.match(/It woke at ",\[":code","([^"]+)"\]/)[1];
// the id of the todo with `text`: the key of the list item that holds it
const todoId = (frame, text) => {
  let id = null;
  const walk = (x) => {
    if (!Array.isArray(x)) return;
    if (x[0] === ':li' && JSON.stringify(x).includes(`[":span",${JSON.stringify(text)}]`)) id = x[1][':replicant/key'];
    x.forEach(walk);
  };
  walk(JSON.parse(frame));
  assert.ok(id, `no todo "${text}" in the frame`);
  return id;
};

async function checks() {
  const step = (name) => console.log(`  ${name}`);

  step('GET / makes a list of one\'s own');
  const start = await fetch(`${base}/`, { redirect: 'manual' });
  assert.equal(start.status, 302);
  const page = start.headers.get('location');
  assert.match(page, /^\/l\/[0-9a-f-]{36}\/$/);
  const noSlash = await fetch(`${base}${page.slice(0, -1)}`, { redirect: 'manual' });
  assert.equal(noSlash.status, 302);
  assert.equal(noSlash.headers.get('location'), page);

  step('the shell of the page and its assets');
  const shell = await fetch(`${base}${page}`);
  assert.equal(shell.status, 200);
  const html = await shell.text();
  assert.match(html, /data-rframes-view/);
  assert.match(html, /src="\/js\/main\.js"/);
  assert.equal((await fetch(`${base}/js/main.js`)).status, 200);
  assert.equal((await fetch(`${base}/app.css`)).status, 200);
  assert.equal((await fetch(`${base}/l/not-a-list/`)).status, 404);
  assert.equal((await fetch(`${base}${page}nothing`)).status, 404);

  step('a tab gets the empty list as its first frame, compressed with brotli');
  const anna = open(page, 'anna');
  const first = await anna.frame(/Nothing to do yet/);
  assert.equal(anna.status, 200);
  assert.equal(anna.headers['content-encoding'], 'br');
  assert.match(first, /\[":strong",1\]," tab is"/);
  assert.match(first, /\[":strong",0\]," events\."/);

  step('a command goes through the event store, the frame shows the todo');
  assert.deepEqual(await command(page, 'todo/add', { text: 'Buy milk' }), { ':success?': true });
  const added = await anna.frame(/\[":span","Buy milk"\]/);
  assert.match(added, /"added ",\[":q","Buy milk"\]/);
  assert.match(added, /\[":strong",1\]," event\."/);
  assert.deepEqual(await command(page, 'todo/add', { text: 'Write the README' }), { ':success?': true });
  await anna.frame(/\[":span","Write the README"\]/);

  step('a second tab shares the list, and both show two tabs');
  const ben = open(page, 'ben', 'gzip');
  const bens = await ben.frame(/\[":span","Buy milk"\]/);
  assert.equal(ben.headers['content-encoding'], 'gzip');
  assert.match(bens, /\[":strong",2\]," tabs are"/);
  await anna.frame(/\[":strong",2\]," tabs are"/);

  step('a tick completes a todo for everyone, and costs a few bytes');
  await sleep(200);
  anna.wire = 0;
  const milk = todoId(anna.last(), 'Buy milk');
  assert.deepEqual(await command(page, 'todo/toggle', { id: milk, 'done?': true }), { ':success?': true });
  const ticked = /":class":"done"\},\[":label",\[":input",\{[^}]*":checked":true/;
  await anna.frame(ticked);
  const bensTicked = await ben.frame(ticked);
  assert.match(bensTicked, /"completed ",\[":q","Buy milk"\]/);
  assert.match(bensTicked, /\[":p\.count",1," of ",2," done"\]/);
  await sleep(100);
  assert.ok(anna.wire < 300, `the frame of one tick took ${anna.wire} bytes`);

  step('a rejected command says why and changes nothing');
  const count = anna.frames.length;
  assert.deepEqual(await command(page, 'todo/add', { text: '   ' }),
                   { ':success?': false, ':result': { ':error': 'A todo needs a text.' } });
  assert.deepEqual(await command(page, 'todo/unknown'), { ':error': ':unknown-command-kind' });
  await sleep(300);
  assert.equal(anna.frames.length, count, 'a page a command left as it was gets no frame');

  step('the log of the list, for a consumer');
  const log = await fetch(`${base}${page}events`);
  assert.equal(log.status, 200);
  const events = plain(reader.read(await log.text()));
  assert.deepEqual(events.map((e) => [e[':event/number'], e[':event/type']]),
                   [[1, ':todo/added'], [2, ':todo/added'], [3, ':todo/completed']]);
  const after = plain(reader.read(await (await fetch(`${base}${page}events?after=2`)).text()));
  assert.deepEqual(after.map((e) => e[':event/number']), [3]);

  step('another list knows nothing of this one');
  const other = (await fetch(`${base}/`, { redirect: 'manual' })).headers.get('location');
  assert.notEqual(other, page);
  const carl = open(other, 'carl');
  assert.match(await carl.frame(/Nothing to do yet/), /\[":strong",1\]," tab is"/);
  carl.close();

  // The relay learns that a tab is gone when it writes a heartbeat, every
  // 10 s here; `wrangler dev` needs two of them.
  step('a tab that leaves is counted out (up to 25 s) ...');
  const beforeLeave = anna.frames.length;
  ben.close();
  await anna.frame(/\[":strong",1\]," tab is"/, beforeLeave, 25000);

  step('the list\'s Object sleeps after a while, the stream stays open (25 s) ...');
  const before = wokeAt(anna.last());
  const frames = anna.frames.length;
  await sleep(25000);
  assert.equal(anna.ended, false);
  const readme = todoId(anna.last(), 'Write the README');
  assert.deepEqual(await command(page, 'todo/remove', { id: readme }), { ':success?': true });
  const woken = await anna.frame(/"removed ",\[":q","Write the README"\]/, frames);
  assert.doesNotMatch(woken, /\[":span","Write the README"\]/);
  assert.match(woken, /\[":span","Buy milk"\]/, 'the list came back from its log');
  assert.match(woken, /\[":strong",4\]," events\."/);
  assert.notEqual(wokeAt(woken), before, 'the Object did not sleep');

  anna.close();
  return page;
}

let wrangler = null;
let storage = null;
let log = '';
if (!remote) {
  storage = mkdtempSync(path.join(tmpdir(), 'worker-todo-example-'));
  wrangler = spawn('node_modules/.bin/wrangler', ['dev', '--port', String(port), '--persist-to', storage],
                   { env: { ...process.env, WRANGLER_SEND_METRICS: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] });
  wrangler.stdout.on('data', (chunk) => { log += chunk; });
  wrangler.stderr.on('data', (chunk) => { log += chunk; });
}

let failed = false;
try {
  if (wrangler) {
    for (let i = 0; i < 120 && !log.includes('Ready on'); i++) await sleep(250);
    assert.ok(log.includes('Ready on'), `wrangler dev did not start:\n${log}`);
  }
  console.log(remote ? `the app at ${remote}` : 'the app inside workerd');
  const page = await checks();
  console.log(`all checks passed${remote ? `, the list they left: ${remote}${page}` : ''}`);
} catch (error) {
  failed = true;
  console.error(error);
  console.error(log.split('\n').filter((line) => /error|exception/i.test(line)).slice(-10).join('\n'));
} finally {
  if (wrangler) {
    wrangler.kill('SIGTERM');
    await sleep(500);
    rmSync(storage, { recursive: true, force: true });
  }
}
process.exit(failed ? 1 : 0);
