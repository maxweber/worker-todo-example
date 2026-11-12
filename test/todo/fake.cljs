(ns todo.fake
  "Stand-ins for what a Durable Object gives the page, for the tests
   under Node: its storage on node:sqlite, as worker-event-sourcing's
   own tests have it, and the sockets it accepted."
  (:require ["node:sqlite" :as sqlite]))

(defn storage
  "`sql.exec` and `transactionSync`, which is all the event store uses."
  []
  (let [^js db (new (.-DatabaseSync sqlite) ":memory:")]
    #js {:sql #js {:exec (fn [statement & params]
                           (let [^js prepared (.prepare db statement)
                                 rows (.apply (.-all prepared) prepared (to-array params))]
                             #js {:toArray (fn [] rows)}))}
         :transactionSync (fn [f]
                            (.exec db "begin")
                            (try
                              (let [result (f)]
                                (.exec db "commit")
                                result)
                              (catch :default e
                                (.exec db "rollback")
                                (throw e))))}))

(defn ctx
  "The state of a list's Object: a storage, and `tabs` open sockets."
  [tabs]
  #js {:storage (storage)
       :getWebSockets (fn [] (to-array (repeat tabs #js {})))})
