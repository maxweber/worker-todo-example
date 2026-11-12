(ns todo.domain-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [todo.domain :as domain]
            [todo.fake :as fake]
            [worker-event-sourcing.store :as store]))

(defn- new-list
  []
  (doto (fake/storage)
    (store/schema! domain/domain)))

(defn- execute!
  [storage command]
  (store/execute! storage domain/domain command))

(defn- todos
  [^js storage]
  ((get domain/queries "todos") (.-sql storage)))

(deftest life-of-a-todo-test
  (let [storage (new-list)
        {[added] :events} (execute! storage {:command/type :todo/add :todo/text "  Buy milk  "})
        id (:todo/id added)]
    (testing "added, trimmed"
      (is (= :todo/added (:event/type added)))
      (is (= [{:id id :text "Buy milk" :done 0}] (todos storage))))
    (testing "completed, and the event says what was completed"
      (is (= [{:event/type :todo/completed :todo/id id :todo/text "Buy milk"}]
             (map #(select-keys % [:event/type :todo/id :todo/text])
                  (:events (execute! storage {:command/type :todo/complete :todo/id id})))))
      (is (= 1 (:done (first (todos storage))))))
    (testing "completing it again changes nothing"
      (is (= {:events []} (execute! storage {:command/type :todo/complete :todo/id id}))))
    (testing "reopened"
      (is (= [:todo/reopened] (map :event/type (:events (execute! storage {:command/type :todo/reopen :todo/id id})))))
      (is (= 0 (:done (first (todos storage)))))
      (is (= {:events []} (execute! storage {:command/type :todo/reopen :todo/id id}))))
    (testing "removed, and removing what is gone is no error"
      (is (= [:todo/removed] (map :event/type (:events (execute! storage {:command/type :todo/remove :todo/id id})))))
      (is (= [] (todos storage)))
      (is (= {:events []} (execute! storage {:command/type :todo/remove :todo/id id}))))
    (testing "the log has every step, numbered"
      (is (= [[1 :todo/added] [2 :todo/completed] [3 :todo/reopened] [4 :todo/removed]]
             (map (juxt :event/number :event/type) (store/events-after storage 0 100)))))))

(deftest rejections-test
  (let [storage (new-list)]
    (is (= {:error "A todo needs a text."} (execute! storage {:command/type :todo/add :todo/text "   "})))
    (is (= {:error "A todo needs a text."} (execute! storage {:command/type :todo/add})))
    (is (= {:error "A todo has at most 200 characters."}
           (execute! storage {:command/type :todo/add :todo/text (str/join (repeat 201 "x"))})))
    (is (= {:error "No such todo."} (execute! storage {:command/type :todo/complete :todo/id "nope"})))
    (is (= {:error "No such todo."} (execute! storage {:command/type :todo/reopen :todo/id nil})))
    (is (str/starts-with? (:error (execute! storage {:command/type :todo/explode})) "Unknown command"))
    (testing "a rejection writes nothing"
      (is (= [] (store/events-after storage 0 100))))))

(deftest full-list-test
  (let [storage (new-list)]
    (dotimes [n domain/list-limit]
      (execute! storage {:command/type :todo/add :todo/text (str "todo " n)}))
    (is (= domain/list-limit (count (todos storage))))
    (is (= {:error "A list holds at most 100 todos."}
           (execute! storage {:command/type :todo/add :todo/text "one more"})))))

(deftest project-test
  (testing "a projection is data: the statements of an event"
    (is (= [["insert into todos (id, text) values (?, ?)" "a" "Buy milk"]]
           (domain/project {:event/type :todo/added :todo/id "a" :todo/text "Buy milk"})))
    (is (= [["delete from todos where id = ?" "a"]]
           (domain/project {:event/type :todo/removed :todo/id "a" :todo/text "Buy milk"}))))
  (testing "an unknown type throws"
    (is (thrown? js/Error (domain/project {:event/type :todo/unknown})))))

(deftest rebuild-test
  (let [storage (new-list)
        id (:todo/id (first (:events (execute! storage {:command/type :todo/add :todo/text "Keep me"}))))]
    (execute! storage {:command/type :todo/add :todo/text "And me"})
    (execute! storage {:command/type :todo/complete :todo/id id})
    (let [before (todos storage)]
      (store/rebuild! storage domain/domain)
      (testing "the read model is derived: dropped and built again from the log, it is the same"
        (is (= before (todos storage)))
        (is (= [1 0] (map :done (todos storage))))))))
