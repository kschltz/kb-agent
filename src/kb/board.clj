(ns kb.board
  "Kanban board model: reads/writes .kanban/ filesystem state with atomic operations.
   Pure maps throughout — no classes."
  (:require [kb.util :as u]
            [clojure.string :as str]
            [cheshire.core :as json]
            [babashka.process :as proc])
  (:import [java.nio.file Path]))

;; Forward declaration (move! defined after pull!)
(declare move!)

;; ── Key translation helpers ───────────────────────────────────
;;
;; YAML files on disk use snake_case for backward compatibility.
;; Internally we use kebab-case keywords.

(defn- snake->kebab
  "Convert a snake_case string or keyword to a kebab-case keyword."
  [k]
  (-> (name k)
      (str/replace "_" "-")
      keyword))


(defn- map-keys
  "Apply f to every key in m."
  [f m]
  (into {} (map (fn [[k v]] [(f k) v]) m)))

;; ── Card shape ────────────────────────────────────────────────
;;
;; {:id "001" :title "..." :lane "backlog" :priority 0
;;  :blocked false :blocked-reason "" :assigned-agent ""
;;  :branch "" :worktree "" :created-at 1234.0 :updated-at 1234.0
;;  :tags []}

(defn make-card
  "Construct a card map with defaults."
  [id title lane & {:keys [priority blocked blocked-reason assigned-agent
                           branch worktree created-at updated-at tags
                           pending-approval approved-by]
                    :or {priority 0 blocked false blocked-reason ""
                         assigned-agent "" branch "" worktree ""
                         pending-approval false approved-by ""
                         tags []}}]
  (let [now (u/now-epoch)]
    {:id               id
     :title            title
     :lane             lane
     :priority         priority
     :blocked          blocked
     :blocked-reason   blocked-reason
     :assigned-agent   assigned-agent
     :branch           branch
     :worktree         worktree
     :created-at       (or created-at now)
     :updated-at       (or updated-at now)
     :tags             tags
     :pending-approval pending-approval
     :approved-by      approved-by}))

(defn- card-from-yaml
  "Convert a YAML map (with snake_case keys as keywords or strings) to a card map."
  [data]
  ;; clj-yaml parses snake_case keys as keyword :blocked_reason etc
  (let [d (map-keys (fn [k] (snake->kebab k)) data)]
    {:id               (str (:id d ""))
     :title            (str (:title d ""))
     :lane             (str (:lane d ""))
     :priority         (or (:priority d) 0)
     :blocked          (boolean (:blocked d))
     :blocked-reason   (str (:blocked-reason d ""))
     :assigned-agent   (str (:assigned-agent d ""))
     :branch           (str (:branch d ""))
     :worktree         (str (:worktree d ""))
     :created-at       (double (or (:created-at d) (u/now-epoch)))
     :updated-at       (double (or (:updated-at d) (u/now-epoch)))
     :tags             (vec (or (:tags d) []))
     :pending-approval (boolean (:pending-approval d))
     :approved-by      (str (:approved-by d ""))}))

(defn- card->yaml-map
  "Convert a card map to snake_case keys for YAML serialization."
  [card]
  {"id"               (:id card)
   "title"            (:title card)
   "lane"             (:lane card)
   "priority"         (:priority card)
   "blocked"          (:blocked card)
   "blocked_reason"   (:blocked-reason card)
   "assigned_agent"   (:assigned-agent card)
   "branch"           (:branch card)
   "worktree"         (:worktree card)
   "created_at"       (:created-at card)
   "updated_at"       (:updated-at card)
   "tags"             (:tags card)
   "pending_approval" (:pending-approval card)
   "approved_by"      (:approved-by card)})

;; ── HistoryEntry shape ────────────────────────────────────────
;;
;; {:ts 1234.0 :role "system" :action "created" :content "" :agent-id "" :gate ""}

(defn make-history-entry
  "Build a history entry map."
  [role action & {:keys [content agent-id gate ts]
                  :or {content "" agent-id "" gate ""}}]
  (cond-> {:ts      (or ts (u/now-epoch))
           :role    role
           :action  action
           :content content}
    (not (str/blank? agent-id)) (assoc :agent-id agent-id)
    (not (str/blank? gate))     (assoc :gate gate)))

(defn- history-entry->json-map
  "Convert a history entry to a snake_case map for JSONL serialization.
   Only includes non-empty optional fields to match Python's to_dict behaviour."
  [entry]
  (cond-> {"ts"      (:ts entry)
           "role"    (:role entry)
           "action"  (:action entry)
           "content" (or (:content entry) "")}
    (not (str/blank? (:agent-id entry))) (assoc "agent_id" (:agent-id entry))
    (not (str/blank? (:gate entry)))     (assoc "gate" (:gate entry))))

