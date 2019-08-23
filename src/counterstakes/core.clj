(ns counterstakes.core
  (:require [discljord.connections :as c]
            [discljord.messaging :as m]
            [discljord.events :as e]
            [clojure.core.async :as a]
            [counterstakes.secerets :as sec]
            [clojure.string :as str]))

(def state (atom {:password sec/default-pw}))

(defmulti handle-event
  (fn [event-type event-data]
    (when (and
           (not (:bot (:author event-data)))
           (= event-type :message-create))
      (first (str/split (:content event-data) #" ")))))

(defmethod handle-event :default
  [event-type event-data])

(defmethod handle-event "!disconnect"
  [event-type event-data]
  (a/put! (:connection @state) [:disconnect]))

(defmethod handle-event "!cs-change-password"
  [event-type {:keys [channel-id content]}]
  (let [split-content (str/split content)
        pw (get 2 split-content)
        new-pw (get 3 split-content)]
    (when (and (= 3 split-content)
               (= (hash pw) (:password @state)))
      (swap! state assoc :password (hash (new-pw)))
      (m/create-message! (:messaging @state) channel-id :content "Updated!"))))

(defmethod handle-event "!hello"
  [event-type {:keys [channel-id]}]
  (m/create-message! (:messaging @state) channel-id :content "hello"))

(defn -main
  [& args]
  (let [event-ch (a/chan 100)
        connection-ch (c/connect-bot! sec/token event-ch)
        messaging-ch (m/start-connection! sec/token)
        init-state {:connection connection-ch
                    :event event-ch
                    :messaging messaging-ch}]
    (reset! state init-state)
    (e/message-pump! event-ch handle-event)
    (m/stop-connection! messaging-ch)
    (c/disconnect-bot! connection-ch)))
