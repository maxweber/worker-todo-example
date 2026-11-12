(ns todo.domain
  "A todo list as an event-sourced domain: commands decided against the
   read model, events folded into it as statements. One list is one
   stream, one Durable Object. worker-event-sourcing keeps the log and
   runs this; nothing here knows the page."
  (:require [clojure.string :as str]
            [worker-event-sourcing.store :refer [select]]))

(def version
  "A new UUID, as a string, committed with the change, whenever
   `tables` or `project` change: a list built under another version
   derives its read model again at its first use."
  "1ae20850-6f50-494a-85bd-665435eeec92")

(def tables
  {"todos" "create table if not exists todos (id text primary key, text text not null, done integer not null default 0)"})

(def text-limit
  "How long a todo may be, in characters."
  200)

(def list-limit
  "How many todos a list holds. The example is open to everyone with a
   link, so a list has an end."
  100)

(defn- todo
  [db id]
  (first (select db "select id, text, done from todos where id = ?" (str id))))

(defn decide
  "The events of a command, or `{:error ...}`. Reads the read model
   through `db`, writes nothing. An event carries the todo's text even
   where the id would do, so the log reads without the read model."
  [db command]
  (case (:command/type command)
    :todo/add
    (let [text (str/trim (str (:todo/text command)))]
      (cond
        (str/blank? text)
        {:error "A todo needs a text."}

        (< text-limit (count text))
        {:error (str "A todo has at most " text-limit " characters.")}

        (<= list-limit (:n (first (select db "select count(*) as n from todos"))))
        {:error (str "A list holds at most " list-limit " todos.")}

        :else
        {:events [{:event/type :todo/added
                   :todo/id (str (random-uuid))
                   :todo/text text}]}))

    :todo/complete
    (let [{:keys [id text done]} (todo db (:todo/id command))]
      (cond
        (nil? id) {:error "No such todo."}
        (= 1 done) {:events []}
        :else {:events [{:event/type :todo/completed
                         :todo/id id
                         :todo/text text}]}))

    :todo/reopen
    (let [{:keys [id text done]} (todo db (:todo/id command))]
      (cond
        (nil? id) {:error "No such todo."}
        (= 0 done) {:events []}
        :else {:events [{:event/type :todo/reopened
                         :todo/id id
                         :todo/text text}]}))

    :todo/remove
    (let [{:keys [id text]} (todo db (:todo/id command))]
      (if (nil? id)
        {:events []}
        {:events [{:event/type :todo/removed
                   :todo/id id
                   :todo/text text}]}))

    {:error (str "Unknown command " (pr-str (:command/type command)))}))

(defn project
  "The statements that fold one event into the read model, each SQL text
   and its parameters: a function of the event alone. It sees every
   event ever written, so an old shape is handled here; an unknown type
   throws."
  [event]
  (case (:event/type event)
    :todo/added [["insert into todos (id, text) values (?, ?)" (:todo/id event) (:todo/text event)]]
    :todo/completed [["update todos set done = 1 where id = ?" (:todo/id event)]]
    :todo/reopened [["update todos set done = 0 where id = ?" (:todo/id event)]]
    :todo/removed [["delete from todos where id = ?" (:todo/id event)]]))

(def queries
  "The read model's queries, by name."
  {"todos" (fn [db] (select db "select id, text, done from todos order by rowid"))})

(def domain
  {:version version
   :tables tables
   :decide decide
   :project project
   :queries queries})
