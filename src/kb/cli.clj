(ns kb.cli
  "CLI entry point for kb kanban board.
   Invoked via: bb -m kb.cli  or  bb run serve"
  (:require [babashka.cli :as cli]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kb.board :as b]
            [kb.util :as u]))

;; ── Output helpers ────────────────────────────────────────────

(defn- fail!
  "Print message to stderr and exit 1."
  [msg]
  (binding [*out* *err*]
    (println (str "Error: " msg)))
  (System/exit 1))

(defn- out-json
  "Pretty-print data as JSON to stdout."
  [data]
  (println (json/generate-string data {:pretty true})))

(defn- ->card-id
  "Coerce card-id opt to string (babashka.cli may parse '001' as Long)."
  [opts]
  (let [v (:card-id opts)]
    (when v (if (integer? v) (format "%03d" v) (str v)))))

;; ── Spawn helper ──────────────────────────────────────────────

(defn- spawn-agent
  "Spawn a sub-agent for a card using the board's agent_command config."
  [board card]
  (let [cmd-template (get (:config board) "agent_command" "")]
    (when (str/blank? cmd-template)
      (fail! (str "no agent_command template configured in board.yaml. "
                  "Add an agent_command field to .kanban/board.yaml. Example:\n"
                  "  agent_command: 'claude --system-prompt \"$(kb context {card_id})\" "
                  "--cwd {worktree}'")))
    (let [cmd (-> cmd-template
                  (str/replace "{card_id}" (or (:id card) ""))
                  (str/replace "{worktree}" (or (:worktree card) "."))
                  (str/replace "{branch}" (or (:branch card) "")))]
      ;; Record spawn event in history
      (b/append-history! board (:id card)
                         {:ts (u/now-epoch)
                          :role "system"
                          :action "spawned"
                          :content (str "Sub-agent spawned: " cmd)})
      (println (str "Spawning agent for card " (:id card) "..."))
      (println (str "  Command: " cmd))
      (println (str "  Worktree: " (:worktree card)))
      (try
        (proc/shell {:dir (or (:worktree card) ".")} cmd)
        (catch Exception _
          (println "\nAgent interrupted."))))))

;; ── Command handlers ──────────────────────────────────────────

(defn cmd-init
  [{:keys [opts]}]
  (let [path (or (:path opts) ".")]
    (try
      (let [board (b/init-board! path)]
        (println (str "Initialized kanban board at " (:root board)))
        (println (str "Base branch: " (b/base-branch board)))
        (println "Edit .kanban/board.yaml to configure lanes and gates."))
      (catch Exception e
        (fail! (.getMessage e))))))

(defn cmd-add
  [{:keys [opts]}]
  (let [title (:title opts)
        _ (when (str/blank? title) (fail! "title is required"))
        board (b/make-board)
        tags (if (str/blank? (:tags opts ""))
               []
               (mapv str/trim (str/split (:tags opts) #",")))
        ;; Description: try as file path first, fall back to inline text
        desc (let [d (:desc opts "")]
               (if (str/blank? d)
                 ""
                 (let [f (io/file d)]
                   (if (.exists f) (slurp f) d))))
        card (b/create-card! board title
                             :lane (:lane opts)
                             :tags tags
                             :priority (or (:priority opts) 0)
                             :description desc)]
    (if (:json opts)
      (out-json card)
      (println (str "Created card " (:id card) ": " (:title card) " [" (:lane card) "]")))))

(defn cmd-pull
  [{:keys [opts]}]
  (let [board (b/make-board)
        card (b/pull! board :agent (:agent opts "") :lane (:lane opts))]
    (if (nil? card)
      (do
        (when (:json opts) (out-json nil))
        (when-not (:json opts) (println "No cards available to pull."))
        (System/exit 1))
      (do
        (if (:json opts)
          (out-json card)
          (do
            (println (str "Pulled card " (:id card) ": " (:title card) " [" (:lane card) "]"))
            (println (str "  Agent:    " (:assigned-agent card)))
            (println (str "  Branch:   " (:branch card)))
            (println (str "  Worktree: " (:worktree card)))))
        (when (:spawn opts)
          (spawn-agent board card))))))

(defn cmd-move
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        lane (:lane opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        _ (when (str/blank? lane) (fail! "lane is required"))
        board (b/make-board)
        [success? message gate-results] (b/move! board card-id lane :agent (:agent opts ""))]
    (if (:json opts)
      (out-json {:success success?
                 :message message
                 :gate_results gate-results})
      (if success?
        (println (str "\u2713 " message))
        (do
          (binding [*out* *err*]
            (println (str "\u2717 " message)))
          (System/exit 1))))))

(defn cmd-reject
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        card (b/reject! board card-id
                        :reason (:reason opts "")
                        :agent (:agent opts ""))]
    (if (:json opts)
      (out-json card)
      (println (str "Rejected card " (:id card) " \u2192 " (:lane card))))))

