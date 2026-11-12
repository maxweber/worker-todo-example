(ns todo.client
  "The browser bundle: the rframes runtime and the one component the page
   brings, a text input whose text lives in the browser's store. The
   list's Object names it as `[:ui/input {:ui/store-key :draft}]` and
   never sees what is typed until a command carries it."
  (:require [rframes.client :as rframes]))

(defn input
  "An input whose text lives in the store under :ui/store-key, so that a
   new frame from the server does not reset what the user types. The
   other attributes pass through, the handlers of the server next to the
   one that keeps the text."
  [{:keys [ui/store-key ui/state] :as attrs} _children]
  [:input
   (-> (dissoc attrs :ui/store-key :ui/state)
       (assoc :type "text"
              :value (str (get state store-key ""))
              :on (assoc (:on attrs)
                         :input [[:store/assoc store-key :event/target.value]])))])

(rframes/init! {:register [{:ui.alias/kind :ui/input
                            :ui.alias/fn input}]})
