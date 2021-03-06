(ns clutter.connection
  (:require [cljs.core.async :refer [>! <! chan close!
                                     dropping-buffer mult pub put!
                                     sliding-buffer take! tap]
             :as async]

            [chord.client :refer [ws-ch]]

            [clutter.changes :refer [apply-change merge-changes]]
            [clutter.query :as query]
            [clutter.renderable :as r :refer [escaped]])
  (:require-macros [cljs.core.async.macros :refer [alt! go go-loop]]))

(defonce app-state
  (atom {:db-cache {}
         :user-id nil
         :messages []
         ;; IDs of the currently selected objects
         :selection #{}}))

(defonce connection (atom nil))
(defonce db-chan (chan (sliding-buffer 1)))
(defonce db-mult (mult db-chan))
                                        ;(defonce db-pub (pub db-chan key (s)))
(def pending-gets (atom []))

(defn db-get-cache
  [id]
  (get-in @app-state [:db-cache id]))

(defn db-get
  "Returns a channel that receives "
  ([id c conn]
   (when id
     (let [result (get-in @app-state [:db-cache id])]
       ;; Always put something on the channel:
       ;; (What if the object doesn't exist?
       (prn "fetching" id)
       (put! c (or result {}))
       (if conn
         (when (put! conn {:dbq id})
           (go
             (let [in (tap db-mult (chan (sliding-buffer 1)
                                         (filter #(contains? % id))))]

               (loop [last result]
                 (let [new-result (get (<! in) id)]
                   (when (or (= new-result last)
                             (put! c new-result))
                     (recur new-result))))
               (close! in))))

         (swap! pending-gets conj id))))
   c)
  ([id c]
   (db-get id c @connection))
  ([id]
   (db-get id (chan (sliding-buffer 1)) @connection)))

(defn cache-good?
  "Determine if the connection is already receiving updates that will
  satisfy the given query. "
  [q]
  false)

(defn send-message!
  [msg]
  (when-let [conn @connection]
    (prn "Sending message:" msg)
    (async/put! conn msg)))

(defn append-message
  [actor text stamp & [type]]
  (swap! app-state
         (fn [{ml :messages :as state}]
           (assoc state :messages (conj ml {:actor actor
                                            :text text
                                            :stamp stamp
                                            :type type})))))


;; The request loop buffers database queries.
(defonce request-chan (chan))

(defn start-db-request-loop
  []
  (go-loop []
    (let [queries (go-loop [queries (list (<! request-chan))
                            timer nil]
                    (let [timer (or timer (async/timeout 50))]
                      (alt! request-chan ([q] (recur (cons q queries) timer))
                            timer queries)))]
      ;; Send the consolidated query to the server
      (send-message! {:dbq (vec queries)})
      (recur))))

(defn connect!
  [handler]
  (go
    (let [url (str "ws://" (.-host js/location) "/connect")
          {:keys [ws-channel error]} (<! (ws-ch url))]
      (if-not error
        (do
          (reset! connection ws-channel)
          ;; Run pending gets
          (loop []
            (let [{m :message, err :error} (<! ws-channel)]
              (when-not err
                (handler m)
                (recur)))))

        (prn "Connection error:" error)))))


(defmulti handle-type :type)
(defmethod handle-type :default [_] nil)

(defn handle-message
  [m]
  (prn "Received:" m)
  (let [{t :text, db :db, e :error, dbd :dbd} m
        user (db-get-cache (:user m))
        uname (escaped (:name user ""))]
    (when-let [uid (:user-id m)]
      (swap! app-state assoc :user-id uid))

    (when db
      ;; Update the DB. New values will supersede old.
      (swap! app-state (fn [st]
                         (let [dbc (:db-cache st)]
                           (assoc st
                                  :db-cache (merge-with merge dbc db))))))

    (when dbd
      ;; dbd == db-delta
      ;; See changes namespace for documentation.
      (let [new-state (swap! app-state
                             (fn [st]
                               (assoc st
                                      :db-cache (apply-change (:db-cache st) dbd))))]
        (put! db-chan (select-keys (:db-cache new-state)
                                   (query/top-level-ids dbd)))))

    ;; Any message can have a visible component that is printed
    ;; directly.
    (when (or t e)
      (append-message uname (or e t) (:stamp m (.valueOf (js/Date.))) (when e "error")))

    (handle-type m)))

(defn init []
  (when-not @connection
    (connect! (fn [m] (handle-message m)))))