(defn cmd-block
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        card (b/block! board card-id (or (:reason opts) ""))]
    (if (:json opts)
      (out-json card)
      (println (str "Blocked card " (:id card) ": " (:reason opts ""))))))

(defn cmd-unblock
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        card (b/unblock! board card-id)]
    (if (:json opts)
      (out-json card)
      (println (str "Unblocked card " (:id card))))))

(defn cmd-approve
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)]
    (try
      (let [card (b/approve! board card-id :agent (or (:agent opts) "human"))]
        (if (:json opts)
          (out-json card)
          (println (str "Approved card " (:id card)))))
      (catch Exception e
        (fail! (.getMessage e))))))

(defn cmd-note
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        message (:message opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        _ (when (str/blank? message) (fail! "message is required"))
        board (b/make-board)]
    (b/add-note! board card-id message :agent (or (:agent opts) "human"))
    (when (and (:worktree (b/load-card board card-id))
               (not (str/blank? (:worktree (b/load-card board card-id)))))
      (let [wt (:worktree (b/load-card board card-id))
            inbox-path (u/path-resolve wt ".kb-inbox.jsonl")
            line (json/generate-string {"ts" (u/now-epoch)
                                        "role" "human"
                                        "action" "note"
                                        "content" message})]
        (u/flock-append! inbox-path line)))
    (if (:json opts)
      (out-json {:status "ok"})
      (println (str "Note added to card " card-id)))))

(defn cmd-log
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        since (when-let [s (:since opts)] (u/parse-since s))
        history (b/load-history board card-id since)]
    (if (:json opts)
      (out-json history)
      (if (empty? history)
        (println "No history.")
        (doseq [entry history]
          (let [ts (u/fmt-ts (get entry :ts 0))
                role (get entry :role "?")
                action (get entry :action "?")
                content (get entry :content "")
                agent-id (get entry :agent_id "")
                agent-str (if (str/blank? agent-id) "" (str " [" agent-id "]"))
                gate (get entry :gate "")
                gate-str (if (str/blank? gate) "" (str " (gate: " gate ")"))]
            (println (str "  " ts "  " role "/" action agent-str gate-str ": " content))))))))

(defn cmd-diff
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        diff (if (:stat opts)
               (b/get-diff-stat board card-id)
               (b/get-diff board card-id))]
    (if (:json opts)
      (out-json {:diff diff})
      (println diff))))

