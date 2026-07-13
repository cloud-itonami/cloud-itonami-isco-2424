(ns training.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [training.store :as store]
            [training.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Works"})
    (store/register-module! st {:module-id "m-basic" :client-id "client-1"
                                :title "safety basics" :hours 4 :prereqs []})
    (store/register-module! st {:module-id "m-adv" :client-id "client-1"
                                :title "advanced ops" :hours 6 :prereqs ["m-basic"]})
    st))

(defn- plan [module-ids total-hours]
  {:op :draft-training-plan :effect :propose :module-ids module-ids
   :total-hours total-hours :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-on-ordered-complete-plan
  (let [st (fresh-store)
        v (governor/check req {} (plan ["m-basic" "m-adv"] 10) st)]
    (is (:ok? v))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (plan ["m-basic"] 4) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (plan ["m-basic"] 4) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-empty-plan
  (let [st (fresh-store)
        v (governor/check req {} (plan [] 0) st)]
    (is (:hard? v))
    (is (some #(= :empty-plan (:rule %)) (:violations v)))))

(deftest hard-on-invented-module
  (let [st (fresh-store)
        v (governor/check req {} (plan ["m-basic" "m-ghost"] 10) st)]
    (is (:hard? v))
    (is (some #(= :unknown-module (:rule %)) (:violations v)))))

(deftest hard-on-foreign-module
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other Org"})
    (store/register-module! st {:module-id "m-other" :client-id "client-2"
                                :title "theirs" :hours 2 :prereqs []})
    (let [v (governor/check req {} (plan ["m-basic" "m-other"] 6) st)]
      (is (:hard? v))
      (is (some #(= :module-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-prerequisite-after-dependent
  (testing "the advanced module scheduled before its prerequisite is a
            graph fact, not approvable at any confidence"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (plan ["m-adv" "m-basic"] 10)
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :prerequisite-order (:rule %)) (:violations v))))))

(deftest hard-on-prerequisite-missing-from-plan
  (let [st (fresh-store)
        v (governor/check req {} (plan ["m-adv"] 6) st)]
    (is (:hard? v))
    (is (some #(= :prerequisite-order (:rule %)) (:violations v)))))

(deftest hard-on-padded-hours
  (let [st (fresh-store)
        v (governor/check req {} (plan ["m-basic" "m-adv"] 12) st)]
    (is (:hard? v))
    (is (some #(= :hours-mismatch (:rule %)) (:violations v)))))

(deftest escalates-invitation-send
  (let [st (fresh-store)
        v (governor/check req {} {:op :send-invitations :effect :propose
                                  :confidence 0.9 :stake :medium} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} {:op :record-completion :effect :propose
                                  :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
