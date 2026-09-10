(ns training.advisor
  "TrainingAdvisor — proposes a training operation (draft a training
  plan from registered curriculum modules, record a completion, send
  invitations) for a registered organization. Swappable mock/llm; the
  advisor ONLY proposes — `training.governor` validates module
  existence, prerequisite order and hour totals independently. Modeled
  on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :draft-training-plan|:record-completion|:send-invitations
               :effect :propose :module-ids [str ...] :total-hours n
               :stake kw :confidence n :rationale str}"
  (:require [training.store :as store]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference. For :draft-training-plan the honest
  advisor sums hours from the registered modules (an LLM advisor would
  tool-call the same); the governor recomputes independently."
  [store {:keys [op stake module-ids] :as request}]
  (let [base {:op op
              :effect :propose
              :stake (or stake :low)
              :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
              :rationale (str "proposed " (name op) " for client " (:client-id request))}]
    (if (= :draft-training-plan op)
      (let [ms (keep #(store/module store %) module-ids)]
        (assoc base
               :module-ids (vec module-ids)
               :total-hours (reduce + 0 (map :hours ms))))
      base)))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a training advisor. Given a request, propose an :op, the
   cited :module-ids in teaching order, :total-hours summed from the
   registered modules, an honest :confidence and a :stake. Never invent
   modules or hours.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