(defn cmd-show
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        card (b/load-card board card-id)
        desc (b/load-description board card-id)
        history (b/load-history board card-id)
        diff-stat (b/get-diff-stat board card-id)]
    (if (:json opts)
      (out-json {:card card
                 :description desc
                 :history history
                 :diff_stat diff-stat})
      (do
        (println)
        (println (str (apply str (repeat 60 "="))))
        (println (str "  Card " (:id card) ": " (:title card)))
        (println (str (apply str (repeat 60 "="))))
        (println (str "  Lane:     " (:lane card)))
        (println (str "  Priority: " (:priority card)))
        (println (str "  Blocked:  " (:blocked card)
                      (if (str/blank? (:blocked-reason card ""))
                        ""
                        (str " (" (:blocked-reason card) ")"))))
        (println (str "  Agent:    " (or (not-empty (:assigned-agent card)) "(none)")))
        (println (str "  Branch:   " (or (not-empty (:branch card)) "(none)")))
        (println (str "  Worktree: " (or (not-empty (:worktree card)) "(none)")))
        (println (str "  Tags:     " (if (seq (:tags card))
                                       (str/join ", " (:tags card))
                                       "(none)")))
        (println (str "  Created:  " (u/fmt-ts (:created-at card))))
        (println (str "  Updated:  " (u/fmt-ts (:updated-at card))))
        (when (and (:pending-question card) (not (str/blank? (:pending-question card))))
          (println (str "  Question: " (:pending-question card))))
        (when (:last-heartbeat card)
          (println (str "  Heartbeat: " (u/fmt-ts (:last-heartbeat card)))))

        (when (not (str/blank? desc))
          (println)
          (println "  Description:")
          (doseq [line (str/split-lines (str/trim desc))]
            (println (str "    " line))))

        (when (and (not (str/blank? diff-stat))
                   (not (str/blank? (str/trim diff-stat)))
                   (not (str/includes? diff-stat "(no branch)")))
          (println)
          (println "  Changes:")
          (doseq [line (str/split-lines (str/trim diff-stat))]
            (println (str "    " line))))

        (println)
        (println (str "  History (" (count history) " entries):"))
        (let [recent (if (> (count history) 15) (drop (- (count history) 15) history) history)]
          (doseq [entry recent]
            (let [ts (u/fmt-ts (get entry :ts 0))
                  role (get entry :role "?")
                  action (get entry :action "?")
                  content (get entry :content "")]
              (println (str "    " ts "  " role "/" action ": " content))))
          (when (> (count history) 15)
            (println (str "    ... (" (- (count history) 15) " earlier entries)"))))
        (println)))))