(defn- json-map->history-entry
  "Convert a parsed JSONL map (string keys) to a kebab-case history entry."
  [m]
  (cond-> {:ts      (double (get m "ts" 0))
           :role    (get m "role" "")
           :action  (get m "action" "")
           :content (get m "content" "")}
    (get m "agent_id") (assoc :agent-id (get m "agent_id"))
    (get m "gate")     (assoc :gate (get m "gate"))))

;; ── GateResult shape ─────────────────────────────────────────
;;
;; {:gate "cmd" :passed true :output "..." :timestamp 1234.0}

(defn make-gate-result [gate passed output]
  {:gate      gate
   :passed    passed
   :output    output
   :timestamp (u/now-epoch)})

;; ── Board constructor ─────────────────────────────────────────

(defn make-board
  "Construct a board map from optional root path (Path or string).
   Loads config from board.yaml immediately."
  ([] (make-board nil))
  ([root]
   (let [kanban-root (if root
                       (u/->path root)
                       (u/find-root))
         project-root (.getParent kanban-root)
         config-path  (u/path-resolve kanban-root u/board-file)
         cards-dir    (u/path-resolve kanban-root u/cards-dir)
         worktrees    (u/path-resolve kanban-root u/worktrees-dir)
         config       (u/slurp-yaml config-path)]
     {:root         kanban-root
      :project-root project-root
      :config-path  config-path
      :cards-dir    cards-dir
      :worktrees-dir worktrees
      :config       config})))

;; ── Config / lane helpers ─────────────────────────────────────

(defn lanes [board]
  (get (:config board) "lanes" []))

