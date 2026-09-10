(ns training.governor
  "TrainingGovernor — the independent safety/traceability layer for the
  ISCO-08 2424 community training actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. The training-specific
  twist: a training plan is validated DETERMINISTICALLY against the
  registered curriculum — module existence, prerequisite ordering and
  hour totals are arithmetic/graph facts, not opinions.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. curriculum basis  — every module a :draft-training-plan cites
                           must be REGISTERED and belong to this client
                           (no invented curriculum).
    4. prerequisite order — a cited module's prereqs must appear
                           EARLIER in the plan sequence. Scheduling the
                           advanced module first is a graph fact a human
                           approver cannot approve away.
    5. hours integrity   — the plan's :total-hours must equal the sum
                           of the cited modules' :hours (no padding).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :send-invitations (external-send to staff).
    7. low confidence (< `confidence-floor`)."
  (:require [training.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:send-invitations})

(defn- prereq-violations [modules-in-order]
  (let [positions (into {} (map-indexed (fn [i m] [(:module-id m) i]) modules-in-order))]
    (for [m modules-in-order
          p (:prereqs m)
          :let [mp (get positions (:module-id m))
                pp (get positions p)]
          :when (or (nil? pp) (>= pp mp))]
      {:rule :prerequisite-order
       :detail (str "module " (:module-id m) " の前提 " p
                    (if (nil? pp) " が計画に含まれていない" " が後に配置されている"))})))

(defn- hard-violations [{:keys [request proposal]} client-record store]
  (let [{:keys [op module-ids total-hours]} proposal
        plan? (= :draft-training-plan op)
        resolved (when plan? (mapv #(store/module store %) module-ids))
        unknown (when plan?
                  (keep-indexed (fn [i m] (when (nil? m) (nth module-ids i))) resolved))
        known (when plan? (filterv some? resolved))
        foreign (when plan?
                  (filter #(not= (:client-id %) (:client-id request)) known))
        all-known? (and plan? (empty? unknown) (empty? foreign))]
    (concat
     (cond-> []
       (nil? client-record)
       (conj {:rule :no-client :detail "未登録 client"})

       (not= :propose (:effect proposal))
       (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

       (and plan? (empty? module-ids))
       (conj {:rule :empty-plan :detail "計画にモジュールが 1 つも無い"})

       (and plan? (seq unknown))
       (conj {:rule :unknown-module
              :detail (str "未登録モジュール: " (vec unknown) "（カリキュラムの捏造禁止）")})

       (and plan? (seq foreign))
       (conj {:rule :module-wrong-client
              :detail (str "別 client のモジュール: " (mapv :module-id foreign))})

       (and all-known? total-hours
            (not= total-hours (reduce + 0 (map :hours known))))
       (conj {:rule :hours-mismatch
              :detail (str "total-hours " total-hours " ≠ モジュール合計 "
                           (reduce + 0 (map :hours known)))}))
     (when all-known? (prereq-violations known)))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `training.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        hard (vec (hard-violations {:request request :proposal proposal}
                                   client-record store))
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