(defn cmd-status
  [{:keys [opts]}]
  (let [board (b/make-board)
        cards (b/all-cards board)]
    (if (:json opts)
      (let [result (reduce (fn [acc lane-name]
                             (assoc acc lane-name
                                    (filterv #(= (:lane %) lane-name) cards)))
                           {}
                           (b/lane-names board))]
        (out-json result))
      (do
        (println)
        (println (str "  " (get (:config board) "project" "kb")
                      " \u2014 " (b/base-branch board)))
        (println)
        (doseq [lane-conf (b/lanes board)]
          (let [name (get lane-conf "name")
                lane-cards (sort-by (juxt :priority :created-at)
                                    (filterv #(= (:lane %) name) cards))
                max-wip (get lane-conf "max_wip" "\u221e")
                max-par (get lane-conf "max_parallelism" "\u221e")
                header (str "\u2500\u2500 " (str/upper-case name)
                            " (" (count lane-cards) "/" max-wip ")"
                            " [par: " max-par "] ")]
            (println (str header (apply str (repeat (max 0 (- 60 (count header))) "\u2500"))))
            (if (empty? lane-cards)
              (println "  (empty)")
              (doseq [c lane-cards]
                (let [flags (cond-> []
                              (:blocked c) (conj "BLOCKED")
                              (:pending-approval c) (conj "PENDING APPROVAL")
                              (and (:pending-question c) (not (str/blank? (:pending-question c)))) (conj "QUESTION")
                              (not (str/blank? (:assigned-agent c ""))) (conj (str "\u2192 " (:assigned-agent c)))
                              (not (str/blank? (:branch c ""))) (conj (:branch c))
                              (seq (:tags c)) (conj (str/join " " (map #(str "#" %) (:tags c)))))
                      flag-str (if (seq flags)
                                 (str "  (" (str/join ", " flags) ")")
                                 "")]
                  (println (str "  [" (:id c) "] " (:title c) flag-str)))))
            (println)))))))

(defn cmd-context
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)]
    (try
      (let [context (b/get-context board card-id)]
        (if (:json opts)
          (out-json {:context context})
          (println context)))
      (catch Exception e
        (fail! (.getMessage e))))))

(defn cmd-spawn
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        card (b/load-card board card-id)]
    (when (str/blank? (:worktree card ""))
      (fail! (str "card " (:id card) " has no worktree. "
                  "Pull the card first with `kb pull` to create a worktree.")))
    (when-not (.exists (io/file (:worktree card)))
      (fail! (str "worktree path '" (:worktree card) "' does not exist. "
                  "The worktree may have been removed. Run `kb cleanup` and then `kb pull` to recreate it.")))
    (spawn-agent board card)))

(defn cmd-cleanup
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)]
    (b/cleanup! board card-id :delete-branch (boolean (:delete-branch opts)))
    (if (:json opts)
      (out-json {:status "ok"})
      (println (str "Cleaned up card " card-id)))))

(defn cmd-recover
  [{:keys [opts]}]
  (let [board (b/make-board)
        result (b/recover! board
                           :clean (boolean (:clean opts))
                           :delete-branches (boolean (:delete-branches opts)))]
    (if (:json opts)
      (out-json result)
      (let [orphaned-wts (get result :orphaned_worktrees [])
            stale-refs (get result :stale_card_refs [])
            orphaned-branches (get result :orphaned_branches [])
            cleaned (get result :cleaned [])
            total (+ (count orphaned-wts)
                     (count stale-refs)
                     (count orphaned-branches))]
        (if (and (zero? total) (empty? cleaned))
          (println "No orphaned resources found. Board is clean.")
          (do
            ;; Orphaned worktrees
            (if (seq orphaned-wts)
              (do
                (println (str "\n  "
                              (if (:clean opts) "Removed worktrees" "Orphaned worktrees")
                              " (" (count orphaned-wts) "):"))
                (doseq [wt orphaned-wts]
                  (println (str "    [" (:id wt) "] " (:path wt)))))
              (println "\n  Orphaned worktrees: none"))

            ;; Stale card references
            (if (seq stale-refs)
              (do
                (println (str "\n  "
                              (if (:clean opts) "Cleared card refs" "Stale card refs")
                              " (" (count stale-refs) "):"))
                (doseq [ref stale-refs]
                  (println (str "    [" (:id ref) "] " (:title ref) " \u2014 worktree: " (:worktree ref)))))
              (println "\n  Stale card refs: none"))

            ;; Orphaned branches
            (if (seq orphaned-branches)
              (do
                (println (str "\n  "
                              (if (:clean opts) "Deleted branches" "Orphaned branches")
                              " (" (count orphaned-branches) "):"))
                (doseq [ob orphaned-branches]
                  (println (str "    " (:branch ob) "  (card_id: " (:card_id ob) ")"))))
              (println "\n  Orphaned branches: none"))

            ;; Cleanup summary
            (cond
              (and (:clean opts) (seq cleaned))
              (do
                (println (str "\n  Cleaned (" (count cleaned) "):"))
                (doseq [entry cleaned]
                  (println (str "    " entry))))

              (and (pos? total) (not (:clean opts)))
              (do
                (println (str "\n  Found " total " orphaned resource(s)."))
                (println "  Run with --clean to remove them.")
                (when (seq orphaned-branches)
                  (println "  Run with --clean --delete-branches to also delete orphaned branches."))))))))))

(defn cmd-serve
  [{:keys [opts]}]
  (require '[kb.server :as srv])
  ((resolve 'kb.server/run-server)
   {:host (or (:host opts) "127.0.0.1")
    :port (or (:port opts) 8741)}))

(defn cmd-gates
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        gates (b/gates-for-card board card-id)]
    (if (:json opts)
      (out-json gates)
      (if (empty? gates)
        (println "No gates between current lane and the next lane.")
        (do
          (println (str "Gates for card " card-id " (moving to " (:target-lane (first gates)) "):"))
          (doseq [g gates]
            (println (str "  - `" (:gate g) "`"))))))))

(defn cmd-ask
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        question (:question opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        _ (when (str/blank? question) (fail! "question is required"))
        board (b/make-board)
        card (b/ask! board card-id question :agent (or (:agent opts) ""))]
    (if (:json opts)
      (out-json card)
      (println (str "Asked on card " (:id card) ": " question)))))

