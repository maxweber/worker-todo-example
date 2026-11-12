(ns todo.worker
  "The Worker's module: the fetch handler, its default export, and the
   Durable Object class of a list. vite.worker.config.mjs exports both,
   wrangler.jsonc names the class.

   A list is one Object, found by the id in its address. The Object is
   two things at once: the stream of worker-event-sourcing, which keeps
   the list's log and read model, and the page of rframes, which
   renders the list for every tab. The Worker in front of it serves the
   shell, relays the frames and hands the commands on."
  (:require [cljf.x :refer [defclass]]
            [rframes.worker :as worker]
            [rframes.worker.durable :as durable]
            [rframes.worker.object :as object]
            [todo.domain :as domain]
            [todo.page :as page]
            [worker-event-sourcing.stream :as stream]))

(defclass TodoList
  (extends durable/durable-object)
  (constructor [this ctx env]
    (super ctx env)
    ;; the log's tables, and the read model at the domain's version
    (stream/init (.-storage ctx) domain/domain)
    (set! (.-page this)
          (durable/page (let [woke-at (.toISOString (js/Date.))]
                          {:get-register (fn [] page/register)
                           :world (fn [] {:woke-at woke-at})})
                        ctx env)))
  Object
  ;; what the Worker hands over: a WebSocket per tab, and the commands
  (fetch [this request]
    (object/fetch (.-page this) request))
  (webSocketClose [this socket code reason clean]
    (object/closed! (.-page this) socket)
    (object/schedule-refresh! (.-page this)))
  (webSocketError [this socket error]
    (object/closed! (.-page this) socket)
    (object/schedule-refresh! (.-page this)))
  ;; the list's log for a consumer: up to a page of events after a number, transit
  (events [this after]
    (stream/events (.-storage (.-ctx this)) after)))

(def ^:private list-address
  "/l/<id>, with what follows the id."
  #"/l/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(/.*)?")

(defn- list-of
  "`[id rest]` of the list a path belongs to, nil for any other path."
  [uri]
  (when-let [[_ id rest] (re-matches list-address uri)]
    [id rest]))

(defn- object
  "The Object of a request: the list its path names. The id is the whole
   secret of a list here, whoever has the address has the list. An app
   with accounts takes the Object from the session instead."
  [{:keys [worker/env ring/request]}]
  (when-let [[id _] (list-of (:uri request))]
    (let [^js lists (.-LIST ^js env)]
      (.get lists (.idFromName lists id)))))

(defn- connection
  "What the list's Object learns about a tab: where it reaches Cloudflare."
  [{:keys [worker/request]}]
  {:visitor/colo (some-> ^js request .-cf .-colo)})

(defn- redirect
  [location]
  {:status 302
   :headers {"Location" location
             "Cache-Control" "no-store"}
   :body ""})

(defn- new-list
  "GET /: a list of one's own, at an address nobody can guess."
  [w]
  (assoc w :ring/response (redirect (str "/l/" (random-uuid) "/"))))

(defn- ^:async list-route
  "GET under /l/: the log of a list at /l/<id>/events, the page itself
   left to the shim at /l/<id>/, and nothing anywhere else."
  [w]
  (let [uri (:uri (:ring/request w))
        [id rest] (list-of uri)]
    (cond
      (nil? id)
      (assoc w :ring/response {:status 404
                               :headers {"Content-Type" "text/plain"}
                               :body "no such list"})

      ;; the page posts to `command` next to it, so it needs its slash
      (nil? rest)
      (assoc w :ring/response (redirect (str uri "/")))

      (= "/" rest)
      w

      (= "/events" rest)
      (let [after (js/Number (or (second (re-find #"(?:^|&)after=(\d+)" (str (:query-string (:ring/request w)))))
                                 0))]
        (assoc w :ring/response {:status 200
                                 ;; transit is JSON, and a browser shows JSON
                                 :headers {"Content-Type" "application/json"
                                           "Cache-Control" "no-store"}
                                 :body (await (.events ^js (object w) after))}))

      :else
      (assoc w :ring/response {:status 404
                               :headers {"Content-Type" "text/plain"}
                               :body "not found"}))))

(def register
  "The Worker's register: its own routes, then what it shares with the
   Object."
  (concat [{:ring/route [:get "/"]
            :ring/handler #'new-list}
           {:ring/route [:get "/l/*"]
            :ring/handler #'list-route}]
          page/register))

(def handler
  "The module's default export: what the Worker runtime calls."
  #js {:fetch (worker/fetch-handler {:get-register (fn [] register)
                                     :object object
                                     :connection connection
                                     ;; A browser that closes a tab is noticed
                                     ;; when the next heartbeat is written. The
                                     ;; page shows how many tabs are connected,
                                     ;; so it wants that sooner than the 25 s
                                     ;; the stream itself needs.
                                     :heartbeat-ms 10000})})
