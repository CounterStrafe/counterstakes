(ns counterstakes.core
  (:require [discljord.connections :as c]
            [discljord.messaging :as m]
            [discljord.events :as e]
            [clojure.core.async :as a]
            [counterstakes.secerets :as sec]
            [clojure.string :as str]
            [taoensso.carmine :as car :refer (wcar)]))

(def server1-conn {:pool {} :spec {:uri "redis://127.0.0.1:6379/0"}})
(defmacro wcar* [& body] `(car/wcar server1-conn ~@body))
(wcar* (car/ping))

(def state (atom nil))

(defmulti handle-event
  (fn [event-type event-data]
    (when (and
           (not (:bot (:author event-data)))
           (= event-type :message-create))
      (first (str/split (:content event-data) #" ")))))

(defmethod handle-event :default
  [event-type event-data])

(defmethod handle-event "!disconnect"
  [event-type {:keys [content]}]
  (let [split-content (str/split content #" ")
        pw (get split-content 1)]
    (when (and (= 2 (count split-content))
               (= (hash pw) (:password @state)))
      (a/put! (:connection @state) [:disconnect]))))

(defmethod handle-event "!cs-change-password"
  [event-type {:keys [channel-id content]}]
  (let [split-content (str/split content #" ")
        pw (get split-content 1)
        new-pw (get split-content 2)]
    (when (and (= 3 (count split-content))
               (= (hash pw) (:password @state)))
      (swap! state assoc :password (hash new-pw))
      (m/create-message! (:messaging @state) channel-id :content "Updated!"))))

(defmethod handle-event "!balance"
  [event-type {{id :id} :author, :keys [channel-id]}]
  (let [balance (wcar* (car/hget :users id))]
    (if balance
      (m/create-message! (:messaging @state) channel-id
                         :content (str balance))
      (do
        (wcar* (car/hset :users id 100))
        (m/create-message! (:messaging @state) channel-id
                           :content (str (wcar* (car/hget :users id))))))))

(defmethod handle-event "!create-bet"
  [event-type {:keys [channel-id content]}]
  (let [split-content (str/split content #" ")
        pw (get split-content 1)
        game-id (get split-content 2)
        team1 (get split-content 3)
        team2 (get split-content 4)
        {messaging :messaging
         dac :default-announcement-channel
         betting-time :betting-time
         password :password} @state]
    (when (and (= 5 (count split-content))
               (= (hash pw) password))
      (wcar* (car/hmset game-id "1" team1 "2" team2))
      (swap! state assoc
            :open? true
            :game-id game-id
            :team1 team1
            :team2 team2)
      (m/create-message! messaging dac
                         :content (str "Bets are now open for game " game-id
                                       "\n" team1 " vs " team2 "!"
                                       "\ntype ```!bet 1 <amount>``` to bet on " team1
                                       "\nand ```!bet 2 <amount>``` to bet on " team2
                                       "\nYou have " (/ betting-time 1000) " seconds to make your bet!"))
      (a/go (a/<! (a/timeout betting-time)))
      (swap! state assoc :open? false)
      (m/create-message! messaging dac
                         :content "Bets are now closed! Good luck!"))))

(defmethod handle-event "!bet"
  [event-type {{user-id :id} :author, :keys [channel-id content]}]
  (let [split-content (str/split content #" ")
        team (get split-content 1)
        amount (Integer. (get split-content 2))
        {game-id :game-id} @state]
    (when (and
           (or (= team "1") (= team "2"))
           (= 3 (count split-content))
           (> amount 0)
           (< amount (Integer. (wcar* (car/hget :users user-id)))))
      (wcar*
       (car/multi)
       (car/hincrby :users user-id (* -1 amount))
       (car/hset game-id (str user-id ":team" ) team)
       (car/hset game-id (str user-id ":amount" ) amount)
       (car/exec)))))

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
                    :messaging messaging-ch
                    :password sec/default-pw
                    :default-announcement-channel sec/default-announcement-channel
                    :betting-time 60000}]
    (reset! state init-state)
    (e/message-pump! event-ch handle-event)
    (m/stop-connection! messaging-ch)
    (c/disconnect-bot! connection-ch)))