(defn cmd-answer
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        answer-text (:answer opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        _ (when (str/blank? answer-text) (fail! "answer is required"))
        board (b/make-board)]
    (try
      (let [card (b/answer! board card-id answer-text :agent (or (:agent opts) "human"))]
        (when (and (:worktree card)
                   (not (str/blank? (:worktree card)))
                   (u/path-exists? (u/->path (:worktree card))))
          (let [inbox-path (u/path-resolve (:worktree card) ".kb-inbox.jsonl")
                line (json/generate-string {"ts" (u/now-epoch)
                                            "role" "human"
                                            "action" "answer"
                                            "content" answer-text})]
            (u/flock-append! inbox-path line)))
        (if (:json opts)
          (out-json card)
          (println (str "Answered card " (:id card) ": " answer-text))))
      (catch Exception e
        (fail! (.getMessage e))))))

(defn cmd-advance
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        [success? message gate-results] (b/advance! board card-id :agent (:agent opts ""))]
    (if (:json opts)
      (out-json {:success success? :message message :gate_results gate-results})
      (if success?
        (println (str "\u2713 " message))
        (do
          (binding [*out* *err*]
            (println (str "\u2717 " message)))
          (System/exit 1))))))

(defn cmd-done
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)
        [success? message gate-results] (b/done! board card-id :agent (:agent opts ""))]
    (if (:json opts)
      (out-json {:success success? :message message :gate_results gate-results})
      (if success?
        (println (str "\u2713 " message))
        (do
          (binding [*out* *err*]
            (println (str "\u2717 " message)))
          (System/exit 1))))))

