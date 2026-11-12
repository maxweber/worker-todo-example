(ns todo.page-test
  (:require [clojure.test :refer [async deftest is testing]]
            [rframes.worker.command :as command]
            [rframes.worker.object :as object]
            [rframes.worker.transit :as transit]
            [todo.domain :as domain]
            [todo.fake :as fake]
            [todo.page :as page]
            [worker-event-sourcing.store :as store]))

(defn- world
  "The world of a view or a command in a list's Object with `tabs` tabs."
  [tabs]
  (let [^js ctx (fake/ctx tabs)
        _ (store/schema! (.-storage ctx) domain/domain)
        list-page (object/create {:ctx ctx
                                  :get-register (fn [] page/register)
                                  :world (fn [] {:woke-at "2026-11-12T10:00:00.000Z"})})]
    {:system/get-register (fn [] page/register)
     :object/page list-page
     :object/ctx ctx
     :woke-at "2026-11-12T10:00:00.000Z"
     :visitor/colo "DUS"}))

(defn- run
  [w kind data]
  (command/run w {:command/kind kind :command/data data}))

(defn- texts
  "Every string in a view, in order."
  [view]
  (filter string? (tree-seq coll? seq view)))

(deftest commands-and-state-test
  (async done
    (-> ((^:async fn []
           (let [w (world 2)]
             (testing "a new list"
               (is (= {:events 0 :history [] :todos [] :tabs 2 :woke-at "2026-11-12T10:00:00.000Z" :colo "DUS"}
                      (page/state w))))
             (is (= {:success? true} (await (run w :todo/add {:text "Buy milk"}))))
             (is (= {:success? true} (await (run w :todo/add {:text "Write the README"}))))
             (let [[milk readme] (:todos (page/state w))]
               (is (= ["Buy milk" "Write the README"] [(:text milk) (:text readme)]))
               (testing "a tick completes, a second one reopens"
                 (await (run w :todo/toggle {:id (:id milk) :done? true}))
                 (is (= [1 0] (map :done (:todos (page/state w)))))
                 (await (run w :todo/toggle {:id (:id milk) :done? false}))
                 (is (= [0 0] (map :done (:todos (page/state w))))))
               (await (run w :todo/remove {:id (:id readme)})))
             (testing "the domain's reason reaches the browser"
               (is (= {:success? false :result {:error "A todo needs a text."}}
                      (await (run w :todo/add {:text "  "})))))
             (let [{:keys [events history todos]} (page/state w)]
               (testing "the page shows the end of the log, the newest first"
                 (is (= 5 events))
                 (is (= [[5 :todo/removed] [4 :todo/reopened] [3 :todo/completed] [2 :todo/added] [1 :todo/added]]
                        (map (juxt :event/number :event/type) history)))
                 (is (= ["Buy milk"] (map :text todos)))))
             (await (run w :todo/add {:text "A sixth event"}))
             (testing "only the last five"
               (is (= [6 5 4 3 2] (map :event/number (:history (page/state w)))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest view-test
  (let [view (page/view {:todos [{:id "a" :text "Buy milk" :done 1}
                                 {:id "b" :text "Write the README" :done 0}]
                         :events 3
                         :history [{:event/number 3 :event/type :todo/completed :todo/text "Buy milk"}]
                         :tabs 1
                         :woke-at "2026-11-12T10:00:00.000Z"
                         :colo "DUS"})
        all (texts view)]
    (testing "the todos, and how many are done"
      (is (some #{"Buy milk"} all))
      (is (some #{"Write the README"} all))
      (is (= [:p.count 1 " of " 2 " done"] (some #(when (and (vector? %) (= :p.count (first %))) %)
                                                 (tree-seq coll? seq view)))))
    (testing "what is behind the page"
      (is (some #{" tab is"} all))
      (is (some #{"DUS"} all))
      (is (some #{"completed "} all)))
    (testing "a frame crosses a wire: no function in it, and transit can write it"
      (is (not-any? fn? (tree-seq coll? seq view)))
      (is (string? (transit/write-str view)))))
  (testing "an empty list"
    (let [all (texts (page/view {:todos [] :events 0 :history [] :tabs 2 :woke-at "x" :colo nil}))]
      (is (some #{"Nothing to do yet."} all))
      (is (some #{" tabs are"} all))
      (is (not-any? #{" done"} all)))))

(deftest page-view-test
  (async done
    (-> ((^:async fn []
           (let [w (world 1)]
             (await (run w :todo/add {:text "From the Object's SQLite"}))
             (is (some #{"From the Object's SQLite"} (texts (page/page-view w)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
