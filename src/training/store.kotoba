(ns training.store
  "SSoT for the ISCO-08 2424 community training actor (itonami actor
  pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client   — a registered organization (:client-id, :name)
    module   — a registered curriculum module {:module-id :client-id
               :title :hours :prereqs [module-id ...]}. The ONLY
               admissible building block of a training plan — a plan
               citing unregistered modules is an invented curriculum.
    record   — a committed operating record (training plan, completion
               record, sent invitations) — written ONLY via
               commit-record!.
    ledger   — append-only audit trail of every proposal/verdict/
               disposition, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (module [s module-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-module! [s m])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (module [_ module-id] (get-in @a [:modules module-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-module! [s m]
    (swap! a assoc-in [:modules (:module-id m)] m) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :modules {} :records [] :ledger []}
                                   seed)))))