(defn lane-by-name
  "Return the lane config map for `name`, or nil."
  [board name]
  (first (filter #(= (get % "name") name) (lanes board))))

(defn lane-names
  "Return ordered list of lane name strings."
  [board]
  (mapv #(get % "name") (lanes board)))

(defn first-lane
  "Return the name of the first lane."
  [board]
  (get (first (lanes board)) "name"))

(defn base-branch
  "Return configured base_branch (default: \"main\")."
  [board]
  (get (:config board) "base_branch" "main"))

(defn merge-strategy
  "Return configured merge_strategy (default: \"squash\")."
  [board]
  (get (:config board) "merge_strategy" "squash"))

;; ── Card directory helpers ─────────────────────────────────────

(defn find-card-dir
  "Find card directory by id prefix. Returns Path."
  [board card-id]
  (let [cdir (:cards-dir board)]
    (when (u/path-exists? cdir)
      (let [dirs (u/list-dirs cdir)
            match (first (filter #(str/starts-with?
                                   (.getName (.toFile ^Path %))
                                   (str card-id "-"))
                                 dirs))]
        (when match match)))))

(defn- find-card-dir!
  "Like find-card-dir but throws if not found."
  [board card-id]
  (or (find-card-dir board card-id)
      (throw (ex-info (str "Card '" card-id "' not found.") {:card-id card-id}))))

(defn create-card-dir
  "Create and return directory path for a new card."
  [board card-id slug]
  (let [d (u/path-resolve (:cards-dir board) (str card-id "-" slug))]
    (u/mkdirs d)
    d))

(defn next-id
  "Return next zero-padded 3-digit card id string."
  [board]
  (let [cdir (:cards-dir board)]
    (if-not (u/path-exists? cdir)
      "001"
      (let [existing (->> (u/list-dirs cdir)
                          (keep (fn [^Path d]
                                  (try (Integer/parseInt
                                        (first (str/split (.getName (.toFile d)) #"-")))
                                       (catch Exception _ nil)))))]
        (format "%03d" (inc (if (seq existing) (apply max existing) 0)))))))

;; ── Card CRUD ─────────────────────────────────────────────────

(defn load-card
  "Load and return a card map from disk."
  [board card-id]
  (let [d    (find-card-dir! board card-id)
        path (u/path-resolve d "meta.yaml")
        data (u/slurp-yaml path)]
    (card-from-yaml data)))

(defn save-card!
  "Persist card to disk (updates :updated-at). card-dir is optional.
   Returns the updated card map."
  ([board card] (save-card! board card nil))
  ([board card card-dir]
   (let [d       (or card-dir (find-card-dir! board (:id card)))
         updated (assoc card :updated-at (u/now-epoch))]
     (u/spit-yaml (u/path-resolve d "meta.yaml") (card->yaml-map updated))
     updated)))

(defn load-history
  "Load history entries for a card. Optionally filter by since (epoch float)."
  ([board card-id] (load-history board card-id nil))
  ([board card-id since]
   (let [d         (find-card-dir! board card-id)
         hist-path (u/path-resolve d "history.jsonl")
         raw       (u/slurp-json-lines hist-path)
         entries   (mapv json-map->history-entry raw)]
     (if since
       (filterv #(> (:ts %) since) entries)
       entries))))

(defn append-history!
  "Append a history entry to a card's history.jsonl."
  [board card-id entry]
  (let [d         (find-card-dir! board card-id)
        hist-path (u/path-resolve d "history.jsonl")
        line      (json/generate-string (history-entry->json-map entry))]
    (u/flock-append! hist-path line)))

(defn load-description
  "Return description.md content as string, or empty string."
  [board card-id]
  (let [d    (find-card-dir! board card-id)
        path (u/path-resolve d "description.md")]
    (if (u/path-exists? path)
      (slurp (str path))
      "")))

(defn save-description!
  "Write description.md atomically."
  [board card-id content]
  (let [d    (find-card-dir! board card-id)
        path (u/path-resolve d "description.md")]
    (u/atomic-write! path content)))

;; ── All cards ─────────────────────────────────────────────────

(defn all-cards
  "Return all cards sorted by directory name."
  [board]
  (let [cdir (:cards-dir board)]
    (if-not (u/path-exists? cdir)
      []
      (->> (u/list-dirs cdir)
           (filter (fn [^Path d]
                     (u/path-exists? (u/path-resolve d "meta.yaml"))))
           (keep (fn [^Path d]
                   (let [cid (first (str/split (.getName (.toFile d)) #"-"))]
                     (try (load-card board cid)
                          (catch Exception _ nil)))))
           vec))))

(defn cards-in-lane
  "Return cards in lane-name, sorted by (priority, created-at)."
  [board lane-name]
  (->> (all-cards board)
       (filter #(= (:lane %) lane-name))
       (sort-by (juxt :priority :created-at))
       vec))

;; ── Git / worktree operations ─────────────────────────────────

(defn- git-safe
  "Run a git command, returning {:exit int :out str :err str}.
   Never throws — catches babashka.process exceptions (non-zero exits)."
  [& {:keys [args cwd]}]
  (try
    (u/git :args args :cwd cwd)
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        {:exit (or (:exit d) 1)
         :out  (or (:out d) "")
         :err  (or (:err d) (ex-message e) "")}))
    (catch Exception e
      {:exit 1 :out "" :err (str e)})))

(defn create-worktree!
  "Create a git branch and worktree for a card.
   Returns [branch worktree-path-str]."
  [board card]
  (let [slug     (u/slugify (:title card))
        branch   (str "kb/" (:id card) "-" slug)
        wt-path  (u/path-resolve (:worktrees-dir board) (:id card))
        proj     (:project-root board)
        base     (base-branch board)]

    ;; Create branch from base — OK if already exists (returns non-zero exit in that case)
    (let [r (git-safe :args ["branch" branch base] :cwd proj)]
      (when (and (not= 0 (:exit r))
                 (not (str/includes? (:err r) "already exists")))
        (throw (ex-info (str "Failed to create branch: " (:err r))
                        {:branch branch}))))

    ;; Create worktree if missing
    (when-not (u/path-exists? wt-path)
      (u/mkdirs (:worktrees-dir board))
      (let [r (git-safe :args ["worktree" "add" (str wt-path) branch] :cwd proj)]
        (when (not= 0 (:exit r))
          (throw (ex-info (str "Failed to create worktree: " (:err r))
                          {:wt-path (str wt-path)})))))

    [branch (str wt-path)]))

(defn remove-worktree!
  "Remove worktree and optionally the branch."
  [board card & {:keys [delete-branch] :or {delete-branch false}}]
  (let [wt-path (if (not (str/blank? (:worktree card)))
                  (u/->path (:worktree card))
                  (u/path-resolve (:worktrees-dir board) (:id card)))
        proj    (:project-root board)]
    (when (u/path-exists? wt-path)
      (git-safe :args ["worktree" "remove" (str wt-path) "--force"] :cwd proj))
    (when (and delete-branch (not (str/blank? (:branch card))))
      (git-safe :args ["branch" "-D" (:branch card)] :cwd proj))))

(defn check-merge-conflicts!
  "Pre-check for merge conflicts. Throws ex-info with conflicted files if any."
  [board card]
  (let [proj   (:project-root board)
        base   (base-branch board)
        branch (:branch card)]

    ;; Checkout base branch
    (let [r (git-safe :args ["checkout" base] :cwd proj)]
      (when (not= 0 (:exit r))
        (throw (ex-info (str "Failed to checkout " base ": " (:err r)) {}))))

    ;; Trial merge — expected to fail (non-zero) when conflicts exist
    (let [r (git-safe :args ["merge" "--no-commit" "--no-ff" branch] :cwd proj)]
      (when (not= 0 (:exit r))
        ;; Find conflicted files
        (let [diff-r     (git-safe :args ["diff" "--name-only" "--diff-filter=U"] :cwd proj)
              conflicted (str/trim (:out diff-r))]
          ;; Always abort the trial merge
          (git-safe :args ["merge" "--abort"] :cwd proj)
          (if (not (str/blank? conflicted))
            (throw (ex-info (str "Merge conflict detected. Conflicted files:\n" conflicted)
                            {:conflicted-files conflicted}))
            (throw (ex-info (str "Merge test failed: " (:err r)) {})))))
      ;; No conflicts — abort trial so real merge can proceed
      (git-safe :args ["merge" "--abort"] :cwd proj))))

(defn merge-card!
  "Merge a card's branch into the base branch using the configured strategy."
  [board card]
  (let [strategy (merge-strategy board)
        branch   (:branch card)
        proj     (:project-root board)
        base     (base-branch board)]

    (check-merge-conflicts! board card)

    (cond
      (= strategy "squash")
      (do
        (let [r (git-safe :args ["checkout" base] :cwd proj)]
          (when (not= 0 (:exit r))
            (throw (ex-info (str "Failed to checkout " base ": " (:err r)) {}))))
        (let [r (git-safe :args ["merge" "--squash" branch] :cwd proj)]
          (when (not= 0 (:exit r))
            (git-safe :args ["checkout" "-"] :cwd proj)
            (throw (ex-info (str "Merge conflict: " (:err r)) {}))))
        (let [r (git-safe :args ["commit" "-m" (str "kb: " (:title card) " (#" (:id card) ")")] :cwd proj)]
          (when (not= 0 (:exit r))
            (throw (ex-info (str "Failed to commit merge: " (:err r)) {})))))

      (= strategy "merge")
      (do
        (let [r (git-safe :args ["checkout" base] :cwd proj)]
          (when (not= 0 (:exit r))
            (throw (ex-info (str "Failed to checkout " base ": " (:err r)) {}))))
        (let [r (git-safe :args ["merge" branch "-m" (str "kb: " (:title card) " (#" (:id card) ")")] :cwd proj)]
          (when (not= 0 (:exit r))
            (git-safe :args ["merge" "--abort"] :cwd proj)
            (throw (ex-info (str "Merge conflict: " (:err r)) {})))))

      (= strategy "rebase")
      (do
        (let [wt  (u/->path (:worktree card))
              r   (git-safe :args ["rebase" base] :cwd wt)]
          (when (not= 0 (:exit r))
            (git-safe :args ["rebase" "--abort"] :cwd wt)
            (throw (ex-info (str "Rebase conflict: " (:err r)) {}))))
        (let [r (git-safe :args ["checkout" base] :cwd proj)]
          (when (not= 0 (:exit r))
            (throw (ex-info (str "Failed to checkout " base ": " (:err r)) {}))))
        (let [r (git-safe :args ["merge" "--ff-only" branch] :cwd proj)]
          (when (not= 0 (:exit r))
            (throw (ex-info (str "Fast-forward failed: " (:err r)) {})))))

      :else
      (throw (ex-info (str "Unknown merge strategy: " strategy) {:strategy strategy})))))

(defn- diff-internal
  "Run git diff with optional extra args. Returns diff string or error."
  [board card-id extra-args]
  (let [card (load-card board card-id)]
    (if (str/blank? (:branch card))
      "(no branch)"
      (let [r (git-safe :args (into ["diff"] (concat extra-args
                                                    [(str (base-branch board) "..." (:branch card))]))
                        :cwd (:project-root board))]
        (if (= 0 (:exit r)) (:out r) (:err r))))))

(defn get-diff
  "Get diff of card's branch vs base branch."
  [board card-id]
  (diff-internal board card-id []))

(defn get-diff-stat
  "Get diff --stat for a card's branch vs base branch."
  [board card-id]
  (diff-internal board card-id ["--stat"]))

;; ── Board operations ──────────────────────────────────────────

(defn create-card!
  "Create a new card. Returns the card map."
  [board title & {:keys [lane tags priority description]
                  :or {tags [] priority 0 description ""}}]
  (let [card-id  (next-id board)
        slug     (u/slugify title)
        lane-n   (or lane (first-lane board))]
    (when-not (some #{lane-n} (lane-names board))
      (throw (ex-info (str "Lane '" lane-n "' not found. Available: " (lane-names board))
                      {:lane lane-n})))
    (let [card     (make-card card-id title lane-n
                              :priority priority
                              :tags (or tags []))
          card-dir (create-card-dir board card-id slug)]
      ;; Persist card to meta.yaml (save-card! writes atomically via spit-yaml)
      (save-card! board card card-dir)
      (when (not (str/blank? description))
        (u/atomic-write! (u/path-resolve card-dir "description.md") description))
      (append-history! board card-id
                       (make-history-entry "system" "created"
                                           :content (str "Card created in lane '" lane-n "'")))
      card)))

(defn- find-available-card
  "Find the first unblocked, unassigned card across all lanes,
   preferring lanes earlier in the workflow (lower index)."
  [board]
  (let [all (all-cards board)]
    (first (sort-by (juxt :priority :created-at)
                    (filter #(and (not (:blocked %))
                                  (str/blank? (:assigned-agent %)))
                            all)))))

(defn pull!
  "Pull the next available card. Creates worktree + branch.
   If :lane is specified, also moves the card to that lane.
   Returns card map or nil if no card available."
  [board & {:keys [agent lane]}]
  (let [card (find-available-card board)]
    (when card
      (let [agent-id   (or (and (not (str/blank? agent)) agent)
                           (str "agent-" (subs (str (java.util.UUID/randomUUID)) 0 6)))
            ;; Move card to target lane first (before creating worktree, so gates see correct state)
            _          (when (and lane (not= (:lane card) lane))
                         (let [[ok? msg _gates] (move! board (:id card) lane :agent agent-id)]
                           (when-not ok?
                             (throw (ex-info (str "Cannot move card to " lane ": " msg) {})))))
            ;; Re-load card after move to get updated lane
            card'      (if (and lane (not= (:lane card) lane))
                         (load-card board (:id card))
                         card)
            [branch wt-path] (create-worktree! board card')
            updated    (assoc card'
                              :assigned-agent agent-id
                              :branch branch
                              :worktree wt-path)]
        (save-card! board updated)
        (append-history! board (:id card')
                         (make-history-entry "system" "pulled"
                                             :content (str "Assigned to " agent-id
                                                           ". Branch: " branch
                                                           ". Worktree: " wt-path)
                                             :agent-id agent-id))
        updated))))

;; ── Gate execution ────────────────────────────────────────────

(defn run-gate
  "Run a gate command with card context in env vars.
   Returns a gate-result map."
  [board gate-cmd card]
  (let [card-dir  (find-card-dir board (:id card))
        extra-env {"KB_CARD_ID"     (:id card)
                   "KB_CARD_TITLE"  (:title card)
                   "KB_CARD_LANE"   (:lane card)
                   "KB_CARD_DIR"    (str card-dir)
                   "KB_WORKTREE"    (or (:worktree card) "")
                   "KB_BRANCH"      (or (:branch card) "")
                   "KB_BASE_BRANCH" (base-branch board)}
        cwd       (if (and (:worktree card)
                           (not (str/blank? (:worktree card)))
                           (u/path-exists? (u/->path (:worktree card))))
                    (:worktree card)
                    (str (:project-root board)))]
    (try
      (let [result (apply proc/shell [{:out :string :err :string :extra-env extra-env :dir cwd :continue true}
                                         "sh" "-c" gate-cmd])
            passed (= 0 (:exit result))
            output (str/trim (str (:out result) (:err result)))]
        (make-gate-result gate-cmd passed output))
      (catch java.util.concurrent.TimeoutException _
        (make-gate-result gate-cmd false "Gate timed out (120s)"))
      (catch Exception e
        (make-gate-result gate-cmd false (str e))))))

(defn move!
  "Move card to target-lane. Runs quality gates.
   Returns [success? message gate-results]."
  [board card-id target-lane & {:keys [agent] :or {agent ""}}]
  (if-not (some #{target-lane} (lane-names board))
    [false (str "Lane '" target-lane "' not found.") []]
    (let [card        (load-card board card-id)
          source-lane (:lane card)]
      (cond
        (= source-lane target-lane)
        [false (str "Card is already in '" target-lane "'.") []]

        (:blocked card)
        [false (str "Card " card-id " is blocked: " (:blocked-reason card)) []]

        (:pending-approval card)
        [false (str "Card " card-id " is pending approval. Run `kb approve " card-id "` first.") []]

        :else
        (let [target-config (lane-by-name board target-lane)
              max-wip       (get target-config "max_wip")
              current-wip   (count (cards-in-lane board target-lane))]
          (if (and max-wip (>= current-wip max-wip))
            [false (str "Lane '" target-lane "' is at WIP limit (" max-wip ").") []]
            (let [gate-key (str "gate_from_" source-lane)
                  gates    (get target-config gate-key [])
                  gate-results
                  (reduce (fn [acc gate-cmd]
                            (let [gr (run-gate board gate-cmd card)]
                              (if (:passed gr)
                                (conj acc gr)
                                ;; Log failure then short-circuit via reduced
                                (do
                                  (append-history! board card-id
                                                   (make-history-entry "system" "gate_fail"
                                                                       :content (str "Gate failed: " (:output gr))
                                                                       :agent-id agent
                                                                       :gate gate-cmd))
                                  (reduced (conj acc gr))))))
                          []
                          gates)]
              ;; Check if last gate failed
              (if (and (seq gate-results)
                       (not (:passed (last gate-results))))
                (let [gr (last gate-results)]
                  [false (str "Gate failed: " (:gate gr) "\n" (:output gr)) gate-results])
                ;; All gates passed — check if target lane requires approval
                (if (get target-config "requires_approval")
                  ;; Move to target lane but mark as pending approval
                  (do
                    (let [card2 (assoc card :lane target-lane
                                          :pending-approval true
                                          :assigned-agent (:assigned-agent card))]
                      (save-card! board card2))
                    (append-history! board card-id
                                     (make-history-entry "system" "moved"
                                                         :content (str "Moved from '" source-lane "' to '" target-lane "' (pending approval)")
                                                         :agent-id agent))
                    (doseq [gr gate-results]
                      (append-history! board card-id
                                       (make-history-entry "system" "gate_pass"
                                                           :content "Gate passed"
                                                           :gate (:gate gr))))
                    (append-history! board card-id
                                     (make-history-entry "system" "approval_required"
                                                         :content (str "Awaiting approval to complete move to '" target-lane "'")
                                                         :agent-id agent))
                    [true (str "Moved to '" target-lane "' — awaiting approval. Run `kb approve " card-id "` to approve.") gate-results])
                  ;; No approval needed — handle on_enter: merge
                  (let [on-enter (get target-config "on_enter")]
                  (if (and (= on-enter "merge") (not (str/blank? (:branch card))))
                    (try
                      (merge-card! board card)
                      (append-history! board card-id
                                       (make-history-entry "system" "merged"
                                                           :content (str "Branch " (:branch card)
                                                                         " merged into " (base-branch board)
                                                                         " (" (merge-strategy board) ")")))
                      (remove-worktree! board card :delete-branch true)
                      (let [card2 (assoc card :worktree "" :branch "" :lane target-lane :assigned-agent "")]
                        (save-card! board card2)
                        (append-history! board card-id
                                         (make-history-entry "system" "moved"
                                                             :content (str "Moved from '" source-lane "' to '" target-lane "'")
                                                             :agent-id agent))
                        (doseq [gr gate-results]
                          (append-history! board card-id
                                           (make-history-entry "system" "gate_pass"
                                                               :content "Gate passed"
                                                               :gate (:gate gr))))
                        [true (str "Moved to '" target-lane "'.") gate-results])
                      (catch Exception e
                        (append-history! board card-id
                                         (make-history-entry "system" "merge_fail"
                                                             :content (str e)))
                        [false (str "Merge failed: " e) gate-results]))
                    ;; No merge needed — just move
                    (do
                      (let [card2 (assoc card :lane target-lane :assigned-agent "")]
                        (save-card! board card2))
                      (append-history! board card-id
                                       (make-history-entry "system" "moved"
                                                           :content (str "Moved from '" source-lane "' to '" target-lane "'")
                                                           :agent-id agent))
                      (doseq [gr gate-results]
                        (append-history! board card-id
                                         (make-history-entry "system" "gate_pass"
                                                             :content "Gate passed"
                                                             :gate (:gate gr))))
                      [true (str "Moved to '" target-lane "'.") gate-results]))))))))))))

(defn reject!
  "Move card back to previous lane."
  [board card-id & {:keys [reason agent] :or {reason "" agent ""}}]
  (let [card      (load-card board card-id)
        names     (lane-names board)
        idx       (.indexOf names (:lane card))
        prev-lane (nth names (max 0 (dec idx)))
        updated   (assoc card :lane prev-lane :assigned-agent ""
                               :pending-approval false :approved-by "")]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "system" "rejected"
                                         :content (str "Rejected to '" prev-lane "': " reason)
                                         :agent-id agent))
    updated))

(defn approve!
  "Approve a card that is pending approval. Clears the pending flag and logs approval."
  [board card-id & {:keys [agent] :or {agent ""}}]
  (let [card (load-card board card-id)]
    (when-not (:pending-approval card)
      (throw (ex-info (str "Card " card-id " is not pending approval.") {})))
    (let [approver (if (str/blank? agent) "human" agent)
          updated  (assoc card :pending-approval false :approved-by approver)]
      (save-card! board updated)
      (append-history! board card-id
                       (make-history-entry "system" "approved"
                                           :content (str "Approved by " approver)
                                           :agent-id approver))
      updated)))

(defn block!
  "Block a card with a reason."
  [board card-id reason]
  (let [card    (load-card board card-id)
        updated (assoc card :blocked true :blocked-reason reason)]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "system" "blocked" :content reason))
    updated))

(defn unblock!
  "Unblock a card."
  [board card-id]
  (let [card    (load-card board card-id)
        updated (assoc card :blocked false :blocked-reason "")]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "system" "unblocked" :content ""))
    updated))

(defn add-note!
  "Add a note to a card's history."
  [board card-id message & {:keys [agent] :or {agent "human"}}]
  (let [role (if (= agent "human") "human" "agent")]
    (append-history! board card-id
                     (make-history-entry role "note"
                                         :content message
                                         :agent-id (if (= role "agent") agent "")))))

(defn cleanup!
  "Remove worktree for a card and optionally delete the branch."
  [board card-id & {:keys [delete-branch] :or {delete-branch false}}]
  (let [card    (load-card board card-id)
        _       (remove-worktree! board card :delete-branch delete-branch)
        updated (cond-> (assoc card :worktree "")
                  delete-branch (assoc :branch ""))]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "system" "cleanup"
                                         :content (str "Worktree removed. Branch deleted: " delete-branch)))))

;; ── Recovery ──────────────────────────────────────────────────

(defn recover!
  "Detect and optionally clean orphaned worktrees, stale card refs, and dangling kb/ branches.
   Returns map with :orphaned-worktrees, :stale-card-refs, :orphaned-branches, :cleaned."
  [board & {:keys [clean delete-branches] :or {clean false delete-branches false}}]
  (let [all    (all-cards board)
        known  (into #{} (map :id all))
        proj   (:project-root board)
        wt-dir (:worktrees-dir board)

        ;; 1. Worktrees with no matching card
        orphaned-wts
        (when (u/path-exists? wt-dir)
          (->> (u/list-dirs wt-dir)
               (filter (fn [^Path d]
                         (not (contains? known (.getName (.toFile d))))))
               (mapv (fn [^Path d]
                       {:id   (.getName (.toFile d))
                        :path (str d)}))))

        ;; 2. Cards referencing missing worktrees
        stale-refs
        (->> all
             (filter #(and (not (str/blank? (:worktree %)))
                           (not (u/path-exists? (u/->path (:worktree %))))))
             (mapv #(hash-map :id (:id %) :title (:title %) :worktree (:worktree %))))

        ;; 3. Branches starting with kb/ that have no matching card
        branch-r (git-safe :args ["branch" "--list" "kb/*"] :cwd proj)
        orphaned-branches
        (when (= 0 (:exit branch-r))
          (->> (str/split-lines (:out branch-r))
               (map str/trim)
               (map #(str/replace % #"^\* " ""))
               (filter #(str/starts-with? % "kb/"))
               (keep (fn [branch]
                       (let [bid (first (str/split (subs branch 3) #"-"))]
                         (when-not (contains? known bid)
                           {:branch branch :card-id bid}))))
               vec))

        result {:orphaned-worktrees  (or orphaned-wts [])
                :stale-card-refs     stale-refs
                :orphaned-branches   (or orphaned-branches [])
                :cleaned             []}]

    (if-not clean
      result
      (let [cleaned (atom [])]

        ;; Clean orphaned worktrees
        (doseq [wt (:orphaned-worktrees result)]
          (let [wt-path (u/->path (:path wt))]
            (when (u/path-exists? wt-path)
              (let [r (git-safe :args ["worktree" "remove" (:path wt) "--force"] :cwd proj)]
                (if (= 0 (:exit r))
                  (swap! cleaned conj (str "Removed orphaned worktree: " (:id wt) " (" (:path wt) ")"))
                  (do
                    (git-safe :args ["worktree" "prune"] :cwd proj)
                    (swap! cleaned conj
                           (if-not (u/path-exists? wt-path)
                             (str "Pruned orphaned worktree: " (:id wt) " (" (:path wt) ")")
                             (str "Failed to remove worktree: " (:id wt) " (" (:path wt) ")"))))))))

        ;; Clear stale worktree references in card metadata
        (doseq [ref (:stale-card-refs result)]
          (try
            (let [card  (load-card board (:id ref))
                  card2 (assoc card :worktree "")
                  ;; Check if branch still exists (rev-parse returns non-zero if branch missing)
                  has-branch (and (not (str/blank? (:branch card)))
                                  (let [r (git-safe :args ["rev-parse" "--verify" (:branch card)] :cwd proj)]
                                    (= 0 (:exit r))))
                  card3 (if has-branch card2 (assoc card2 :branch ""))]
              (save-card! board card3)
              (swap! cleaned conj
                     (if has-branch
                       (str "Cleared stale worktree ref for card " (:id ref))
                       (str "Cleared stale refs for card " (:id ref) ": worktree + branch"))))
            (catch Exception e
              (swap! cleaned conj (str "Error clearing refs for card " (:id ref) ": " e)))))

        ;; Delete orphaned branches if requested
        (when delete-branches
          (doseq [ob (:orphaned-branches result)]
            (let [r (git-safe :args ["branch" "-D" (:branch ob)] :cwd proj)]
              (swap! cleaned conj
                     (if (= 0 (:exit r))
                       (str "Deleted orphaned branch: " (:branch ob))
                       (str "Failed to delete branch " (:branch ob) ": " (str/trim (:err r))))))))

        (assoc result :cleaned @cleaned))))))

;; ── Context generation ─────────────────────────────────────────

(defn get-context
  "Generate the full context string for a sub-agent system prompt."
  [board card-id]
  (let [agent-command (get (:config board) "agent_command" "")]
    (when (str/blank? agent-command)
      (throw (ex-info
              (str "No agent_command template configured in board.yaml. "
                   "Add an agent_command field to .kanban/board.yaml. Example:\n"
                   "  agent_command: 'claude --system-prompt \"$(kb context {card_id})\" --cwd {worktree}'")
              {})))
    (let [card      (load-card board card-id)
          desc      (load-description board card-id)
          history   (load-history board card-id)
          diff-stat (get-diff-stat board card-id)
          base      (base-branch board)
          names     (lane-names board)
          idx       (.indexOf names (:lane card))

          next-gates (when (and (>= idx 0) (< (inc idx) (count names)))
                       (let [next-lane   (nth names (inc idx))
                             next-config (lane-by-name board next-lane)
                             gate-key    (str "gate_from_" (:lane card))]
                         (get next-config gate-key [])))

          lines (-> [(str "# Task: " (:title card))
                     (str "Card ID: " (:id card))
                     (str "Lane: " (:lane card))
                     (str "Branch: " (:branch card))
                     (str "Worktree: " (:worktree card))
                     (str "Base branch: " base)
                     ""]
                    (into (when (not (str/blank? desc))
                             ["## Description" "" (str/trim desc) ""]))
                    (into (when (seq next-gates)
                             (into [(str "## Gates to pass (moving to '" (nth names (inc idx)) "')") ""]
                                   (mapv #(str "- `" % "`") next-gates))))
                    (into (when (and diff-stat (not (str/blank? diff-stat))
                                     (not (str/includes? diff-stat "(no branch)")))
                             ["## Current changes" "" "```" (str/trim diff-stat) "```" ""]))
                    (into (when (seq history)
                             (into ["## History" ""]
                                   (mapv (fn [entry]
                                           (let [ts       (u/fmt-time (:ts entry))
                                                 role     (:role entry "?")
                                                 action   (:action entry "?")
                                                 content  (:content entry "")
                                                 agent-id (:agent-id entry "")
                                                 gate     (:gate entry "")
                                                 parts    (cond-> [(str "[" ts "]") (str role "/" action)]
                                                            (not (str/blank? agent-id)) (conj (str "(agent: " agent-id ")"))
                                                            (not (str/blank? gate))     (conj (str "(gate: " gate ")")))]
                                             (str (str/join " " parts) ": " content)))
                                         (take-last 30 history))))))]

      (str/join "\n"
                (into lines
                       ["## Instructions"
                        ""
                        "You are working on this card. Your working directory is the git worktree for this card."
                        "Use these commands to interact with the board:"
                        ""
                        (str "- `kb note " card-id " \"<message>\"` -- log your thinking or progress")
                        (str "- `kb move " card-id " <lane>` -- attempt to move the card forward (runs gates)")
                        (str "- `kb log " card-id "` -- check for new human notes or instructions")
                        (str "- `kb diff " card-id "` -- see your changes vs the base branch")
                        ""
                        "Before each major step, check `kb log` for new human instructions."
                        "When you believe the task is complete and tests pass, move the card to the next lane."])))))

;; ── Init board ────────────────────────────────────────────────

(defn init-board!
  "Initialize a new .kanban/ directory in a git repo.
   Returns a board map."
  ([] (init-board! "." nil))
  ([path] (init-board! path nil))
  ([path template]
   (let [project-root (.toAbsolutePath (u/->path path))
         ;; Verify git repo — use git-safe since non-zero exit = not a repo
         r (git-safe :args ["rev-parse" "--git-dir"] :cwd project-root)]
     (when (not= 0 (:exit r))
       (throw (ex-info "Not a git repository. Initialize git first: `git init`" {})))

     ;; Detect current branch
     (let [branch-r (git-safe :args ["rev-parse" "--abbrev-ref" "HEAD"] :cwd project-root)
           base     (or (not-empty (str/trim (:out branch-r))) "main")
           root     (u/path-resolve project-root u/kanban-dir)]

       (when (u/path-exists? root)
         (throw (ex-info (str root " already exists.") {})))

       (u/mkdirs root)
       (u/mkdirs (u/path-resolve root u/cards-dir))
       (u/mkdirs (u/path-resolve root u/worktrees-dir))

       (let [tmpl (or template
                      {"project"        (str (.getFileName (.normalize project-root)))
                       "base_branch"    base
                       "merge_strategy" "squash"
                       "agent_command"  "claude --system-prompt \"$(kb context {card_id})\" --cwd {worktree}"
                       "lanes"
                       [{"name" "backlog"}
                        {"name"            "in-progress"
                         "max_wip"         5
                         "max_parallelism" 2}
                        {"name"  "review"
                         "max_wip" 3}
                        {"name"    "done"
                         "on_enter" "merge"}]})]
         (u/spit-yaml (u/path-resolve root u/board-file) tmpl)
         (make-board root))))))
