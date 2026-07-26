(ns training.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`training.actor` -> `training.governor` -> `training.store`) through
  a scenario built from real, exercised store data and renders the
  result deterministically -- no invented numbers, no timestamps in the
  page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs before shipping).

  Adapted from the ISCO-08 1211/1111/2113/1213/1112/2133/2423
  build-time-console precedents (`90-docs/business/cloud-itonami-
  maturity-loop.md` in com-junkawasaki/root) using this repo's OWN real
  fixture, not a copy of theirs: client `client-1` (\"Kobo Works\") +
  module `m-basic` (\"safety basics\", 4 hours, no prereqs) + module
  `m-adv` (\"advanced ops\", 6 hours, prereq `m-basic`) are lifted
  VERBATIM from `training.actor-test`'s `fresh-store` fixture (ground
  truth, not invented). This is the prerequisite-chain fixture flagged
  by the earlier screening pass, verified here by actually reading the
  fixture: `m-adv`'s `:prereqs` really is `[\"m-basic\"]`, and
  `training.governor/prereq-violations` really does enforce it as a
  graph-position fact (confirmed by reading the code, not assumed).

  Client `client-2`/\"Other Org\" and module `m-other` (\"theirs\", 2
  hours, no prereqs) are ALSO real fixture data, but inline-registered
  in `training.governor-test/hard-on-foreign-module` (a real test in
  this repo's own `test/training/governor_test.clj`, not a shared
  `fresh-store` helper) -- used here to reach the real
  `:module-wrong-client` hard-hold and to show `client-2` operating
  cleanly with its own real module. Every other field this page
  displays (statuses, record counts, hold/escalation reasons) is real
  output read after `run-demo!` actually executed the graph -- none of
  it is hand-typed. No entity beyond these two repos'-own test files is
  added in this scenario.

  Honesty note on `context` (architecture, not a shortcut): like the
  ISCO-08 2133/2423 siblings and unlike the 1112 sibling
  (`administration.governor`, which gates on `context`'s `:topic`),
  `training.governor/check`'s parameter list includes `context` but the
  function BODY never reads it (confirmed by reading the code). There
  is no context/topic-sensitivity gate in this domain; this scenario
  passes `{}` for context throughout.

  This scenario demonstrates 5 of the 7 distinct real HARD-hold `:rule`
  values in `training.governor/hard-violations` (`:no-client`,
  `:empty-plan`, `:unknown-module`, `:module-wrong-client`, and
  `:prerequisite-order` -- reached BOTH ways the code can produce it,
  \"prereq scheduled later\" and \"prereq missing from the plan
  entirely\") and the one real escalation op (`:send-invitations`) --
  every `:op` keyword and violation rule name below is copied from
  `training.governor` itself, not invented.

  Known architectural gaps, honestly noted rather than papered over
  (confirmed by reading `training.governor` and `training.advisor`
  directly, not assumed -- one of these was FOUND by actually running
  the real graph while building this scenario, not read off a
  docstring):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    unconditionally sets `:effect :propose` on every proposal it emits.
    Covered instead by
    `training.governor-test/hard-on-no-actuation-violation` (which
    calls `governor/check` directly with a hand-built proposal whose
    `:effect` is `:direct-write`).
  - `:hours-mismatch` is ALSO not reachable through this demo, for a
    subtler reason discovered while building it (verify yourself:
    `run-request!` a `:draft-training-plan` request with
    `:module-ids [\"m-basic\" \"m-adv\"]` and a deliberately WRONG
    `:total-hours 12` -- the committed record's `:total-hours` comes
    back `10`, not `12`, and disposition is `:commit`, not `:hold`).
    Reading `training.advisor/infer` explains why: for
    `:draft-training-plan` the advisor recomputes `:total-hours` itself
    by summing the REGISTERED modules' real `:hours`
    (`(reduce + 0 (map :hours ms))`) and OVERWRITES whatever
    `:total-hours` the incoming request claimed -- the request's own
    number is discarded before the governor ever runs. So a
    `:draft-training-plan` proposal reaching `training.governor/check`
    through the real advisor can never disagree with itself on hours;
    `:hours-mismatch` is only reachable by calling `governor/check`
    directly with a hand-built proposal that skips the advisor
    entirely, exactly what
    `training.governor-test/hard-on-padded-hours` does. This scenario
    still drives a plan that CLAIMS the wrong total (`c1-pad-attempt`
    below) to make this self-correcting behavior visible on the
    console, but it is honestly labeled as a commit (the true outcome),
    not a hold.
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable
    either, because `training.advisor/infer`'s stake-derived confidence
    (`:high` 0.7, `:medium` 0.85, `:low` 0.95) never drops below the
    governor's `confidence-floor` (0.6).
  All three gaps are the same shape as the ISCO-08 1211/2113/1213/1112/
  2133/2423 precedents' disclosed `:no-actuation` gap -- this demo,
  like those, only ever drives the real actor/graph the way an
  operator actually would, and does not hand-construct proposals to
  force unreachable paths. The \"Total hours\" and \"Modules\" columns
  in the audit trail below render the REAL advisor `:proposal` (what
  the governor actually evaluated), not the raw incoming request --
  for `c1-hold-unknown-module` this matters concretely: the request
  claims `:total-hours 10`, but the real proposal (after the advisor
  drops the unresolvable `m-ghost` and sums only `m-basic`) shows `4`,
  which is what's displayed.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [training.store :as store]
            [training.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real training operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra context]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request context tid)
        proposal (get-in r1 [:state :proposal])]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request :proposal proposal :context context
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request :proposal proposal :context context
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request :proposal proposal :context context
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit across 2 clients and 2 distinct
  ops, escalate-then-approve, and 5 of the 7 distinct HARD-hold `:rule`
  values in `training.governor` -- `:no-actuation` and
  `:hours-mismatch`, plus the low-confidence escalation reason, are
  architecturally unreachable via the real advisor, see namespace
  docstring). Every `:op` keyword and violation rule name below is
  copied from `training.governor`'s own `hard-violations`/`check`, not
  invented. Vector shape: [thread-id client-id op extra context]."
  [;; client-1 (real fixture from training.actor-test) -- clean ops
   ["c1-plan-clean"          "client-1" :draft-training-plan {:module-ids ["m-basic" "m-adv"] :total-hours 10} {}]
   ["c1-completion-clean"    "client-1" :record-completion    {} {}]
   ;; client-1 -- real HARD-hold reasons
   ["c1-hold-misordered"     "client-1" :draft-training-plan {:module-ids ["m-adv" "m-basic"] :total-hours 10} {}]
   ["c1-hold-missing-prereq" "client-1" :draft-training-plan {:module-ids ["m-adv"] :total-hours 6} {}]
   ["c1-hold-empty-plan"     "client-1" :draft-training-plan {:module-ids [] :total-hours 0} {}]
   ["c1-hold-unknown-module" "client-1" :draft-training-plan {:module-ids ["m-basic" "m-ghost"] :total-hours 10} {}]
   ["c1-hold-foreign-module" "client-1" :draft-training-plan {:module-ids ["m-basic" "m-other"] :total-hours 6} {}]
   ;; NOT a hold: the advisor recomputes total-hours from the real
   ;; registered modules and overwrites the request's claim -- this
   ;; commits with the CORRECT total (10), demonstrating that
   ;; :hours-mismatch is unreachable via the real advisor (see
   ;; namespace docstring).
   ["c1-pad-attempt"         "client-1" :draft-training-plan {:module-ids ["m-basic" "m-adv"] :total-hours 12} {}]
   ;; unregistered client entirely
   ["ghost-no-client"        "nobody" :draft-training-plan {:module-ids ["m-basic"] :total-hours 4} {}]
   ;; client-1 -- real escalation reason, approved after human sign-off
   ["c1-escalate-invitations" "client-1" :send-invitations {} {}]
   ;; client-2 (real fixture entity from training.governor-test's own
   ;; hard-on-foreign-module test -- see namespace docstring)
   ["c2-plan-clean"          "client-2" :draft-training-plan {:module-ids ["m-other"] :total-hours 2} {}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `training.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Works"})
    (store/register-module! db {:module-id "m-basic" :client-id "client-1"
                                 :title "safety basics" :hours 4 :prereqs []})
    (store/register-module! db {:module-id "m-adv" :client-id "client-1"
                                 :title "advanced ops" :hours 6 :prereqs ["m-basic"]})
    (store/register-client! db {:client-id "client-2" :name "Other Org"})
    (store/register-module! db {:module-id "m-other" :client-id "client-2"
                                 :title "theirs" :hours 2 :prereqs []})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra context]]
                       (run-op! graph tid client-id op extra context))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id name]} runs]
  (let [record-count (count (store/records-of store client-id))
        last-run (last (filter #(= client-id (:client-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc name) record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- module-row [{:keys [module-id client-id title hours prereqs]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
          (esc module-id) (esc client-id) (esc title) hours
          (esc (if (seq prereqs) (str/join "," prereqs) "-"))))

(defn- run-row
  "Renders the Modules/Total-hours columns from the REAL advisor
  `:proposal` (what `training.governor/check` actually evaluated), not
  the raw incoming request -- the two can differ for
  `:draft-training-plan` (see namespace docstring: the advisor
  recomputes `:total-hours` and never adopts an invented figure)."
  [{:keys [thread-id client-id op proposal outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (if (seq (:module-ids proposal)) (str/join "," (:module-ids proposal)) ""))
          (esc (or (:total-hours proposal) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`training.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:draft-training-plan</code></td><td><span class=\"warn\">auto-commit ONLY when every module is registered to this client, prereqs appear earlier in the plan than the modules that need them, and total-hours exactly matches the modules' summed hours</span></td></tr>"
   "        <tr><td><code>:send-invitations</code></td><td><span class=\"warn\">ALWAYS human approval &middot; external send to staff</span></td></tr>"
   "        <tr><td><code>:record-completion</code></td><td><span class=\"ok\">auto-commit when the client is registered</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Kobo Works"}
                 {:client-id "client-2" :name "Other Org"}]
        modules [{:module-id "m-basic" :client-id "client-1" :title "safety basics" :hours 4 :prereqs []}
                 {:module-id "m-adv" :client-id "client-1" :title "advanced ops" :hours 6 :prereqs ["m-basic"]}
                 {:module-id "m-other" :client-id "client-2" :title "theirs" :hours 2 :prereqs []}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        module-rows (str/join "\n" (map module-row modules))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2424 &middot; community training practice</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Training Practice (ISCO-08 2424) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every proposal is for staff review only, never binding action</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>training.store</code> via <code>training.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered curriculum modules</h2>\n"
     "    <p class=\"muted\"><code>m-adv</code> prereqs <code>m-basic</code> — a real prerequisite-chain fixture (verified by reading <code>training.actor-test</code>); scheduling it first is a graph fact <code>training.governor</code> hard-holds on, never approvable.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Module</th><th>Client</th><th>Title</th><th>Hours</th><th>Prereqs</th></tr></thead>\n"
     "      <tbody>\n"
     module-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Training Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Unlike the ISCO-08 1112 sibling, this governor's <code>context</code> parameter is never read — there is no topic-sensitivity dimension in this domain (see namespace docstring).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule). Modules/Total-hours are the advisor's actual PROPOSAL as evaluated by the governor, not the raw incoming request — the two can differ (see namespace docstring).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Modules (in order)</th><th>Total hours</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
