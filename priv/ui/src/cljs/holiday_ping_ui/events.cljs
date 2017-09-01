(ns holiday-ping-ui.events
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [goog.crypt.base64 :as base64]
            [cljs-time.core :as time]
            [holiday-ping-ui.db :as db]
            [holiday-ping-ui.time-format :as format]
            [bouncer.core :as bouncer]
            [bouncer.validators :as validators]))

;;; EFFECTS/COEFFECTS

;; TODO make these do parse/stringify json and transform to clj
(re-frame/reg-fx
 :set-local-store
 (fn [[key value]]
   (.setItem js/localStorage key value)))

(re-frame/reg-fx
 :remove-local-store
 (fn [key]
   (.removeItem js/localStorage key)))

(re-frame/reg-cofx
 :local-store
 (fn [coeffects key]
   (assoc coeffects :local-store (.getItem js/localStorage key))))

;;; GENERAL EVENTS
(re-frame/reg-event-fx
 :initialize-db
 [(re-frame/inject-cofx :local-store "access_token")]
 (fn [cofx _]
   (if-let [stored-token (:local-store cofx)]
     {:db       db/default-db
      :dispatch [:auth-success {:access_token stored-token}]} ;; TODO should this dispatch be sync ?
     {:db db/default-db})))

(re-frame/reg-event-db
 :switch-view
 (fn [db [_ new-view & args]]
   (-> db
       (assoc :current-view new-view)
       (assoc :current-view-args args)
       (dissoc :error-message)
       (dissoc :success-message))))

(re-frame/reg-event-db
 :error-message
 (fn [db [_ message extra]]
   (assoc db :error-message message)))

(re-frame/reg-event-db
 :success-message
 (fn [db [_ message]]
   (assoc db :success-message message)))

;;; AUTH EVENTS
(defn- basic-auth-header
  [user password]
  (->> (str user ":" password)
       (base64/encodeString)
       (str "Basic ")))

(re-frame/reg-event-fx
 :auth-submit
 (fn [_ [_ {:keys [email password]}]]
   {:http-xhrio {:method          :get
                 :uri             "/api/auth/token"
                 :timeout         8000
                 :headers         {:authorization (basic-auth-header email password)}
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:auth-success]
                 :on-failure      [:error-message "Authentication failed"]}}))

(re-frame/reg-event-fx
 :auth-success
 (fn
   [{:keys [db]} [_ response]]
   (let [token  (:access_token response)
         new-db (assoc db :access-token token)]
     {:db              new-db
      :set-local-store ["access_token" token]
      :dispatch-n      [[:channel-load]
                        [:holidays-load]
                        [:switch-view :dashboard]]})))

(re-frame/reg-event-fx
 :logout
 (fn [{:keys [db]} _]
   {:db                 (-> db
                            (dissoc :access-token)
                            (assoc :current-view :login))
    :remove-local-store "access_token"}))

(re-frame/reg-event-fx
 :register-submit
 (fn [_ [_ {:keys [email password password-repeat] :as data}]]
   ;; TODO handle validations generically
   (cond
     (some string/blank? (vals data))
     {:dispatch [:error-message "All fields are required."]}

     (not= password password-repeat)
     {:dispatch [:error-message "Passwords must match."]}

     (not (bouncer/valid? data :email validators/email))
     {:dispatch [:error-message "Email is invalid."]}

     :else {:http-xhrio {:method          :post
                         :uri             "/api/users"
                         :timeout         8000
                         :format          (ajax/json-request-format)
                         :params          data
                         :response-format (ajax/text-response-format)
                         :on-success      [:register-success email password]
                         :on-failure      [:error-message "Registration failed"]}})))

(re-frame/reg-event-fx
 :register-success
 (fn
   [_ [_ email password _]]
   {:dispatch [:auth-submit {:email    email
                             :password password}]}))

;;; CHANNEL EVENTS

(re-frame/reg-event-fx
 :channel-load
 (fn [{:keys [db]} _]
   {:http-xhrio {:method          :get
                 :uri             "/api/channels"
                 :timeout         8000
                 :headers         {:authorization (str "Bearer " (:access-token db))}
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:channel-load-success]
                 :on-failure      [:error-message "Channel loading failed."]}}))

(re-frame/reg-event-db
 :channel-load-success
 (fn [db [_ response]]
   (assoc db :channels response)))

(re-frame/reg-event-fx
 :channel-delete
 (fn [{db :db} [_ channel]]
   {:http-xhrio {:method          :delete
                 :uri             (str "/api/channels/" channel)
                 :timeout         8000
                 :headers         {:authorization (str "Bearer " (:access-token db))}
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:channel-delete-success channel]
                 :on-failure      [:error-message "Channel deleting failed."]}}))

