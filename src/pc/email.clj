(ns pc.email
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.format]
            [datomic.api :as d]
            [hiccup.core :as hiccup]
            [pc.datomic :as pcd]
            [pc.mailgun :as mailgun]
            [pc.models.access-grant :as access-grant-model]
            [pc.utils]))

(defn emails-to-send [db eid]
  (set (map first
            (d/q '{:find [?emails]
                   :in [$ ?e]
                   :where [[?e :needs-email ?email-eid]
                           [?email-eid :db/ident ?emails]]}
                 db eid))))

(defn mark-sent-email
  "Returns true if this was the first transaction to mark the email as sent. False if it wasn't."
  [eid email-enum]
  (let [t @(d/transact (pcd/conn) [[:db/retract eid :needs-email email-enum]
                                   [:db/add eid :sent-email email-enum]])]
    (and (contains? (emails-to-send (:db-before t) eid) email-enum)
         (not (contains? (emails-to-send (:db-after t) eid) email-enum)))))

(defn unmark-sent-email
  [eid email-enum]
  @(d/transact (pcd/conn) [[:db/add eid :needs-email email-enum]
                           [:db/retract eid :sent-email email-enum]]))

(defn chat-invite-html [doc-id]
  (hiccup/html
   [:html
    [:body
     [:p
      "I'm prototyping something on Precursor, come join me at "
      [:a {:href (str "https://prcrsr.com/document/" doc-id)}
       (str "https://prcrsr.com/document/" doc-id)]
      "."]
     [:p "This is what I have so far:"]
     [:p
      [:a {:href (str "https://prcrsr.com/document/" doc-id)
           :style "display: inline-block"}
       [:img {:width 325
              :style "border: 1px solid #888888;"
              :alt "Images disabled? Just come and take a look."
              :src (str "https://prcrsr.com/document/" doc-id ".png?rand=" (rand))}]]]
     [:p {:style "font-size: 12px"}
      "Tell us if this message was sent in error info@prcrsr.com."
      ;; Add some hidden text so that Google doesn't try to trim these.
      [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
       " Sent at "
       (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
       "."]]]]))

(defn format-inviter [inviter]
  (str/trim (str (:cust/first-name inviter)
                 (when (and (:cust/first-name inviter)
                            (:cust/last-name inviter))
                   (str " " (:cust/last-name inviter)))
                 " "
                 (cond (and (not (:cust/last-name inviter))
                            (:cust/first-name inviter))
                       (str "(" (:cust/email inviter) ") ")

                       (not (:cust/first-name inviter))
                       (str (:cust/email inviter) " ")

                       :else nil))))

(defn send-chat-invite [{:keys [cust to-email doc-id]}]
  (mailgun/send-message {:from "Precursor <joinme@prcrsr.com>"
                         :to to-email
                         :subject (str (format-inviter cust)
                                       " invited you to a document on Precursor")
                         :text (str "Hey there,\nCome draw with me on Precursor: https://prcrsr.com/document" doc-id)
                         :html (chat-invite-html doc-id)
                         :o:tracking "yes"
                         :o:tracking-opens "yes"
                         :o:tracking-clicks "no"
                         :o:campaign "chat_invites"}))

(defn access-grant-html [doc-id token]
  (let [doc-link (str "https://prcrsr.com/document/" doc-id "?access-grant-token=" token)]
    (hiccup/html
     [:html
      [:body
       [:p
        "I'm prototyping something on Precursor, come join me at "
        [:a {:href doc-link}
         (str "https://prcrsr.com/document/" doc-id)]
        "."]
       [:p "This is what I have so far:"]
       [:p
        [:a {:href doc-link
             :style "display: inline-block"}
         [:img {:width 325
                :style "border: 1px solid #888888;"
                :alt "Images disabled? Just come and take a look."
                :src (str "https://prcrsr.com/document/" doc-id ".png?rand=" (rand) "&access-grant-token=" token)}]]]
       [:p {:style "font-size: 12px"}
        "Tell us if this message was sent in error info@prcrsr.com."
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn send-access-grant-email* [db access-grant-eid]
  (let [access-grant (d/entity db access-grant-eid)
        doc-id (:access-grant/document access-grant)
        granter (access-grant-model/get-granter db access-grant)
        token (:access-grant/token access-grant)]
    (mailgun/send-message {:from "Precursor <joinme@prcrsr.com>"
                           :to (:access-grant/email access-grant)
                           :subject (str (format-inviter granter)
                                         " invited you to a document on Precursor")
                           :text (str "Hey there,\nCome draw with me on Precursor: https://prcrsr.com/document" doc-id
                                      "?access-grant-token=" token)
                           :html (access-grant-html doc-id token)
                           :o:tracking "yes"
                           :o:tracking-opens "yes"
                           :o:tracking-clicks "no"
                           :o:campaign "access_grant_invites"})))

(defn send-access-grant-email [db access-grant-eid]
  (if (mark-sent-email access-grant-eid :email/access-grant-created)
    (try
      (log/infof "sending access-grant-email for %s" access-grant-eid)
      (send-access-grant-email* db access-grant-eid)
      (catch Exception e
        (.printStackTrace e)
        (unmark-sent-email access-grant-eid :email/access-grant-created)))
    (log/infof "not re-sending access-grant-email for %s" access-grant-eid)))

(defn send-access-grant-email-cron
  "Used to catch any emails missed by the transaction watcher."
  []
  (let [db (pcd/default-db)]
    (doseq [[grant-eid] (d/q '{:find [?t]
                               :in [$ ?email]
                               :where [[?t :access-grant/email]
                                       [?t :needs-email ?email]]}
                             db :email/access-grant-created)]
      (log/infof "queueing access grant email for %s" grant-eid)
      (send-access-grant-email db grant-eid))))

(defn init []
  (pc.utils/safe-schedule {:minute (range 0 60 5)} #'send-access-grant-email-cron))
