(ns kb.board
  "Kanban board model: reads/writes .kanban/ filesystem state with atomic operations.
   Pure maps throughout — no classes."
  (:require [kb.util :as u]
            [clojure.string :as str]
            [cheshire.core :as json]
            [babashka.process :as proc])
  (:import [java.nio.file Path]))

;; Forward declaration (move! defined after pull!)
(declare move! deps-satisfied? all-cards find-card-dir! save-card! append-history! lane-by-name)

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
                           pending-approval approved-by
                           pending-question last-heartbeat
                           depends-on]
                    :or {priority 0 blocked false blocked-reason ""
                         assigned-agent "" branch "" worktree ""
                         pending-approval false approved-by ""
                         pending-question nil
                         last-heartbeat nil
                         tags [] depends-on []}}]
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
     :approved-by      approved-by
     :pending-question (or pending-question "")
     :last-heartbeat   last-heartbeat
     :depends-on       depends-on}))

(defn- card-from-yaml
  "Convert a YAML map (with snake_case keys as keywords or strings) to a card map."
  [data]
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
     :approved-by      (str (:approved-by d ""))
     :pending-question (or (:pending-question d) "")
     :last-heartbeat   (when (:last-heartbeat d) (double (:last-heartbeat d)))
     :depends-on       (vec (or (:depends-on d) []))}))

(defn- card->yaml-map
  "Convert a card map to snake_case keys for YAML serialization."
  [card]
  (let [m {"id"               (:id card)
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
           "approved_by"      (:approved-by card)}]
    (cond-> m
      (:pending-question card) (assoc "pending_question" (:pending-question card))
      (:last-heartbeat card)   (assoc "last_heartbeat"   (:last-heartbeat card))
      (seq (:depends-on card)) (assoc "depends_on"        (vec (:depends-on card))))))

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

;; ── Notification hooks ──────────────────────────────────────────

(defn notification-hooks
  "Return the list of notification hook maps from board config.
   Each hook has :event and :command keys."
  [board]
  (get (:config board) "notifications" {}))

(defn- template-vars
  "Build a map of template variables for hook command substitution.
   Uses {var} syntax, e.g. {card_id}, {card_title}."
  [event-type card & {:keys [reason gate question answer agent]}]
  (let [m {"{card_id}"     (or (:id card) "")
           "{card_title}"  (or (:title card) "")
           "{lane}"        (or (:lane card) "")
           "{reason}"      (or reason "")
           "{gate}"        (or gate "")
           "{question}"    (or question "")
           "{answer}"      (or answer "")
           "{agent}"       (or agent "")}]
    m))

(defn- run-hook-command
  "Run a single notification hook command. Substitutes template variables.
   Returns nil on success, logs warning on failure but never throws."
  [hook-command template-map]
  (let [cmd (reduce (fn [c [var val]]
                      (str/replace c (str var) (str val)))
                    hook-command
                    template-map)]
    (try
      (proc/shell {:out :string :err :string :continue true} "sh" "-c" cmd)
      nil
      (catch Exception e
        (binding [*out* *err*]
          (println (str "Warning: notification hook failed: " (.getMessage e))))))))

(defn run-hooks!
  "Run all notification hooks matching event-type for the board.
   Template vars are substituted from the card and optional extra data.
   Hooks run on best-effort basis — failures are logged but don't block operations."
  [board event-type card & {:keys [reason gate question answer agent]}]
  (let [hooks-config (notification-hooks board)
        hooks (get hooks-config "hooks" [])]
    (doseq [hook hooks]
      (when (= (get hook "event") event-type)
        (let [cmd    (get hook "command")
              tvars  (template-vars event-type card
                                    :reason reason :gate gate
                                    :question question :answer answer
                                    :agent agent)]
          (run-hook-command cmd tvars))))))

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

;; ── Lane instructions ─────────────────────────────────────────

(def default-lane-instructions
  "Fallback instructions for lanes without a markdown file or config override."
  {"backlog"     "This card is waiting to be picked up. Do not start work yet — use `kb advance` when ready."
   "discovery"   "Research and understand the problem. Read the codebase, identify affected files, understand constraints. Do NOT implement yet — produce findings only. Log your discoveries with `kb note`. When you understand the problem fully, `kb advance` to plan."
   "plan"         "Design your approach. Break the task into concrete steps, identify files to create or modify, consider edge cases. Write your plan as a `kb note`. Do NOT implement yet. When the plan is clear, `kb advance` to in-progress."
   "in-progress"  "Implement the solution. Write code, make changes, iterate. Log progress with `kb note`. Run relevant tests. When implementation is complete and tests pass, `kb advance` to unit-tests."
   "unit-tests"   "Write and run tests covering the changes. Verify edge cases, integration, and regression. If tests fail, fix issues and re-run. When all tests pass, `kb advance` to done."
   "review"       "Review the implementation for correctness, style, and completeness. Check edge cases. If issues found, `kb note` them and fix. If clean, `kb advance`."
   "testing"     "Write and run tests covering the changes. Verify edge cases, integration, and regression. If tests fail, fix issues and re-run. When all tests pass, `kb advance`."
   "done"         "This card is complete. No further action needed."})