(defn cmd-edit
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)]
    (try
      (let [card (b/edit-card! board card-id
                               :title (:title opts)
                               :priority (when-let [p (:priority opts)] (if (integer? p) p (Integer/parseInt (str p))))
                               :description (:desc opts)
                               :tags (when-let [t (:tags opts)]
                                       (if (str/blank? t) []
                                           (mapv str/trim (str/split t #",")))))]
        (if (:json opts)
          (out-json card)
          (println (str "Edited card " card-id))))
      (catch Exception e
        (fail! (.getMessage e))))))

(defn cmd-heartbeat
  [{:keys [opts]}]
  (let [card-id (->card-id opts)
        _ (when (str/blank? card-id) (fail! "card-id is required"))
        board (b/make-board)]
    (try
      (let [card (b/heartbeat! board card-id :agent (or (:agent opts) ""))]
        (if (:json opts)
          (out-json card)
          (println (str "Heartbeat recorded for card " card-id))))
      (catch Exception e
        (fail! (.getMessage e))))))

(defn cmd-help
  [_]
  (println "Usage: kb <command> [options]")
  (println)
  (println "Commands:")
  (println "  init     [--path .]                     Initialize a new kanban board")
  (println "  add      <title> [opts]                 Add a card to the board")
  (println "  pull     [opts]                         Pull next available card")
  (println "  move     <card-id> <lane> [opts]        Move a card to a lane (runs gates)")
  (println "  advance  <card-id> [opts]              Move card to next lane (runs gates)")
  (println "  done     <card-id> [opts]              Move card to final lane (runs all gates)")
  (println "  reject   <card-id> [opts]               Reject a card back to previous lane")
  (println "  block    <card-id> [opts]               Block a card")
  (println "  unblock  <card-id> [opts]               Unblock a card")
  (println "  approve  <card-id> [opts]               Approve a card pending approval")
  (println "  ask      <card-id> <question> [opts]    Ask the human a question (blocks card)")
  (println "  answer   <card-id> <answer> [opts]     Answer a pending question (unblocks card)")
  (println "  note     <card-id> <message> [opts]     Add a note to a card")
  (println "  log      <card-id> [opts]               Show card history")
  (println "  diff     <card-id> [opts]               Show card diff vs base branch")
  (println "  show     <card-id> [opts]               Show card details")
  (println "  gates    <card-id> [opts]               Show gates for the next lane transition")
  (println "  edit     <card-id> [opts]               Edit card title, priority, description, or tags")
  (println "  heartbeat <card-id> [opts]              Record an agent heartbeat")
  (println "  status   [opts]                         Show board status")
  (println "  context  <card-id> [opts]               Output card context for agent prompt")
  (println "  spawn    <card-id>                      Spawn a sub-agent for a card")
  (println "  cleanup  <card-id> [opts]               Remove worktree for a card")
  (println "  serve    [--host 127.0.0.1] [--port 8741]  Start web UI server")
  (println "  recover  [opts]                         Detect and clean orphaned resources")
  (System/exit 1))

;; ── Dispatch table ────────────────────────────────────────────

(def dispatch-table
  [{:cmds ["init"] :fn cmd-init :args->opts [:path]}
   {:cmds ["add"] :fn cmd-add :args->opts [:title]}
   {:cmds ["pull"] :fn cmd-pull}
   {:cmds ["move"] :fn cmd-move :args->opts [:card-id :lane]}
   {:cmds ["advance"] :fn cmd-advance :args->opts [:card-id]}
   {:cmds ["done"] :fn cmd-done :args->opts [:card-id]}
   {:cmds ["reject"] :fn cmd-reject :args->opts [:card-id]}
   {:cmds ["block"] :fn cmd-block :args->opts [:card-id]}
   {:cmds ["unblock"] :fn cmd-unblock :args->opts [:card-id]}
   {:cmds ["approve"] :fn cmd-approve :args->opts [:card-id]}
   {:cmds ["ask"] :fn cmd-ask :args->opts [:card-id :question]}
   {:cmds ["answer"] :fn cmd-answer :args->opts [:card-id :answer]}
   {:cmds ["note"] :fn cmd-note :args->opts [:card-id :message]}
   {:cmds ["log"] :fn cmd-log :args->opts [:card-id]}
   {:cmds ["diff"] :fn cmd-diff :args->opts [:card-id]}
   {:cmds ["show"] :fn cmd-show :args->opts [:card-id]}
   {:cmds ["gates"] :fn cmd-gates :args->opts [:card-id]}
   {:cmds ["edit"] :fn cmd-edit :args->opts [:card-id]}
   {:cmds ["heartbeat"] :fn cmd-heartbeat :args->opts [:card-id]}
   {:cmds ["status"] :fn cmd-status}
   {:cmds ["context"] :fn cmd-context :args->opts [:card-id]}
   {:cmds ["spawn"] :fn cmd-spawn :args->opts [:card-id]}
   {:cmds ["cleanup"] :fn cmd-cleanup :args->opts [:card-id]}
   {:cmds ["serve"] :fn cmd-serve}
   {:cmds ["recover"] :fn cmd-recover}
   {:cmds [] :fn cmd-help}])

;; ── Entry point ───────────────────────────────────────────────

(defn -main [& args]
  (try
    (cli/dispatch dispatch-table args {})
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Error: " (.getMessage e))))
      (System/exit 1))))

;; Alias for bb -m kb.cli invocation style
(def main -main)