(re-frame/reg-event-db
 :channel-delete-success
 (fn [db [_ channel]]
   (update db :channels
           (fn [channels]
             (remove #(= (:name %) channel) channels)))))

(defn valid-slack-target?
  [value]
  (or (string/starts-with? value "#")
      (string/starts-with? value "@")))

(re-frame/reg-event-fx
 :channel-submit
 (fn [{db :db} [_ {:keys [name type url channels username emoji]}]]
   (let [channels (string/split channels #"\s+")
         params   {:name          name
                   :type          type
                   :configuration {:channels channels
                                   :url      url
                                   :username username
                                   :emoji    emoji}}]
     (cond
       (some string/blank? [name type url])
       {:dispatch [:error-message "Please fill required fields."]}

       (not (every? valid-slack-target? channels))
       {:dispatch [:error-message "Slack targets must start with @ or #"]}

       (not (string/starts-with? url "https://hooks.slack.com/"))
       {:dispatch [:error-message "The url should be a valid slack hook url."]}

       :else {:http-xhrio {:method          :put
                           :uri             (str "/api/channels/" name)
                           :headers         {:authorization (str "Bearer " (:access-token db))}
                           :timeout         8000
                           :format          (ajax/json-request-format)
                           :params          params
                           :response-format (ajax/text-response-format)
                           :on-success      [:channel-submit-success]
                           :on-failure      [:error-message "Channel submission failed"]}}))))

(re-frame/reg-event-fx
 :channel-submit-success
 (fn [_ _]
   {:dispatch-n [[:channel-load]
                 [:switch-view :channel-list]]}))

(re-frame/reg-event-db
 :channel-test-start
 (fn [db [_ channel]]
   (assoc db :channel-to-test channel)))

(re-frame/reg-event-db
 :channel-test-cancel
 (fn [db _]
   (dissoc db :channel-to-test)))

(re-frame/reg-event-fx
 :channel-test-confirm
 (fn [{:keys [db]} [_ channel]]
   {:dispatch   [:channel-test-cancel]
    :http-xhrio {:method          :post
                 :uri             (str "/api/channels/" (:name channel) "/test")
                 :timeout         8000
                 :headers         {:authorization (str "Bearer " (:access-token db))}
                 :format          (ajax/json-request-format)
                 :params          {}
                 :response-format (ajax/text-response-format)
                 :on-success      [:success-message "Channel reminder sent."]
                 :on-failure      [:error-message "There was an error sending the reminder."]}}))

;;; HOLIDAY EVENTS

(re-frame/reg-event-fx
 :holidays-load
 (fn [{:keys [db]} _]
   {:http-xhrio {:method          :get
                 :uri             "/api/holidays"
                 :timeout         8000
                 :headers         {:authorization (str "Bearer " (:access-token db))}
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:holidays-load-success]
                 :on-failure      [:error-message "Holidays loading failed."]}}))

(re-frame/reg-event-db
 :holidays-load-success
 (fn [db [_ response]]
   (let [holidays (map #(update % :date format/string-to-date) response)]
     (-> db
         (assoc :holidays-saved holidays)
         (assoc :holidays-edited holidays)))))

(re-frame/reg-event-fx
 :holidays-save
 (fn [{:keys [db]} _]
   (let [edited       (:holidays-edited db)
         new-holidays (map #(update % :date format/date-to-string) edited)]
     {:http-xhrio {:method          :put
                   :uri             "/api/holidays"
                   :timeout         8000
                   :headers         {:authorization (str "Bearer " (:access-token db))}
                   :response-format (ajax/json-response-format {:keywords? true})
                   :format          (ajax/json-request-format)
                   :params          new-holidays
                   :on-success      [:holidays-load-success]
                   :on-failure      [:error-message "Holidays saving failed."]}})))

(re-frame/reg-event-db
 :holidays-reset
 (fn [db _]
   (assoc db :holidays-edited (:holidays-saved db))))

(re-frame/reg-event-db
 :holidays-clear
 (fn [db _]
   (let [past?         #(time/before? (:date %) (time/today))
         past-holidays (take-while past? (:holidays-saved db))]
     (assoc db :holidays-edited past-holidays))))

(re-frame/reg-event-db
 :calendar-select-year
 (fn [db [_ year]]
   (assoc db :calendar-selected-year year)))

(re-frame/reg-event-db
 :calendar-select-day
 (fn [db [_ day]]
   (let [holidays (:holidays-edited db)
         name     (:name (first (filter #(time/= day (:date %)) holidays)))]
     (-> db
         (assoc :calendar-selected-day day)
         (assoc :calendar-selected-day-name name)))))

(re-frame/reg-event-db
 :calendar-deselect-day
 (fn [db]
   (-> db
       (dissoc :calendar-selected-day)
       (dissoc :calendar-selected-day-name))))

(re-frame/reg-event-db
 :calendar-selected-name-change
 (fn [db [_ name]]
   (assoc db :calendar-selected-day-name name)))

(re-frame/reg-event-db
 :holidays-update
 (fn [db [_ date name]]
   (let [edited  (:holidays-edited db)
         removed (remove #(time/= date (:date %)) edited)
         updated (conj removed {:date date :name name})]
     (assoc db :holidays-edited updated))))

(re-frame/reg-event-db
 :holidays-remove
 (fn [db [_ date]]
   (let [edited  (:holidays-edited db)
         removed (remove #(time/= date (:date %)) edited)]
     (assoc db :holidays-edited removed))))