(defn- load-lane-md
  "Load lane instructions from a markdown file at <kanban-root>/lanes/<lane-name>.md.
   Returns the file content as a string, or nil if the file doesn't exist."
  [board lane-name]
  (let [md-path (u/path-resolve (:root board) "lanes" (str lane-name ".md"))]
    (when (u/path-exists? md-path)
      (str/trim (slurp (str md-path))))))

(defn lane-instructions
  "Return the instructions string for a lane. Priority order:
   1. Markdown file at .kanban/lanes/<lane-name>.md
   2. `instructions` key in the lane config (board.yaml)
   3. Default-lane-instructions map
   4. Generic fallback string"
  [board lane-name]
  (let [lane-conf  (lane-by-name board lane-name)
        custom-ins (and lane-conf (get lane-conf "instructions"))
        md-ins     (load-lane-md board lane-name)]
    (or md-ins
        custom-ins
        (get default-lane-instructions lane-name)
        (str "Work on this card in lane '" lane-name "'. Use `kb advance` when ready to move forward."))))

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

(defn save-config!
  "Save the board config back to board.yaml. Returns the updated config map."
  [board]
  (let [config-path (u/path-resolve (:root board) u/board-file)]
    (u/spit-yaml config-path (:config board))
    (:config board)))

(defn add-lane!
  "Add a new lane to the board. Options: :wip, :parallelism, :on-enter, :instructions.
   Returns the updated board."
  [board lane-name & {:keys [wip parallelism on-enter instructions]}]
  (when (some #{lane-name} (lane-names board))
    (throw (ex-info (str "Lane '" lane-name "' already exists.") {:lane lane-name})))
  (let [lane-conf (cond-> {"name" lane-name}
                    wip          (assoc "max_wip" wip)
                    parallelism (assoc "max_parallelism" parallelism)
                    on-enter    (assoc "on_enter" on-enter)
                    instructions (assoc "instructions" instructions))
        new-config (update (:config board) "lanes" conj lane-conf)]
    (assoc board :config (save-config! (assoc board :config new-config)))))

(defn rename-lane!
  "Rename a lane. Updates both the config and all cards in that lane.
   Returns the updated board."
  [board old-name new-name]
  (when-not (some #{old-name} (lane-names board))
    (throw (ex-info (str "Lane '" old-name "' not found.") {:lane old-name})))
  (when (some #{new-name} (lane-names board))
    (throw (ex-info (str "Lane '" new-name "' already exists.") {:lane new-name})))
  (let [new-config (update (:config board) "lanes"
                            (fn [lanes]
                              (mapv (fn [lane]
                                      (if (= (get lane "name") old-name)
                                        (assoc lane "name" new-name)
                                        lane))
                                    lanes)))]
    ;; Update all cards in the old lane
    (doseq [card (filter #(= (:lane %) old-name) (all-cards board))]
      (let [d       (find-card-dir! board (:id card))
            updated (assoc card :lane new-name)]
        (save-card! board updated d)))
    (assoc board :config (save-config! (assoc board :config new-config)))))

(defn remove-lane!
  "Remove a lane from the board. Moves orphaned cards to the target lane.
   Returns the updated board."
  [board lane-name & {:keys [move-to]}]
  (when-not (some #{lane-name} (lane-names board))
    (throw (ex-info (str "Lane '" lane-name "' not found.") {:lane lane-name})))
  (when (= lane-name (first-lane board))
    (throw (ex-info "Cannot remove the first lane." {:lane lane-name})))
  (let [target-lane (or move-to (first-lane board))
        new-config  (update (:config board) "lanes"
                            (fn [lanes]
                              (vec (remove #(= (get % "name") lane-name) lanes))))]
    ;; Move orphaned cards to target lane
    (doseq [card (filter #(= (:lane %) lane-name) (all-cards board))]
      (let [d       (find-card-dir! board (:id card))
            updated (assoc card :lane target-lane)]
        (save-card! board updated d)
        (append-history! board (:id card)
                         {:ts (u/now-epoch)
                          :role "system"
                          :action "moved"
                          :content (str "Moved from '" lane-name "' to '" target-lane "' (lane removed)")})))
    (assoc board :config (save-config! (assoc board :config new-config)))))

(defn reorder-lanes!
  "Reorder lanes to match the given ordered sequence of lane names.
   Returns the updated board."
  [board ordered-names]
  (let [current-names (set (lane-names board))]
    (doseq [n ordered-names]
      (when-not (contains? current-names n)
        (throw (ex-info (str "Lane '" n "' not found.") {:lane n}))))
    (when-not (= (count ordered-names) (count current-names))
      (throw (ex-info "Must provide all lane names in reorder." {:given (count ordered-names)
                                                                    :expected (count current-names)})))
    (let [name->conf  (into {} (map #(vector (get % "name") %) (lanes board)))
          new-lanes   (mapv #(get name->conf %) ordered-names)
          new-config  (assoc (:config board) "lanes" new-lanes)]
      (assoc board :config (save-config! (assoc board :config new-config))))))

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
        (let [commit-r (git-safe :args ["commit" "-m" (str "kb: " (:title card) " (#" (:id card) ")")] :cwd proj)]
          ;; If commit fails, it may mean there's nothing to commit (branch has no changes vs base)
          ;; Check if there are staged changes — if not, the merge is a no-op, which is fine
          (when (not= 0 (:exit commit-r))
            (let [diff-r (git-safe :args ["diff" "--cached" "--quiet"] :cwd proj)]
              (when (not= 0 (:exit diff-r))
                (throw (ex-info (str "Failed to commit merge: " (:err commit-r)) {})))))))

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
      (run-hooks! board "created" card)
      card)))

(defn- find-available-card
  "Find the first unblocked, unassigned card across all lanes,
   preferring lanes earlier in the workflow (lower index).
   Cards in the final lane (done) are excluded.
   Cards with unsatisfied dependencies are excluded."
  [board]
  (let [final-lane (last (lane-names board))
        all        (all-cards board)]
    (first (sort-by (juxt #(.indexOf (lane-names board) (:lane %))
                          :priority :created-at)
                    (filter #(and (not= (:lane %) final-lane)
                                  (not (:blocked %))
                                  (str/blank? (:assigned-agent %))
                                  (deps-satisfied? board %))
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
        (run-hooks! board "pulled" updated :agent agent-id)
        updated))))

;; ── Card dependencies ────────────────────────────────────────────

(defn link!
  "Add a dependency: card-id depends on dep-id (card-id will not be available
   until dep-id is in the final lane). Returns the updated card."
  [board card-id dep-id]
  (let [card (load-card board card-id)
        _ (when (= card-id dep-id)
            (throw (ex-info "A card cannot depend on itself." {})))
        existing (:depends-on card)]
    (when (some #{dep-id} existing)
      (throw (ex-info (str "Card " card-id " already depends on " dep-id) {})))
    (let [updated (assoc card :depends-on (conj existing dep-id))]
      (save-card! board updated)
      (append-history! board card-id
                       (make-history-entry "system" "linked"
                                           :content (str "Added dependency on card " dep-id)))
      updated)))

(defn unlink!
  "Remove a dependency: card-id no longer depends on dep-id.
   Returns the updated card."
  [board card-id dep-id]
  (let [card (load-card board card-id)
        existing (:depends-on card)]
    (when-not (some #{dep-id} existing)
      (throw (ex-info (str "Card " card-id " does not depend on " dep-id) {})))
    (let [updated (assoc card :depends-on (vec (remove #{dep-id} existing)))]
      (save-card! board updated)
      (append-history! board card-id
                       (make-history-entry "system" "unlinked"
                                           :content (str "Removed dependency on card " dep-id)))
      updated)))

(defn card-blocks
  "Return list of card IDs that depend on the given card (i.e. cards this one blocks)."
  [board card-id]
  (let [all (all-cards board)]
    (filterv (fn [c] (some #{card-id} (:depends-on c))) all)))

(defn deps-satisfied?
  "Check if all dependencies for a card are satisfied (each dep is in the final lane)."
  [board card]
  (let [final-lane (last (lane-names board))
        deps      (:depends-on card)]
    (if (empty? deps)
      true
      (let [dep-cards (mapv #(load-card board %) deps)]
        (every? #(= final-lane (:lane %)) dep-cards)))))

(defn unsatisfied-deps
  "Return list of dependency card IDs that are not yet in the final lane."
  [board card]
  (let [final-lane (last (lane-names board))
        deps      (:depends-on card)]
    (if (empty? deps)
      []
      (let [dep-cards (mapv #(load-card board %) deps)]
        (mapv :id (filterv #(not= final-lane (:lane %)) dep-cards))))))

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
   Returns [success? message gate-results].
   Opts: :agent, :confidence (0-100)"
  [board card-id target-lane & {:keys [agent confidence] :or {agent "" confidence nil}}]
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
              min-conf      (get target-config "min_confidence")
              ;; Check confidence threshold
              low-confidence? (and min-conf confidence (< confidence min-conf))]
          (if low-confidence?
            ;; Auto-block the card instead of moving
            (do
              (let [reason (str "Low confidence (" confidence "% < " min-conf "% min for '" target-lane "')")
                    card2  (assoc card :blocked true :blocked-reason reason)]
                (save-card! board card2))
              (append-history! board card-id
                               (make-history-entry "system" "blocked"
                                                   :content (str "Auto-blocked: confidence " confidence "% below minimum " min-conf "%")
                                                   :agent-id agent))
              (run-hooks! board "blocked" (load-card board card-id)
                          :reason (str "confidence " confidence "% below min " min-conf "%")
                          :agent agent)
              [false (str "Blocked: confidence " confidence "% is below the minimum " min-conf "% for lane '" target-lane "'.") []])
          (let [max-wip       (get target-config "max_wip")
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
                                  (run-hooks! board "gate_fail" card :gate gate-cmd :agent agent)
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
                    (doseq [gr gate-results]
                      (run-hooks! board "gate_pass" card :gate (:gate gr) :agent agent))
                    (append-history! board card-id
                                     (make-history-entry "system" "approval_required"
                                                         :content (str "Awaiting approval to complete move to '" target-lane "'")
                                                         :agent-id agent))
                    (run-hooks! board "approval_required" (load-card board card-id) :agent agent)
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
                        (doseq [gr gate-results]
                          (run-hooks! board "gate_pass" card :gate (:gate gr) :agent agent))
                        (run-hooks! board "completed" card2 :agent agent)
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
                      (doseq [gr gate-results]
                        (run-hooks! board "gate_pass" card :gate (:gate gr) :agent agent))
                      (let [final-lane (last (lane-names board))]
                        (when (= target-lane final-lane)
                          (run-hooks! board "completed" (load-card board card-id) :agent agent)))
                      [true (str "Moved to '" target-lane "'.") gate-results]))))))))))))))

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
    (run-hooks! board "rejected" updated :reason reason :agent agent)
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
      (run-hooks! board "approved" updated :agent approver)
      updated)))

(defn reject-approval!
  "Reject a card that is pending approval. Moves it back to the previous lane
   and clears the pending flag. Optionally records an approval timeout action."
  [board card-id & {:keys [reason agent timeout-action] :or {reason "" agent "" timeout-action nil}}]
  (let [card  (load-card board card-id)]
    (when-not (:pending-approval card)
      (throw (ex-info (str "Card " card-id " is not pending approval.") {})))
    (let [names     (lane-names board)
          idx       (.indexOf names (:lane card))
          prev-lane (nth names (max 0 (dec idx)))
          reject-reason (if (str/blank? reason)
                          (if timeout-action
                            (str "Approval timed out (" timeout-action ")")
                            "Approval rejected")
                          reason)
          updated (assoc card :pending-approval false :approved-by ""
                              :lane prev-lane :assigned-agent ""
                              :blocked false :blocked-reason "")]
      (save-card! board updated)
      (append-history! board card-id
                       (make-history-entry "system" "approval_rejected"
                                           :content (str "Approval rejected: " reject-reason)
                                           :agent-id agent))
      (run-hooks! board "approval_rejected" updated :reason reject-reason :agent agent)
      updated)))

(defn block!
  "Block a card with a reason."
  [board card-id reason]
  (let [card    (load-card board card-id)
        updated (assoc card :blocked true :blocked-reason reason)]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "system" "blocked" :content reason))
    (run-hooks! board "blocked" updated :reason reason)
    updated))

(defn unblock!
  "Unblock a card."
  [board card-id]
  (let [card    (load-card board card-id)
        updated (assoc card :blocked false :blocked-reason "")]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "system" "unblocked" :content ""))
    (run-hooks! board "unblocked" updated)
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

;; ── Gate introspection ─────────────────────────────────────────

(defn gates-for-card
  "Return the list of gate commands the card would need to pass to move forward.
   Returns a vector of {:gate cmd :description nil} maps."
  [board card-id]
  (let [card       (load-card board card-id)
        names      (lane-names board)
        idx        (.indexOf names (:lane card))
        next-idx   (when (and (>= idx 0) (< (inc idx) (count names)))
                     (inc idx))]
    (if-not next-idx
      []
      (let [next-lane   (nth names next-idx)
            next-config (lane-by-name board next-lane)
            gate-key    (str "gate_from_" (:lane card))
            gates       (get next-config gate-key [])]
        (mapv (fn [cmd] {:gate cmd
                         :target-lane next-lane
                         :description (get next-config "gate_description" nil)})
              gates)))))

(defn last-gate-failure
  "Return the most recent gate_fail history entry for a card, or nil."
  [board card-id]
  (let [history (load-history board card-id)]
    (->> history
         (filter #(= "gate_fail" (:action %)))
         last)))

(defn recent-human-notes
  "Return the last N human notes from card history."
  [board card-id & {:keys [n] :or {n 3}}]
  (let [history (load-history board card-id)]
    (->> history
         (filter #(and (= "human" (:role %))
                       (contains? #{"note" "answer"} (:action %))))
         (take-last n))))

;; ── Card editing ───────────────────────────────────────────────

(defn edit-card!
  "Edit card fields. Returns updated card."
  [board card-id & {:keys [title priority description tags]}]
  (let [card (load-card board card-id)
        card' (cond-> card
                title       (assoc :title title)
                priority    (assoc :priority priority)
                tags        (assoc :tags tags))]
    (save-card! board card')
    (when description
      (save-description! board card-id description))
    (append-history! board card-id
                     (make-history-entry "system" "edited"
                                         :content (str "Card edited"
                                                       (when title (str " title='" title "'"))
                                                       (when priority (str " priority=" priority)))))
    card'))

;; ── Ask/Answer ─────────────────────────────────────────────────

(defn ask!
  "Mark a card as having a pending question. Blocks the card with the question.
   Returns updated card."
  [board card-id question & {:keys [agent] :or {agent ""}}]
  (let [card    (load-card board card-id)
        updated (assoc card :pending-question question :blocked true
                            :blocked-reason (str "Question: " question))]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry (if (str/blank? agent) "agent" "agent")
                                         "ask"
                                         :content question
                                         :agent-id (if (str/blank? agent) "" agent)))
    (run-hooks! board "ask" updated :question question :agent agent)
    updated))

(defn answer!
  "Answer a pending question on a card. Unblocks it and records the answer.
   Returns updated card."
  [board card-id answer & {:keys [agent] :or {agent "human"}}]
  (let [card (load-card board card-id)]
    (when-not (:pending-question card)
      (throw (ex-info (str "Card " card-id " has no pending question.") {})))
    (let [updated (assoc card :pending-question nil
                            :blocked false
                            :blocked-reason "")]
      (save-card! board updated)
      (append-history! board card-id
                       (make-history-entry "human" "answer"
                                           :content answer
                                           :agent-id agent))
      (run-hooks! board "answer" updated :answer answer :agent agent)
      updated)))

;; ── Heartbeat ──────────────────────────────────────────────────

(defn heartbeat!
  "Record an agent heartbeat for a card."
  [board card-id & {:keys [agent] :or {agent ""}}]
  (let [card (load-card board card-id)
        now  (u/now-epoch)
        updated (assoc card :last-heartbeat now)]
    (save-card! board updated)
    (append-history! board card-id
                     (make-history-entry "agent" "heartbeat"
                                         :content "Agent heartbeat"
                                         :agent-id agent))
    updated))

;; ── Watcher checks ─────────────────────────────────────────────

(defn- parse-duration
  "Parse a duration string like '30s', '30m', '4h', '1d' to seconds.
   Returns nil if unparseable."
  [s]
  (when (string? s)
    (let [match (re-matches #"(\d+)([smhd])" s)]
      (when match
        (let [n (Integer/parseInt (match 1))
              unit (match 2)]
          (cond
            (= unit "s") n
            (= unit "m") (* n 60)
            (= unit "h") (* n 3600)
            (= unit "d") (* n 86400)
            :else nil))))))

(defn check-approval-timeouts!
  "Check all pending-approval cards for expired approval timeouts.
   For each expired card, applies the configured timeout_action.
   Returns list of cards that were acted on."
  [board]
  (let [now   (u/now-epoch)
        all   (all-cards board)]
    (->> all
         (filter :pending-approval)
         (keep (fn [card]
                 (let [lane-conf  (lane-by-name board (:lane card))
                       timeout    (get lane-conf "approval_timeout")
                       action     (get lane-conf "approval_timeout_action" "reject")
                       timeout-s  (parse-duration timeout)]
                   (when (and timeout-s
                              ;; Card must have been in pending-approval for at least timeout-s
                              ;; We check against updated-at as a proxy for when it entered this state
                              (> (- now (:updated-at card)) timeout-s))
                     (try
                       (reject-approval! board (:id card)
                                         :reason (str "Approval timeout (" timeout ")")
                                         :timeout-action action)
                       (run-hooks! board "approval_timeout" card :reason (str "Approval timeout (" timeout ")"))
                       card
                       (catch Exception _ nil)))))))))

(defn check-stale-heartbeats!
  "Check all cards in lanes with heartbeat_timeout for stale heartbeats.
   Cards with no heartbeat for longer than the timeout are auto-blocked.
   Returns list of cards that were blocked."
  [board]
  (let [now   (u/now-epoch)
        all   (all-cards board)]
    (->> all
         (filter (fn [card]
                   (let [lane-conf  (lane-by-name board (:lane card))
                         timeout    (get lane-conf "heartbeat_timeout")
                         timeout-s  (parse-duration timeout)]
                     (and timeout-s
                          (not (:blocked card))
                          (:last-heartbeat card)
                          (> (- now (:last-heartbeat card)) timeout-s)))))
         (keep (fn [card]
                 (let [lane-conf  (lane-by-name board (:lane card))
                       timeout    (get lane-conf "heartbeat_timeout")
                       reason (str "Agent heartbeat timeout (last heartbeat: "
                                   (u/fmt-ts (:last-heartbeat card))
                                   ", timeout: " timeout ")")]
                   (try
                     (let [updated (block! board (:id card) reason)]
                       (run-hooks! board "heartbeat_missed" updated :reason reason)
                       updated)
                     (catch Exception _ nil))))))))

;; ── Advance / Done shortcuts ───────────────────────────────────

(defn advance!
  "Move card to the next lane in the workflow. Returns [success? message gate-results]."
  [board card-id & {:keys [agent confidence] :or {agent "" confidence nil}}]
  (let [card   (load-card board card-id)
        names  (lane-names board)
        idx    (.indexOf names (:lane card))
        next   (when (and (>= idx 0) (< (inc idx) (count names)))
                 (nth names (inc idx)))]
    (if next
      (move! board card-id next :agent agent :confidence confidence)
      [false (str "Card is already in the last lane ('" (:lane card) "').") []])))

(defn done!
  "Move card directly to the last lane (runs all intermediate gates). Returns [success? message gate-results]."
  [board card-id & {:keys [agent] :or {agent ""}}]
  (let [names     (lane-names board)
        last-lane (last names)]
    (move! board card-id last-lane :agent agent)))

;; ── Context generation ─────────────────────────────────────────

(defn- history-summary
  "Summarize a sequence of history entries into a compact string."
  [entries]
  (let [actions (frequencies (map :action entries))
        parts  (mapv (fn [[action cnt]]
                       (str cnt " " action (when (> cnt 1) "s")))
                     actions)]
    (str/join ", " parts)))

(defn- trim-to-budget
  "Trim lines to fit within a character budget. Keeps the first and last lines,
   dropping middle content with a truncation notice."
  [lines budget]
  (let [full (str/join "\n" lines)]
    (if (<= (count full) budget)
      lines
      (loop [head (take 8 lines)
             tail (take-last 4 lines)
             rest-lines (drop 8 lines)]
        (let [head' (if (> (count rest-lines) 4)
                      (concat head [(str "... (" (count rest-lines) " earlier entries truncated) ...")])
                      (concat head rest-lines))
              result (vec (concat head' tail))]
          (if (<= (count (str/join "\n" result)) budget)
            result
            (if (empty? rest-lines)
              (vec (concat (take 4 lines) [(str "... (truncated to fit budget) ...")]))
              (recur head tail (drop 1 rest-lines)))))))))

(defn get-context
  "Generate the full context string for a sub-agent system prompt.
   Opts map supports:
     :strategy   - :full (default), :recent (last 10 history), :summary (condensed history)
     :budget     - max character count for output (nil = unlimited)
     :since      - only include history entries after this epoch timestamp
     :gates-only - only output gates information (for quick checks)
     :deps-only  - only output dependency information"
  ([board card-id] (get-context board card-id {}))
  ([board card-id opts]
  (let [agent-command (get (:config board) "agent_command" "")]
    (when (str/blank? agent-command)
      (throw (ex-info
              (str "No agent_command template configured in board.yaml. "
                   "Add an agent_command field to .kanban/board.yaml. Example:\n"
                   "  agent_command: 'claude --system-prompt \"$(kb context {card_id})\" --cwd {worktree}'")
              {})))
    (let [strategy  (or (:strategy opts) (keyword (or (get (:config board) "context_strategy") "full")))
          budget    (or (:budget opts) (when-let [b (get (:config board) "context_budget")]
                                         (when (pos? b) b)))
          since-ep  (:since opts)
          card      (load-card board card-id)
          desc      (load-description board card-id)
          history   (if since-ep
                      (load-history board card-id since-ep)
                      (load-history board card-id))
          diff-stat (get-diff-stat board card-id)
          base      (base-branch board)
          names     (lane-names board)
          idx       (.indexOf names (:lane card))

          next-gates (when (and (>= idx 0) (< (inc idx) (count names)))
                       (let [next-lane   (nth names (inc idx))
                             next-config (lane-by-name board next-lane)
                             gate-key    (str "gate_from_" (:lane card))]
                         (get next-config gate-key [])))

          all-cards-raw (all-cards board)
          lane-cards   (filterv #(and (= (:lane %) (:lane card))
                                       (not= (:id %) card-id))
                                all-cards-raw)
          blocked-cards (filterv :blocked all-cards-raw)
          recent-hnotes (->> history
                             (filter #(and (= "human" (:role %))
                                           (contains? #{"note" "answer"} (:action %))))
                             (take-last 3))
          last-gfail   (->> history
                            (filter #(= "gate_fail" (:action %)))
                            last)
          board-summary (->> (lane-names board)
                             (mapv (fn [ln]
                                     (let [lc (count (filter #(= (:lane %) ln) all-cards-raw))]
                                       (str ln ": " lc " card(s)"))))
                             (str/join ", "))

          ;; Gates-only mode: just output gates info
          gates-output
          (when (seq next-gates)
            (into [(str "## Gates to pass (moving to '" (nth names (inc idx)) "')") ""]
                  (mapv #(str "- `" % "`") next-gates)))

          ;; Deps-only mode: just output dependency info
          deps-output
          (let [dep-lines (when (seq (:depends-on card))
                            (let [usdeps (unsatisfied-deps board card)]
                              (into [(str "## Dependencies (" (count (:depends-on card)) " total, "
                                          (count usdeps) " unsatisfied)") ""]
                                    (mapv (fn [dep-id]
                                            (let [dep-card (load-card board dep-id)]
                                              (str "- [" dep-id "] " (:title dep-card)
                                                   " (" (:lane dep-card)
                                                   (when (some #{dep-id} usdeps) " — UNSATISFIED") ")")))
                                          (:depends-on card)))))
                blocking (card-blocks board card-id)
                block-lines (when (seq blocking)
                              (into ["## This card blocks" ""]
                                    (mapv #(str "- [" (:id %) "] " (:title %)) blocking)))]
            (vec (concat dep-lines block-lines)))

          ;; History based on strategy
          history-lines
          (condp = strategy
            :summary
            (when (seq history)
              (let [recent   (take-last 5 history)
                    older    (drop-last 5 history)]
                (into ["## History (summary mode)" ""]
                      (concat
                        (when (seq older)
                          [(str "Earlier activity: " (history-summary older) "")])
                        (mapv (fn [entry]
                                (let [ts       (u/fmt-time (:ts entry))
                                      role     (:role entry "?")
                                      action   (:action entry "?")
                                      content  (:content entry "")
                                      agent-id (:agent-id entry "")
                                      parts    (cond-> [(str "[" ts "]") (str role "/" action)]
                                                 (not (str/blank? agent-id)) (conj (str "(agent: " agent-id ")")))]
                                  (str (str/join " " parts) ": " content)))
                              recent)))))
            :recent
            (when (seq history)
              (let [recent (take-last 10 history)]
                (into ["## History (last 10 entries)" ""]
                      (mapv (fn [entry]
                              (let [ts       (u/fmt-time (:ts entry))
                                    role     (:role entry "?")
                                    action   (:action entry "?")
                                    content  (:content entry "")
                                    agent-id (:agent-id entry "")
                                    parts    (cond-> [(str "[" ts "]") (str role "/" action)]
                                               (not (str/blank? agent-id)) (conj (str "(agent: " agent-id ")")))]
                                (str (str/join " " parts) ": " content)))
                            recent))))
            ;; :full (default)
            (when (seq history)
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
                          (take-last 30 history)))))

          ;; Handle focused modes
          lines (cond
                  (:gates-only opts)
                  (vec (concat
                         [(str "# Task: " (:title card))
                          (str "Card ID: " (:id card))
                          (str "Lane: " (:lane card))
                          ""]
                         gates-output
                         (when last-gfail
                           ["## Last gate failure" ""
                            (str "Gate: `" (:gate last-gfail "") "`")
                            (str "Output: " (:content last-gfail))])))

                  (:deps-only opts)
                  (vec (concat
                         [(str "# Task: " (:title card))
                          (str "Card ID: " (:id card))
                          (str "Lane: " (:lane card))
                          ""]
                         deps-output))

                  :else
                  (let [usdeps    (unsatisfied-deps board card)
                        dep-status (if (seq usdeps)
                                     (str "BLOCKED — " (count usdeps) " unsatisfied dependenc"
                                           (if (= 1 (count usdeps)) "y" "ies"))
                                     "UNBLOCKED — all dependencies satisfied")]
                    (-> [(str "# Task: " (:title card))
                         (str "Card ID: " (:id card))
                         (str "Lane: " (:lane card))
                         (str "Status: " dep-status)
                         (str "Branch: " (:branch card))
                         (str "Worktree: " (:worktree card))
                         (str "Base branch: " base)
                         ""]
                        (into (let [md-ins (load-lane-md board (:lane card))]
                                (if md-ins
                                  ;; Full markdown file — it has its own heading
                                  ["" md-ins ""]
                                  ;; Fallback: short instruction string with a header
                                  [(str "## Lane: " (:lane card))
                                   (lane-instructions board (:lane card)) ""])))
                        (into (when (not (str/blank? desc))
                                 ["## Description" "" (str/trim desc) ""]))
                      (into gates-output)
                      (into (when last-gfail
                               ["## Last gate failure" ""
                                (str "Gate: `" (:gate last-gfail "") "`")
                                (str "Output: " (:content last-gfail))
                                "Fix the issue above and retry with `kb move`." ""]))
                      (into (when (and diff-stat (not (str/blank? diff-stat))
                                       (not (str/includes? diff-stat "(no branch)")))
                               ["## Current changes" "" "```" (str/trim diff-stat) "```" ""]))
                      (into (when (seq recent-hnotes)
                               (into ["## Recent human notes (IMPORTANT — read these)" ""]
                                     (mapv (fn [e]
                                             (str "- [" (u/fmt-time (:ts e)) "] "
                                                  (:action e) ": " (:content e)))
                                           recent-hnotes))))
                      (into (when (and (:pending-question card)
                                      (not (str/blank? (:pending-question card))))
                               ["## Your pending question" ""
                                (:pending-question card)
                                "Wait for a human to answer via `kb answer`." ""]))
                      (into (when (not-empty board-summary)
                               ["## Board summary" "" board-summary ""]))
                      (into deps-output)
                      (into (when (seq lane-cards)
                               (into ["## Other cards in your lane" ""]
                                     (mapv #(str "- [" (:id %) "] " (:title %)
                                                 (when (:blocked %) " (BLOCKED)"))
                                           lane-cards))))
                      (into (when (seq blocked-cards)
                               (into ["## Blocked cards" ""]
                                     (mapv #(str "- [" (:id %) "] " (:title %)
                                                 " — " (:blocked-reason %))
                                           blocked-cards))))
                      (into history-lines))))]

      ;; Apply budget trimming if configured
      (let [final-lines (if budget
                          (trim-to-budget lines budget)
                          lines)]
        (str/join "\n"
                  (into final-lines
                         ["## Instructions"
                          ""
                          "You are working on this card. Your working directory is the git worktree for this card."
                          "Use these commands to interact with the board:"
                          ""
                          (str "- `kb note " card-id " \"<message>\"` -- log your thinking or progress")
                          (str "- `kb ask " card-id " \"<question>\"` -- ask the human a question (card will be blocked until answered)")
                          (str "- `kb advance " card-id "` -- move card to the next lane (runs gates)")
                          (str "- `kb move " card-id " <lane>` -- move card to a specific lane (runs gates)")
                          (str "- `kb log " card-id "` -- check for new human notes or answers")
                          (str "- `kb diff " card-id "` -- see your changes vs the base branch")
                          (str "- `kb heartbeat " card-id "` -- signal you are still working (call every 2 minutes)")
                          ""
                          "Before each major step, check `kb log` for new human instructions."
                          "If you are unsure about something, use `kb ask` to ask the human."
                          "When you believe the task is complete and tests pass, use `kb advance` to move forward."])))))))

(defn compact-history!
  "Permanently compress older history entries into a single summary entry.
   Keeps the last N entries intact, replaces everything before with one summary line."
  [board card-id & {:keys [keep] :or {keep 10}}]
  (let [history  (load-history board card-id)
        total    (count history)]
    (if (<= total keep)
      {:compacted false :message "History is already compact enough."}
      (let [older     (drop-last keep history)
            newer     (take-last keep history)
            summary   (str "Compacted " (count older) " earlier entries: " (history-summary older))
            compact-entry (make-history-entry "system" "compacted" :content summary)]
        ;; Rewrite history: compact-entry + newer entries
        (let [d         (find-card-dir board card-id)
              hist-path (u/path-resolve d "history.jsonl")
              all-entries (conj (into [compact-entry] newer))]
          ;; Write all entries back
          (u/atomic-write! hist-path
                          (str/join "\n" (mapv (fn [e]
                                                 (json/generate-string (history-entry->json-map e)))
                                               all-entries))))
        {:compacted true
         :removed (count older)
         :kept keep
         :message (str "Compacted " (count older) " entries into 1 summary.")}))))

;; ── Agent instructions ────────────────────────────────────────

(defn agent-instructions
  "Return a markdown string with kb workflow instructions for coding agents.
   Used by `kb init` (appended to CLAUDE.md) and `kb help --agent`."
  []
  (str/join "\n"
    ["# kb — Kanban Board Workflow"
     ""
     "This project uses `kb` for task management."
     ""
     "## Working with the Board"
     ""
     "1. `kb status` — see all lanes and cards"
     "2. `kb pull --lane in-progress --agent claude` — claim the next card (auto-moves to that lane, creates worktree + branch)"
     "3. Work in the card's worktree (shown in pull output)"
     "4. `kb note <id> \"what you did\"` — log progress"
     "5. `kb ask <id> \"question\"` — ask the human (blocks the card until answered)"
     "6. `kb log <id>` — check for human notes or answers before each major step"
     "7. `kb heartbeat <id>` — signal you are still working (call every 2 minutes)"
     "8. `kb advance <id>` — move card to next lane (runs quality gates)"
     "9. `kb done <id>` — move card to final lane (runs all gates)"
     "10. If gates fail, read the output, fix the issue, and retry"
     ""
     "## Rules"
     ""
     "- Always work inside the card's worktree, not the main tree"
     "- Use `kb context <id>` to get full card context including lane instructions"
     "- Use `kb note` to record key decisions and progress"
     "- Use `kb ask` when you need clarification — the card blocks until the human answers"
     "- Do not skip quality gates — fix failures before retrying"
     "- One card at a time unless explicitly told to work on multiple"
     ""
     "## Quick Reference"
     ""
     "| Command | Description |"
     "|---------|-------------|"
     "| `kb status` | Show all lanes and cards |"
     "| `kb pull [--lane L] [--agent A]` | Claim next card |"
     "| `kb show <id>` | Card details |"
     "| `kb context <id>` | Full card context for agent prompt |"
     "| `kb note <id> \"msg\"` | Log progress |"
     "| `kb ask <id> \"question\"` | Ask the human (blocks card) |"
     "| `kb advance <id>` | Move to next lane (runs gates) |"
     "| `kb done <id>` | Move to final lane (runs all gates) |"
     "| `kb block <id> --reason R` | Mark card blocked |"
     "| `kb heartbeat <id>` | Record agent heartbeat |"
     "| `kb diff <id>` | Show diff vs base branch |"
     "| `kb link <id> <dep-id>` | Add dependency |"
     "| `kb deps <id>` | Show dependencies |"
     ""]))

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

         ;; Add .kanban/ to .gitignore if not already present
         (let [gitignore-path (u/path-resolve project-root ".gitignore")
               existing-lines (when (u/path-exists? gitignore-path)
                                (str/split-lines (slurp (str gitignore-path))))
               kanban-entries (filter #(let [l (str/trim %)]
                                          (or (= l (str u/kanban-dir "/"))
                                              (= l u/kanban-dir)))
                                      existing-lines)]
           (when (empty? kanban-entries)
             (spit (str gitignore-path) (str u/kanban-dir "/\n") :append true)))

         ;; Append kb instructions to CLAUDE.md
         (let [claude-md (str (u/path-resolve project-root "CLAUDE.md"))]
           (spit claude-md (str "\n" (agent-instructions)) :append true))

         (make-board root))))))
