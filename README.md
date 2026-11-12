# worker-todo-example

A todo list on a Cloudflare Worker. It is a proof of concept for two
libraries used together:

- [worker-event-sourcing](https://github.com/maxweber/worker-event-sourcing)
  keeps a list's state, an event log and a read model in the SQLite of a
  Durable Object.
- [rframes](https://github.com/simplemono/rframes), with its Worker
  module, renders the page in that same Object and pushes it to every
  open tab.

A deployment to try: https://worker-todo-example.storrito.workers.dev

Mostly written by Claude Code.

## Try it

1. Open the address. You get a list of your own at `/l/<id>/`.
2. Add a few todos. Open the same address in a second tab, or on a
   phone, and tick one there.
3. Read "Behind the page". It says how many tabs are connected, when the
   list's Object woke and how long its log is.
4. Leave the list alone for half a minute, then change something. The
   wake time is new and the todos are the same. The Object slept, the
   page stayed connected, and the list came back from its log.
5. Follow "Read the log", or open `/l/<id>/events`.

## How it works

One list is one Durable Object, found by the id in its address.

```
 browser                    Worker                       Durable Object of the list
                        +-----------------+          +--------------------------+
 GET /l/<id>/   ------> | the page's shell|          |                          |
 POST /l/<id>/  ------> | relay           | -------> | a WebSocket per tab      |
 frame stream   <------ | SSE, brotli     | <------- | view -> frame per change |
 POST .../command ----> | hands it on     | -------> | decide -> events ->      |
                        +-----------------+          | read model (SQLite)      |
                          keeps no state             +--------------------------+
```

A click takes this way:

1. The browser posts a command, `{:command/kind :todo/toggle ...}`, to
   `command` next to the page.
2. The Worker finds the list's Object by the id in the path and hands
   the command over.
3. In the Object the command function turns it into a command of the
   domain and executes it. `decide` reads the read model and answers
   events. The store appends them to the log and folds them into the
   `todos` table, in one transaction.
4. rframes renders the page again for every connected tab, from the
   read model, and sends each tab its frame over a WebSocket to the
   Worker.
5. The Worker writes the frame into the stream the tab holds open. The
   stream is compressed as one, so a ticked box costs a few dozen bytes.
6. The browser diffs the frame into the page with Replicant.

Nothing in the browser knows what a todo is. Its bundle is the client of
rframes and one component, a text input that keeps its text in the
browser.

Between two changes the Object sleeps. Its memory goes, the WebSockets
stay, and Cloudflare bills no duration for it. The next command runs the
constructor again, and the list is what its log says.

## The code

| file | what |
| --- | --- |
| `src/todo/domain.cljs` | the domain: commands decided into events, events projected into the `todos` table, the queries |
| `src/todo/page.cljs` | the page: the view and the command functions, which run in the Object |
| `src/todo/worker.cljs` | the Object's class, and the Worker's handler with its routes |
| `src/todo/client.cljs` | the browser bundle: rframes' client and the text input |
| `test/todo/` | the domain and the page under Node, on a storage of `node:sqlite` |
| `test/worker/check.mjs` | the built app inside workerd, or against a deployment |

The Object is two things at once. `worker.cljs` defines one class whose
constructor sets up the event store and the page, with `fetch` for
rframes and `events` for a reader of the log.

## Run it

Node 24 or newer, and a JVM for the compiler.

```sh
npm ci
npm run dev       # builds both bundles, then http://127.0.0.1:8787
npm test          # the unit tests, then the app inside workerd; no network
```

`npm test` takes about a minute. Most of it is waiting: for a closed tab
to be noticed, and for the Object to fall asleep.

## Deploy

```sh
npx wrangler login    # once
npm run deploy        # builds, then wrangler deploy
```

The Worker goes to `worker-todo-example.<account>.workers.dev`.
`wrangler.jsonc` names the Durable Object class, the static assets and
the two compatibility flags. To run the checks against a deployment:

```sh
BASE=https://worker-todo-example.<account>.workers.dev node test/worker/check.mjs
```

## Measured on the deployment

Chromium over HTTP/3, the Worker in Düsseldorf, 2026-11-12:

- Typing a todo and pressing Enter until it showed took 125 to 219 ms.
- The first frame of a new list took 601 bytes on the wire. The frames
  after a change took 23 to 374 bytes while the page grew to 3 kB.
- A heartbeat of the stream took 16 bytes.
- A closed tab was counted out after 10 s, at the next heartbeat.

## What the example leaves out

- Accounts. The id in the address is the whole secret of a list.
  Whoever has the address has the list. An app with accounts takes the
  Object from the session in `:object` and checks the role in every
  command function.
- Retries. The commands carry no `:command/id`, so a command sent twice
  is decided twice.
- Readers of the log. `/l/<id>/events` hands it out, nothing consumes it.
- A limit on lists. A list holds at most 100 todos of 200 characters,
  but anyone can make another list.

## License

MIT
