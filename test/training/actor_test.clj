(ns training.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [training.actor :as actor]
            [training.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Works"})
    (store/register-module! st {:module-id "m-basic" :client-id "client-1"
                                :title "safety basics" :hours 4 :prereqs []})
    (store/register-module! st {:module-id "m-adv" :client-id "client-1"
                                :title "advanced ops" :hours 6 :prereqs ["m-basic"]})
    st))

(deftest commits-an-ordered-plan-with-lib-summed-hours
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-training-plan :stake :low
                 :module-ids ["m-basic" "m-adv"]}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 10 (get-in result [:state :record :payload :total-hours])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-a-misordered-plan-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-training-plan :stake :low
                 :module-ids ["m-adv" "m-basic"]}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-sends-invitations-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :send-invitations :stake :medium}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
