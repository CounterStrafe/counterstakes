(ns counterstakes.core
  (:require [discljord.connections :as c]
            [discljord.messaging :as m]
            [discljord.events :as e]
            [clojure.core.async :as a]
            [clojure.string :as s]
            [counterstakes.secerets :as sec]
            [clojure.string :as str]
            [taoensso.carmine :as car :refer (wcar)])
  (:gen-class))

(def server1-conn {:pool {} :spec {:uri "redis://127.0.0.1:6379/0"}})
(defmacro wcar* [& body] `(car/wcar server1-conn ~@body))
#_(wcar* (car/ping))

(def state (atom nil))

(defn flip-team
  [team]
  (case team
    "1" "2"
    "2" "1"
    :default "0"))

(defn format-2
  [vec2]
  (str (first vec2) "   =>   " (second vec2)))

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
  [event-type {{username :username id :id disc :discriminator} :author, :keys [channel-id]}]
  (let [balance (wcar* (car/hget :users id))]
    (when (= channel-id (str sec/bot-channel))
      (if balance
        (m/create-message! (:messaging @state) channel-id
                           :content (str "<@" id "> " balance))
        (do
          (wcar* (car/multi)
                 (car/hset :users id 100)
                 (car/lpush :usernames username)
                 (car/lpush :user-ids id)
                 (car/exec))
          (m/create-message! (:messaging @state) channel-id
                             :content (str "<@" id "> " (wcar* (car/hget :users id)))))))))

(defmethod handle-event "!create-bet"
  [event-type {:keys [content]}]
  (let [split-content (str/split content #" ")
        pw (get split-content 1)
        game-id (get split-content 2)
        team1 (get split-content 3)
        team2 (get split-content 4)
        {messaging :messaging
         betting-time :betting-time
         password :password} @state]
    (when (and (= 5 (count split-content))
               (= (hash pw) password))
      (wcar* (car/hmset game-id
                        "1" team1
                        "2" team2))
      (swap! state assoc
            :open? true
            :game-id game-id
            :team1 team1
            :team2 team2)
      (m/create-message! messaging sec/bot-channel
                         :content (str "Bets are now open for game " game-id
                                       "\n" team1 " vs " team2 "!"
                                       "\ntype ```!bet 1 <amount>``` to bet on " team1
                                       "\nand ```!bet 2 <amount>``` to bet on " team2
                                       "\nYou have " (/ betting-time 1000) " seconds to make your bet!"))
      (a/go (a/<! (a/timeout betting-time))
            (swap! state assoc :open? false)
            (let [t1-total-str (wcar* (car/hget game-id "1:total"))
                  t2-total-str (wcar* (car/hget game-id "2:total"))
                  t1-total (if (nil? t1-total-str) 0.0 (Float. t1-total-str))
                  t2-total (if (nil? t2-total-str) 0.0 (Float. t2-total-str))
                  ratio (cond
                          (and (= t1-total 0.0) (= t2-total 0.0)) "0:0"
                          (= t1-total 0.0) "0.00:1.00"
                          (= t2-total 0.0) "1.00:0.00"
                          (> t1-total t2-total) (str (format "%.2f" (/ t1-total t2-total)) " : " "1.00")
                          :default (str "1.00" " : " (format "%.2f" (/ t2-total t1-total))))]
              (m/create-message! messaging sec/bot-channel
                                 :content (str "Bets are now closed!"
                                               "\n" "Ratio in: " team1 " : " team2
                                               "\n```" ratio "```")))))))

(defmethod handle-event "!bet"
  [event-type {{username :username user-id :id} :author, :keys [id channel-id content]}]
  (let [{game-id :game-id
         messaging :messaging
         open? :open?} @state
        split-content (str/split content #" ")
        team (get split-content 1)
        amount (try (Integer. (get split-content 2))
                    (catch Exception e
                      (m/create-reaction! messaging channel-id id \u274C)
                      (throw (Exception. "wrong input format"))))
        user-balance-str (wcar* (car/hget :users user-id))
        user-balance (if (nil? user-balance-str)
                       (do (wcar*
                            (car/multi)
                            (car/hset :users user-id 100)
                            (car/lpush :usernames username)
                            (car/lpush :user-ids user-id)
                            (car/exec))
                           100)
                       (Integer. user-balance-str))]
    (if (and
         open?
         (= channel-id (str sec/bot-channel))
         (or (= team "1") (= team "2"))
         (= 3 (count split-content))
         (> amount 0)
         (<= amount user-balance))
      (let [old-amount (wcar* (car/hget game-id (str user-id ":amount")))
            old-team (wcar* (car/hget game-id (str user-id ":team")))]
        (when (and old-amount old-team)
          (wcar* (car/hincrby game-id (str old-team ":total") (* -1 (Integer. old-amount))))
          (wcar* (car/srem (str game-id ":1") user-id))
          (wcar* (car/srem (str game-id ":2") user-id)))
        (wcar*
         (car/multi)
         (car/hset game-id (str user-id ":team") team)
         (car/hset game-id (str user-id ":amount") amount)
         (car/hincrby game-id (str team ":total") amount)
         (car/sadd (str game-id ":" team) user-id)
         (car/exec))
        (m/create-reaction! messaging channel-id id \u2705))
      (m/create-reaction! messaging channel-id id \u274C))))

(defmethod handle-event "!pay"
  [event-type {:keys [content]}]
  (let [split-content (str/split content #" ")
        pw (get split-content 1)
        team (get split-content 2)
        {game-id :game-id
         messaging :messaging
         open? :open?
         password :password} @state]
    (when (and
           (= (hash pw) password)
           (or (= team "1") (= team "2")))
      (let [winners-ids (wcar* (car/smembers (str game-id ":" team)))
            loser-ids (wcar* (car/smembers (str game-id ":" (flip-team team))))
            w-str (wcar* (car/hget game-id (str team ":total")))
            l-str (wcar* (car/hget game-id (str (flip-team team) ":total")))
            winning (if (nil? w-str) 0.0 (Float. w-str))
            losing (if (nil? l-str) 0.0 (Float. l-str))
            ratio (if (= winning 0.0) 0 (/ losing winning))]
        (doseq [winner winners-ids]
          (wcar* (car/hincrby :users winner (int (* ratio (Integer. (wcar* (car/hget game-id (str winner ":amount")))))))))
        (doseq [loser loser-ids]
          (let [losses (int (* -1 (Integer. (wcar* (car/hget game-id (str loser ":amount"))))))
                current-balance (Integer. (wcar* (car/hget :users loser)))
                total-balance (+ losses current-balance)]
            (if (< total-balance 100)
              (wcar* (car/hset :users loser 100))
              (wcar* (car/hset :users loser total-balance)))))
        (wcar* car/bgsave)
        (m/create-message! messaging sec/bot-channel
                           :content (str "Team " team " wins the bet!"))))))

(defmethod handle-event "!top"
  [event-type {:keys [content channel-id]}]
  (let [usernames (wcar* (car/lrange :usernames 0 -1))
        ids (wcar* (car/lrange :user-ids 0 -1))
        {game-id :game-id
         messaging :messaging} @state]
    (when (= channel-id (str sec/bot-channel))
        (m/create-message! messaging channel-id
                           :content (s/join "\n"
                                            (map format-2
                                                 (take 10
                                                       (reverse
                                                        (sort-by second
                                                                 (mapv #(vector %1 (Integer. (wcar* (car/hget :users %2))))
                                                                       usernames ids))))))))))

(defmethod handle-event "!help"
  [event-type {:keys [channel-id]}]
  (m/create-message! (:messaging @state) channel-id
                     :content (str "This bot will allow you to participate in our fake money betting markets!"
                                   " The person who will have the most money at the end of the tournament will"
                                   " win a :trophy: MP9 StatTrak Ruby Poison Dart skin :trophy:"
                                   "\n\n Here are the commands available:"

                                   "\n```!balance - checks your current balance. You can never go under 100 credits. It is recommended "
                                   "to go all in if your bet would otherwise leave you wih less than 100 credits```"
                                   
                                   "\n```!bet <team-number> <amount> - Bet credits on a specific team. This command "
                                   "can only be used when a betting market is open. These are only for streamed games "
                                   "and will be announced in #general. You have 3 minutes from the time a betting market opens to place a bet. "
                                   "Making a new bet will override your previous bet.```"
                                   
                                   "\n ```!top - shows the top 10 highest balances.```"

                                   "\n To avoid spam in the #general channel, we will block all commands coming from there. "
                                   "We recommend using #bot-commands or DMing the bot. ")))

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
                    :betting-time 180000}]
    (reset! state init-state)
    (e/message-pump! event-ch handle-event)
    (m/stop-connection! messaging-ch)
    (c/disconnect-bot! connection-ch)))
