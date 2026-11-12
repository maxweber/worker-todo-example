(ns todo.page
  "The page of a todo list: the view and the commands, which run in the
   list's Durable Object. The view is a function of what the Object's
   SQLite holds, the commands go through the event store. rframes
   renders the open pages again after every command, so a command
   function only has to execute.

   Nothing here needs a Worker to run: the tests give it a storage of
   node:sqlite."
  (:require [rframes.worker.command :as command]
            [rframes.worker.object :as object]
            [todo.domain :as domain]
            [worker-event-sourcing.store :as store]))

(defn- storage
  "The storage of the Object a view or a command runs in."
  [w]
  (.-storage ^js (:object/ctx w)))

(def history-length
  "How many of the last events the page shows."
  5)

(defn- history
  "The last events of the list's log, the newest first."
  [^js storage]
  (let [last-number (or (:n (first (store/select (.-sql storage) "select max(number) as n from events")))
                        0)]
    {:events last-number
     :history (reverse (store/events-after storage (max 0 (- last-number history-length)) history-length))}))

(defn state
  "What the view shows, gathered from the Object: the todos of the read
   model, the end of the log, and what the world knows about this
   Object and this tab."
  [w]
  (merge (history (storage w))
         {:todos ((get domain/queries "todos") (.-sql (storage w)))
          :tabs (object/connections (:object/page w))
          :woke-at (:woke-at w)
          :colo (:visitor/colo w)}))

(defn- said
  "One event of the log in words."
  [{:keys [event/type todo/text event/number]}]
  [:li {:replicant/key number}
   [:span.number number]
   (case type
     :todo/added "added "
     :todo/completed "completed "
     :todo/reopened "reopened "
     :todo/removed "removed "
     (str (name type) " "))
   [:q text]])

(defn- todo-item
  [{:keys [id text done]}]
  (let [done? (= 1 done)]
    [:li {:replicant/key id
          :class (when done? "done")}
     [:label
      [:input {:type "checkbox"
               :checked done?
               :on {:change [[:data/command {:command/kind :todo/toggle
                                             :command/data {:id id
                                                            :done? (not done?)}}]]}}]
      [:span text]]
     [:button.remove {:title "Remove"
                      :aria-label (str "Remove " text)
                      :on {:click [[:data/command {:command/kind :todo/remove
                                                   :command/data {:id id}}]]}}
      "×"]]))

(def ^:private add
  "What the Add button and the Enter key do: the draft, which the browser
   holds, becomes a todo, and the draft goes when the list took it."
  [:data/command
   {:command/kind :todo/add
    :command/data {:text [:store/deref :draft]}}
   {:on-success [[:store/dissoc :draft]]}])

(defn view
  "The page as hiccup, a pure function of `state`'s answer."
  [{:keys [todos events history tabs woke-at colo]}]
  (let [done (count (filter #(= 1 (:done %)) todos))]
    [:main
     [:h1 "Todos"]
     [:div.add
      ;; The draft is client state: a frame from the list does not reset it.
      [:ui/input {:ui/store-key :draft
                  :placeholder "What needs to be done?"
                  :maxlength domain/text-limit
                  :autofocus true
                  :on {:keydown [[:js/on-enter] add]}}]
      [:button {:on {:click [add]}} "Add"]]
     (if (seq todos)
       [:ul.todos
        (for [todo todos]
          (todo-item todo))]
       [:p.empty "Nothing to do yet."])
     (when (seq todos)
       [:p.count done " of " (count todos) " done"])
     [:section.share
      [:h2 "Shared by its link"]
      [:p "Everyone who opens this address works on the same list. Open it "
       "in a second tab, or on your phone, and tick something."]]
     [:section.behind
      [:h2 "Behind the page"]
      [:ul
       [:li [:strong tabs] (if (= 1 tabs) " tab is" " tabs are") " connected to this list."]
       (when colo
         [:li "Your tab reaches Cloudflare at " [:code colo] "."])
       [:li "The list is one Durable Object. It woke at " [:code woke-at] ". "
        "Leave the list alone for half a minute and it sleeps. The page "
        "stays connected, and the next change wakes it."]
       [:li "Its log has " [:strong events] (if (= 1 events) " event." " events.")
        (when (pos? events)
          [:span " The todos above are derived from it. "
           [:a {:href "events" :target "_blank"} "Read the log"] "."])]]
      (when (seq history)
        [:ol.history
         (for [event history]
           (said event))])]]))

(defn page-view
  [w]
  (view (state w)))

(defn- execute
  "A command of the domain executed against the list's log. A rejection
   carries the domain's reason to the browser."
  [w domain-command]
  (let [answer (store/execute! (storage w) domain/domain domain-command)]
    (if (contains? answer :error)
      (command/rejected w (:error answer))
      (command/accepted w))))

(defn- add-todo
  [w]
  (execute w {:command/type :todo/add
              :todo/text (:text (command/command-data w))}))

(defn- toggle-todo
  [w]
  (let [{:keys [id done?]} (command/command-data w)]
    (execute w {:command/type (if done? :todo/complete :todo/reopen)
                :todo/id id})))

(defn- remove-todo
  [w]
  (execute w {:command/type :todo/remove
              :todo/id (:id (command/command-data w))}))

(defn tab-changed!
  "A tab came or went: the pages show how many are connected."
  [w]
  (object/schedule-refresh! (:object/page w)))

(def head
  "The head of the page's shell. A list is found by its address alone, so
   search engines are asked to leave the pages out."
  (str "<title>Todos</title>"
       "<meta name=\"robots\" content=\"noindex\">"
       "<link rel=\"icon\" href=\"data:image/svg+xml,"
       "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E"
       "%3Crect width='16' height='16' rx='3' fill='%232f6fed'/%3E"
       "%3Cpath d='M4 8.5l2.5 2.5L12 5.5' fill='none' stroke='white' stroke-width='2'/%3E"
       "%3C/svg%3E\">"
       "<link rel=\"stylesheet\" href=\"/app.css\">"
       "<script type=\"module\" src=\"/js/main.js\"></script>"))

(def register
  "What the Object and the Worker both read: the page of a list under
   /l/<id>/ and its commands."
  [{:replicant/shim "/l/*"
    :head head}
   {:replicant/render "/l/*"
    :render/fn #'page-view
    :open/fn #'tab-changed!}
   {:command/kind :todo/add
    :command/fn #'add-todo}
   {:command/kind :todo/toggle
    :command/fn #'toggle-todo}
   {:command/kind :todo/remove
    :command/fn #'remove-todo}])
