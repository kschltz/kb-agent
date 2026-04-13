#!/usr/bin/env bb
;; kb CLI integration tests
;; Run from project root: bb tests/integration.clj

(ns kb.test
  (:require [babashka.process :as proc]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [kb.board :as b]))

(def pass (atom 0))
(def fail (atom 0))
(def errors (atom []))

(defn sh [& args]
  (let [r (apply proc/shell {:out :string :err :string :continue true} (mapv str args))]
    (assoc r :ok (zero? (:exit r)))))

(defn sh-in [dir & args]
  (let [r (apply proc/shell {:out :string :err :string :continue true :dir (str dir)} (mapv str args))]
    (assoc r :ok (zero? (:exit r)))))

(defn make-repo []
  (let [d (-> (java.nio.file.Files/createTempDirectory "kb-test" (into-array java.nio.file.attribute.FileAttribute []))
              .toString)]
    (sh-in d "git" "init")
    (sh-in d "git" "config" "user.email" "t@t.com")
    (sh-in d "git" "config" "user.name" "T")
    (spit (str d "/README.md") "# test\n")
    (sh-in d "git" "add" ".")
    (sh-in d "git" "commit" "-m" "init")
    d))

(def project-dir (System/getProperty "user.dir"))

(defn kb [dir & args]
  (let [cmd (into [(str project-dir "/bin/kb")] (mapv str args))
        r (apply proc/shell {:out :string :err :string :continue true :dir (str dir)} cmd)]
    (assoc r :ok (zero? (:exit r)))))

(defn txt [r] (str/trim (:out r "")))

(defn T [name expr msg]
  (if expr
    (do (swap! pass inc) (println (str "  \u2713 " name)))
    (do (swap! fail inc) (swap! errors conj [name msg]) (println (str "  \u2717 " name ": " msg)))))

(defn cleanup [dir]
  (proc/shell {:continue true} "rm" "-rf" dir))

;; ── Run tests ─────────────────────────────────────────────────

(let [dir (make-repo)]

  (println "\n== Init ==")
  (let [r (kb dir "init")]
    (T "init exits 0" (:ok r) "init failed")
    (T "board.yaml exists" (.exists (io/file dir ".kanban" "board.yaml")) "no board.yaml")
    (T "cards dir exists" (.exists (io/file dir ".kanban" "cards")) "no cards dir"))

  (let [r (kb dir "init")]
    (T "second init fails" (not (:ok r)) "second init should fail"))

  (println "\n== Card CRUD ==")
  (let [r (kb dir "add" "Fix the auth bug")]
    (T "add exits 0" (:ok r) "add failed")
    (T "card id 001" (str/includes? (txt r) "001") "expected 001")
    (T "card in backlog" (str/includes? (txt r) "backlog") "expected backlog"))

  (let [r (kb dir "add" "Add tests" "--tags" "testing,urgent" "--priority" "3")]
    (T "add with opts exits 0" (:ok r) "add with opts failed"))

  (let [r (kb dir "show" "001")]
    (T "show exits 0" (:ok r) "show failed")
    (T "show has title" (str/includes? (txt r) "Fix the auth bug") "title missing")
    (T "show has lane" (str/includes? (txt r) "backlog") "lane missing"))

  (let [r (kb dir "edit" "001" "--priority" "5")]
    (T "edit priority exits 0" (:ok r) "edit priority failed")
    (let [s (kb dir "show" "001")]
      (T "priority updated" (str/includes? (txt s) "Priority: 5") "priority not 5")))

  (let [r (kb dir "edit" "001" "--title" "Fix auth v2")]
    (T "edit title exits 0" (:ok r) "edit title failed")
    (let [s (kb dir "show" "001")]
      (T "title updated" (str/includes? (txt s) "Fix auth v2") "title not updated")))

  (println "\n== Block / Unblock ==")
  (kb dir "block" "001" "--reason" "waiting on design")
  (let [s (kb dir "show" "001")]
    (T "card is blocked" (str/includes? (txt s) "Blocked:  true") "not blocked")
    (T "block reason shown" (str/includes? (txt s) "waiting on design") "reason missing"))
  (kb dir "unblock" "001")
  (let [s (kb dir "show" "001")]
    (T "card is unblocked" (str/includes? (txt s) "Blocked:  false") "still blocked"))

  (println "\n== Ask / Answer ==")
  (let [r (kb dir "ask" "002" "Which testing framework?" "--agent" "test-bot")]
    (T "ask exits 0" (:ok r) "ask failed")
    (T "question echoed" (str/includes? (txt r) "Which testing framework?") "question not echoed"))
  (let [s (kb dir "show" "002")]
    (T "card blocked by question" (str/includes? (txt s) "Blocked:  true") "not blocked by question")
    (T "question shown" (str/includes? (txt s) "Question: Which testing framework?") "question field missing"))

  (let [r (kb dir "answer" "002" "Use clojure.test")]
    (T "answer exits 0" (:ok r) "answer failed")
    (T "answer echoed" (str/includes? (txt r) "Use clojure.test") "answer not echoed"))
  (let [s (kb dir "show" "002")]
    (T "card unblocked by answer" (str/includes? (txt s) "Blocked:  false") "still blocked after answer"))

  (let [r (kb dir "log" "002")]
    (T "log has ask event" (str/includes? (txt r) "ask") "no ask in history")
    (T "log has answer event" (str/includes? (txt r) "answer") "no answer in history"))

  (println "\n== Notes & Heartbeat ==")
  (let [r (kb dir "note" "001" "Focus on error handling")]
    (T "note exits 0" (:ok r) "note failed"))

  (let [r (kb dir "heartbeat" "001" "--agent" "bot-42")]
    (T "heartbeat exits 0" (:ok r) "heartbeat failed")
    (let [s (kb dir "show" "001")]
      (T "heartbeat in show" (str/includes? (txt s) "Heartbeat:") "heartbeat not shown")))

  (let [r (kb dir "log" "001")]
    (T "log has created" (str/includes? (txt r) "created") "no created event")
    (T "log has note" (str/includes? (txt r) "note") "no note event")
    (T "log has heartbeat" (str/includes? (txt r) "heartbeat") "no heartbeat event"))

  (println "\n== Advance & Done ==")
  (let [r (kb dir "advance" "001")]
    (T "advance exits 0" (:ok r) "advance failed")
    (T "advance moved to in-progress" (str/includes? (txt r) "in-progress") "wrong lane"))
  (let [s (kb dir "show" "001")]
    (T "lane is in-progress" (str/includes? (txt s) "Lane:     in-progress") "not in in-progress"))

  (kb dir "move" "002" "in-progress")
  (let [r (kb dir "advance" "002")]
    (T "advance 002 exits 0" (:ok r) "advance 002 failed")
    (T "advance 002 to review" (str/includes? (txt r) "review") "002 not in review"))

  (let [r (kb dir "done" "002")]
    (T "done exits 0" (:ok r) "done failed")
    (T "done moved to done" (str/includes? (txt r) "done") "not in done"))

  (println "\n== Gates ==")
  (let [r (kb dir "gates" "001")]
    (T "gates exits 0" (:ok r) "gates failed")
    (T "gates shows output" (or (str/includes? (txt r) "Gates for card")
                                 (str/includes? (txt r) "No gates")) "no gates output"))
  (let [r (kb dir "gates" "002")]
    (T "gates for last-lane card" (str/includes? (txt r) "No gates") "should say no gates"))

  (println "\n== Status ==")
  (let [r (kb dir "status")]
    (T "status exits 0" (:ok r) "status failed")
    (T "status has BACKLOG" (str/includes? (txt r) "BACKLOG") "no BACKLOG")
    (T "status has card" (str/includes? (txt r) "Fix auth v2") "card title missing"))

  (let [r (kb dir "status" "--json")]
    (T "status --json exits 0" (:ok r) "status --json failed"))

  (println "\n== Context ==")
  (let [r (kb dir "context" "001")]
    (T "context exits 0" (:ok r) "context failed")
    (T "context has title" (str/includes? (txt r) "Fix auth v2") "title missing")
    (T "context has kb note" (str/includes? (txt r) "kb note") "no kb note")
    (T "context has kb ask" (str/includes? (txt r) "kb ask") "no kb ask")
    (T "context has kb advance" (str/includes? (txt r) "kb advance") "no kb advance")
    (T "context has kb heartbeat" (str/includes? (txt r) "kb heartbeat") "no kb heartbeat")
    (T "context has board summary" (str/includes? (txt r) "Board summary") "no board summary")
    (T "context has human notes" (str/includes? (txt r) "Recent human notes") "no human notes section"))

  (println "\n== Context compaction ==")
  (let [r (kb dir "context" "001" "--strategy" "summary")]
    (T "context --strategy summary exits 0" (:ok r) "context --strategy summary failed")
    (T "context summary has summary mode" (str/includes? (txt r) "summary mode") "no summary mode label"))
  (let [r (kb dir "context" "001" "--strategy" "recent")]
    (T "context --strategy recent exits 0" (:ok r) "context --strategy recent failed")
    (T "context recent has last 10 label" (str/includes? (txt r) "last 10") "no recent label"))
  (let [r (kb dir "context" "001" "--budget" "200")]
    (T "context --budget exits 0" (:ok r) "context --budget failed"))
  (let [r (kb dir "context" "001" "--compact" "--keep" "3")]
    (T "context --compact exits 0" (:ok r) "context --compact failed")
    (T "compact reports result" (or (str/includes? (txt r) "Compacted")
                                    (str/includes? (txt r) "compact enough"))
        "no compact result"))
  ;; After compact, context should still work
  (let [r (kb dir "context" "001")]
    (T "context after compact exits 0" (:ok r) "context after compact failed"))

  ;; Progressive context: --gates-only
  (let [r (kb dir "context" "001" "--gates-only")]
    (T "context --gates-only exits 0" (:ok r) "context --gates-only failed")
    (T "context gates-only focused" (not (str/includes? (txt r) "## History")) "should not have full history")
    (T "context gates-only has title" (str/includes? (txt r) "Fix auth v2") "title missing in gates-only"))

  ;; Progressive context: --deps-only
  (let [r (kb dir "context" "002" "--deps-only")]
    (T "context --deps-only exits 0" (:ok r) "context --deps-only failed"))

  ;; Context --json: structured output
  (let [r (kb dir "context" "001" "--json")]
    (T "context --json exits 0" (:ok r) "context --json failed")
    (let [output (txt r)]
      (T "context --json is valid JSON" (try (do (cheshire.core/parse-string output) true)
                                             (catch Exception _ false))
         "not valid JSON")
      (let [parsed (cheshire.core/parse-string output)]
        (T "context --json has card key" (contains? parsed "card") "missing card key")
        (T "context --json has description key" (contains? parsed "description") "missing description key")
        (T "context --json has lane_instructions key" (contains? parsed "lane_instructions") "missing lane_instructions key")
        (T "context --json has next_lane key" (contains? parsed "next_lane") "missing next_lane key")
        (T "context --json has next_gates key" (contains? parsed "next_gates") "missing next_gates key")
        (T "context --json has history key" (contains? parsed "history") "missing history key")
        (T "context --json has dependencies key" (contains? parsed "dependencies") "missing dependencies key")
        (T "context --json has rejection_warning key" (contains? parsed "rejection_warning") "missing rejection_warning key")
        (T "context --json card has id" (contains? (get parsed "card") "id") "card missing id")
        (T "context --json card has title" (contains? (get parsed "card") "title") "card missing title")
        (T "context --json card has lane" (contains? (get parsed "card") "lane") "card missing lane"))))

  ;; Context --json --gates-only: subset of fields
  (let [r (kb dir "context" "001" "--json" "--gates-only")]
    (T "context --json --gates-only exits 0" (:ok r) "context --json --gates-only failed")
    (let [parsed (cheshire.core/parse-string (txt r))]
      (T "context --json gates-only has card" (contains? parsed "card") "missing card in gates-only")
      (T "context --json gates-only has next_gates" (contains? parsed "next_gates") "missing next_gates in gates-only")
      (T "context --json gates-only lacks history" (not (contains? parsed "history")) "should not have history in gates-only")
      (T "context --json gates-only lacks dependencies" (not (contains? parsed "dependencies")) "should not have dependencies in gates-only")))

  ;; Context --json --deps-only: subset of fields
  (let [r (kb dir "context" "002" "--json" "--deps-only")]
    (T "context --json --deps-only exits 0" (:ok r) "context --json --deps-only failed")
    (let [parsed (cheshire.core/parse-string (txt r))]
      (T "context --json deps-only has card" (contains? parsed "card") "missing card in deps-only")
      (T "context --json deps-only has dependencies" (contains? parsed "dependencies") "missing dependencies in deps-only")
      (T "context --json deps-only lacks history" (not (contains? parsed "history")) "should not have history in deps-only")
      (T "context --json deps-only lacks next_gates" (not (contains? parsed "next_gates")) "should not have next_gates in deps-only")))

  (println "\n== Reject ==")
  (kb dir "add" "Card to reject" "--lane" "in-progress")
  (let [r (kb dir "reject" "003" "--reason" "needs work")]
    (T "reject exits 0" (:ok r) "reject failed")
    (T "reject moved to backlog" (str/includes? (txt r) "backlog") "not rejected to backlog"))

  (println "\n== Diff ==")
  (let [r (kb dir "diff" "001" "--stat")]
    (T "diff --stat exits 0" (:ok r) "diff --stat failed"))

  (println "\n== Cleanup ==")
  (cleanup dir))

;; ── Worktree root resolution test ───────────────────────────────

(let [dir (make-repo)]
  (println "\n== Worktree root resolution ==")
  (let [r (kb dir "init")]
    (T "wt: init exits 0" (:ok r) "init failed"))

  ;; Add a card and pull it (creates worktree)
  (let [r (kb dir "add" "Worktree card")]
    (T "wt: add exits 0" (:ok r) "add failed"))
  (let [r (kb dir "pull" "001" "--agent" "test-bot" "--lane" "in-progress")]
    (T "wt: pull exits 0" (:ok r) "pull failed"))

  ;; Find worktree path from card metadata
  (let [show-r (kb dir "show" "001")
        wt-match (re-find #"Worktree:\s+(\S+)" (txt show-r))
        wt-path  (second wt-match)]
    (T "wt: pull created worktree" (some? wt-path) "no worktree path in show")

    ;; The key test: kb status from INSIDE the worktree should find
    ;; the same board (at the project root), not a stale shadow copy
    (when wt-path
      (let [status-r (kb wt-path "status")]
        (T "wt: status from worktree exits 0" (:ok status-r) "status failed from worktree")
        (T "wt: status shows all cards" (str/includes? (txt status-r) "BACKLOG")
            (str "status from worktree missing cards: " (txt status-r))))))

  (println "\n== Worktree cleanup ==")
  (cleanup dir))

;; ── Notification hooks test ─────────────────────────────────────

(let [dir (make-repo)]
  (println "\n== Notification hooks ==")
  (let [r (kb dir "init")]
    (T "hooks: init exits 0" (:ok r) "init failed"))

  ;; Configure a notification hook that writes to a temp file
  (let [hook-file (str dir "/hook-output.txt")
        board-yaml (str dir "/.kanban/board.yaml")
        config (slurp board-yaml)]
    (spit board-yaml (str config "\nnotifications:\n  hooks:\n    - event: blocked\n      command: \"echo 'BLOCKED:{card_id}:{reason}' >> " hook-file "\"\n    - event: unblocked\n      command: \"echo 'UNBLOCKED:{card_id}' >> " hook-file "\"\n    - event: ask\n      command: \"echo 'ASK:{card_id}:{question}' >> " hook-file "\"\n    - event: answer\n      command: \"echo 'ANSWER:{card_id}:{answer}' >> " hook-file "\"\n    - event: created\n      command: \"echo 'CREATED:{card_id}:{card_title}' >> " hook-file "\"\n"))
    (let [r (kb dir "add" "Hook test card")]
      (T "hooks: add exits 0" (:ok r) "add failed")
      (T "hooks: add card id" (str/includes? (txt r) "001") "expected 001")
      ;; Check that created hook fired
      (let [hook-out (slurp hook-file)]
        (T "hooks: created hook fired" (str/includes? hook-out "CREATED:001") "created hook not fired")
        (T "hooks: created hook has title" (str/includes? hook-out "Hook test card") "created hook missing title")))

    ;; Test block hook
    (let [r (kb dir "block" "001" "--reason" "testing hooks")]
      (T "hooks: block exits 0" (:ok r) "block failed")
      (let [hook-out (slurp hook-file)]
        (T "hooks: blocked hook fired" (str/includes? hook-out "BLOCKED:001") "blocked hook not fired")
        (T "hooks: blocked hook has reason" (str/includes? hook-out "testing hooks") "blocked hook missing reason")))

    ;; Test unblock hook
    (let [r (kb dir "unblock" "001")]
      (T "hooks: unblock exits 0" (:ok r) "unblock failed")
      (let [hook-out (slurp hook-file)]
        (T "hooks: unblocked hook fired" (str/includes? hook-out "UNBLOCKED:001") "unblocked hook not fired")))

    ;; Test ask hook
    (let [r (kb dir "ask" "001" "What framework?")]
      (T "hooks: ask exits 0" (:ok r) "ask failed")
      (let [hook-out (slurp hook-file)]
        (T "hooks: ask hook fired" (str/includes? hook-out "ASK:001") "ask hook not fired")
        (T "hooks: ask hook has question" (str/includes? hook-out "What framework") "ask hook missing question")))

    ;; Test answer hook
    (let [r (kb dir "answer" "001" "Use Jest")]
      (T "hooks: answer exits 0" (:ok r) "answer failed")
      (let [hook-out (slurp hook-file)]
        (T "hooks: answer hook fired" (str/includes? hook-out "ANSWER:001") "answer hook not fired")
        (T "hooks: answer hook has answer" (str/includes? hook-out "Use Jest") "answer hook missing answer"))))

  (println "\n== Hook cleanup ==")
  (cleanup dir))

;; ── Approval timeout / reject-approval test ─────────────────────

(let [dir (make-repo)]
  (println "\n== Approval timeouts ==")
  (let [r (kb dir "init")]
    (T "at: init exits 0" (:ok r) "init failed"))

  ;; Configure the review lane with requires_approval
  (let [board-yaml (str dir "/.kanban/board.yaml")]
    (spit board-yaml (str "project: test\nbase_branch: master\nmerge_strategy: squash\n"
                          "lanes:\n- name: backlog\n- name: in-progress\n  max_wip: 5\n"
                          "- name: review\n  requires_approval: true\n  approval_timeout: 1s\n"
                          "  approval_timeout_action: reject\n- name: done\n  on_enter: merge\n"))

    ;; Add a card and move it to review (triggers requires_approval)
    (kb dir "add" "Approval test card")
    (kb dir "move" "001" "in-progress")
    (let [r (kb dir "move" "001" "review")]
      (T "at: move to review exits 0" (:ok r) "move to review failed")
      (T "at: pending approval" (str/includes? (txt r) "awaiting approval") "should be pending approval"))

    ;; Test approve --reject
    (let [r (kb dir "approve" "001" "--reject" "--reason" "needs more work")]
      (T "at: approve --reject exits 0" (:ok r) "reject approval failed")
      (T "at: card rejected" (str/includes? (txt r) "Rejected") "should say rejected"))

    ;; Verify card is back in in-progress
    (let [s (kb dir "show" "001")]
      (T "at: card back in in-progress" (str/includes? (txt s) "in-progress") "card not back in in-progress"))

    ;; Move to review again and approve normally
    (let [r (kb dir "move" "001" "review")]
      (T "at: move to review again exits 0" (:ok r) "move to review again failed"))
    (let [r (kb dir "approve" "001")]
      (T "at: approve exits 0" (:ok r) "approve failed")
      (T "at: card approved" (str/includes? (txt r) "Approved") "should say approved")))

  (println "\n== Approval timeout cleanup ==")
  (cleanup dir))

;; ── Heartbeat timeout test ───────────────────────────────────────

(let [dir (make-repo)]
  (println "\n== Heartbeat timeouts ==")
  (let [r (kb dir "init")]
    (T "ht: init exits 0" (:ok r) "init failed"))

  ;; Configure heartbeat_timeout on in-progress lane
  (let [board-yaml (str dir "/.kanban/board.yaml")]
    (spit board-yaml (str "project: test\nbase_branch: master\nmerge_strategy: squash\n"
                          "lanes:\n- name: backlog\n- name: in-progress\n  max_wip: 5\n"
                          "  heartbeat_timeout: 1s\n- name: review\n  max_wip: 3\n"
                          "- name: done\n  on_enter: merge\n"))

    ;; Add a card and move to in-progress
    (kb dir "add" "Heartbeat test card")
    (kb dir "move" "001" "in-progress")

    ;; Record a heartbeat
    (let [r (kb dir "heartbeat" "001" "--agent" "test-bot")]
      (T "ht: heartbeat exits 0" (:ok r) (str "heartbeat failed: " (txt r))))

    ;; Wait for heartbeat to be stale (2 seconds since timeout is 1s)
    (Thread/sleep 2000)

    ;; Run the stale heartbeat check — make board pointing at the test .kanban dir
    (let [board (b/make-board (str dir "/.kanban"))]
      (let [stale (b/check-stale-heartbeats! board)]
        (T "ht: stale heartbeat detected" (seq stale) "no stale heartbeats detected")))

    ;; Card should now be blocked
    (let [s (kb dir "show" "001")]
      (T "ht: card blocked after timeout" (str/includes? (txt s) "Blocked:  true") "card not blocked after timeout")))

  (println "\n== Heartbeat timeout cleanup ==")
  (cleanup dir))

;; ── Card dependencies test ─────────────────────────────────────────

(let [dir (make-repo)]
  (println "\n== Card dependencies ==")
  (let [r (kb dir "init")]
    (T "deps: init exits 0" (:ok r) "init failed"))

  ;; Add two cards
  (kb dir "add" "Foundation card")
  (kb dir "add" "Dependent card")

  ;; Link: card 002 depends on 001
  (let [r (kb dir "link" "002" "001")]
    (T "deps: link exits 0" (:ok r) "link failed")
    (T "deps: link output" (str/includes? (txt r) "depends on 001") "link message wrong"))

  ;; Duplicate link should fail
  (let [r (kb dir "link" "002" "001")]
    (T "deps: duplicate link fails" (not (:ok r)) "duplicate link should fail"))

  ;; Self-link should fail
  (let [r (kb dir "link" "001" "001")]
    (T "deps: self-link fails" (not (:ok r)) "self-link should fail"))

  ;; Show should display depends-on
  (let [s (kb dir "show" "002")]
    (T "deps: show has depends-on" (str/includes? (txt s) "Depends on: 001") "depends-on missing in show"))

  ;; Deps command
  (let [r (kb dir "deps" "002")]
    (T "deps: deps exits 0" (:ok r) "deps failed")
    (T "deps: shows dependency" (str/includes? (txt r) "001") "dep not shown")
    (T "deps: shows unsatisfied" (str/includes? (txt r) "unsatisfied") "dep should be unsatisfied"))

  ;; Deps command on card 001 should show it blocks 002
  (let [r (kb dir "deps" "001")]
    (T "deps: card 001 has no deps" (str/includes? (txt r) "none") "001 should have no deps")
    (T "deps: card 001 blocks 002" (str/includes? (txt r) "blocks") "001 should block 002"))

  ;; Pull should skip card 002 (unsatisfied dep), claim 001
  (let [r (kb dir "pull" "--agent" "test-dep-agent")]
    (T "deps: pull skips 002" (:ok r) "pull failed")
    (T "deps: pull claims 001 not 002" (str/includes? (txt r) "001") "should claim 001, not 002"))

  ;; Move 001 to done, now 002's dep is satisfied
  (kb dir "move" "001" "in-progress")
  (kb dir "move" "001" "review")
  (kb dir "move" "001" "done")

  ;; Now pull should claim 002
  (let [r (kb dir "pull" "--agent" "test-dep-agent2")]
    (T "deps: pull claims 002 after dep done" (:ok r) "pull failed after dep done")
    (T "deps: pull claims 002" (str/includes? (txt r) "002") "should claim 002"))

  ;; Unlink test
  (kb dir "add" "Card C")
  (kb dir "link" "003" "002")
  (let [r (kb dir "unlink" "003" "002")]
    (T "deps: unlink exits 0" (:ok r) "unlink failed")
    (T "deps: unlink output" (str/includes? (txt r) "no longer depends on") "unlink message wrong"))

  ;; Unlink non-existent dep should fail
  (let [r (kb dir "unlink" "003" "002")]
    (T "deps: unlink non-dep fails" (not (:ok r)) "unlink non-dep should fail"))

  ;; Context should show dependencies
  (let [r (kb dir "context" "002")]
    (T "deps: context shows dependencies" (str/includes? (txt r) "Dependencies") "no dependencies in context"))

  (println "\n== Deps cleanup ==")
  (cleanup dir))

;; ── Agent confidence test ──────────────────────────────────────────

(let [dir (make-repo)]
  (println "\n== Agent confidence ==")
  (let [r (kb dir "init")]
    (T "conf: init exits 0" (:ok r) "init failed"))

  ;; Configure review lane with min_confidence
  (let [board-yaml (str dir "/.kanban/board.yaml")]
    (spit board-yaml (str "project: test\nbase_branch: master\nmerge_strategy: squash\n"
                          "lanes:\n- name: backlog\n- name: in-progress\n  max_wip: 5\n"
                          "- name: review\n  min_confidence: 80\n  max_wip: 3\n"
                          "- name: done\n  on_enter: merge\n")))

  ;; Add a card and move to in-progress
  (kb dir "add" "Confidence test card")
  (kb dir "move" "001" "in-progress")

  ;; Move to review with low confidence — should auto-block
  (let [r (kb dir "move" "001" "review" "--confidence" "50")]
    (T "conf: low confidence move fails" (not (:ok r)) "low confidence move should fail")
    (T "conf: blocked message" (str/includes? (str (:out r "") (:err r "")) "Blocked") "should say blocked"))

  ;; Verify card is blocked
  (let [s (kb dir "show" "001")]
    (T "conf: card is blocked" (str/includes? (txt s) "Blocked:  true") "card not blocked"))

  ;; Unblock it manually
  (kb dir "unblock" "001")

  ;; Move with high confidence — should succeed
  (let [r (kb dir "move" "001" "review" "--confidence" "90")]
    (T "conf: high confidence move exits 0" (:ok r) "high confidence move failed")
    (T "conf: moved to review" (str/includes? (txt r) "review") "not in review"))

  ;; Move without confidence flag — should succeed (no check)
  (kb dir "move" "001" "done")
  (let [s (kb dir "show" "001")]
    (T "conf: in done lane" (str/includes? (txt s) "Lane:     done") "not in done"))

  (println "\n== Confidence cleanup ==")
  (cleanup dir))

;; ── kb whoami ──────────────────────────────────────────────────

(let [dir (make-repo)]

  (println "\n== whoami ==")
  (let [r (kb dir "init")]
    (T "whoami: init exits 0" (:ok r) "init failed"))

  ;; Add and pull a card to create a worktree
  (kb dir "add" "Whoami test card")
  (kb dir "pull" "001" "--agent" "test-bot" "--lane" "in-progress")

  ;; Find the worktree path
  (let [show-r  (kb dir "show" "001")
        wt-match (re-find #"Worktree:\s+(\S+)" (txt show-r))
        wt-path  (second wt-match)]
    (T "whoami: worktree created" (some? wt-path) "no worktree in show output")

    (when wt-path
      ;; From inside the worktree — should identify card 001
      (let [r (kb wt-path "whoami")]
        (T "whoami: exits 0 from worktree" (:ok r) (str "exit " (:exit r) ": " (:err r)))
        (T "whoami: shows card id" (str/includes? (txt r) "#001") (txt r))
        (T "whoami: shows lane label" (str/includes? (txt r) "Lane:") (txt r)))

      ;; --json mode
      (let [r (kb wt-path "whoami" "--json")]
        (T "whoami: json exits 0" (:ok r) (:err r))
        (T "whoami: json has id field" (str/includes? (txt r) "\"id\"") (txt r)))

      ;; --export mode
      (let [r (kb wt-path "whoami" "--export")]
        (T "whoami: export exits 0" (:ok r) (:err r))
        (T "whoami: export has KB_CARD_ID" (str/includes? (txt r) "KB_CARD_ID=001") (txt r)))))

  ;; Outside a worktree — should exit non-zero
  (let [r (kb dir "whoami")]
    (T "whoami: fails outside worktree" (not (:ok r)) "should fail outside worktree"))

  (println "\n== whoami cleanup ==")
  (cleanup dir))

;; ── Dependency-aware spawn-parallel ───────────────────────────

(let [dir (make-repo)]

  (println "\n== spawn-parallel dep-awareness ==")
  (kb dir "init")

  ;; Create chain A→B→C: only A has satisfied deps
  (kb dir "add" "Chain A")                         ;; 001, no deps
  (kb dir "add" "Chain B")                         ;; 002
  (kb dir "add" "Chain C")                         ;; 003
  (kb dir "link" "002" "001")                      ;; B depends on A
  (kb dir "link" "003" "002")                      ;; C depends on B

  ;; spawn-parallel --count 3 with no agent_command configured exits non-zero,
  ;; so just test the pull selection directly via kb pull loop
  (let [r1 (kb dir "pull" "--agent" "test-agent")]
    (T "spawn-parallel deps: first pull gets A (no deps)" (str/includes? (txt r1) "001") (txt r1)))

  ;; B and C should NOT be available (A is in-progress, not done)
  (let [r2 (kb dir "pull" "--agent" "test-agent")]
    (T "spawn-parallel deps: no more independent cards" (not (:ok r2)) (txt r2)))

  ;; Verify B is still in backlog (not pulled)
  (let [s (kb dir "show" "002")]
    (T "spawn-parallel deps: B remains in backlog" (str/includes? (txt s) "Lane:     backlog") (txt s)))

  ;; Verify C is still in backlog
  (let [s (kb dir "show" "003")]
    (T "spawn-parallel deps: C remains in backlog" (str/includes? (txt s) "Lane:     backlog") (txt s)))

  (println "\n== spawn-parallel dep-awareness cleanup ==")
  (cleanup dir))

;; ── Priority-aware kb pull ─────────────────────────────────────

(let [dir (make-repo)]

  (println "\n== Priority-aware pull ==")
  (kb dir "init")

  ;; Add 3 cards: two with priority 5 (FIFO among them), one with priority 0
  (kb dir "add" "High priority A" "--priority" "5")   ;; 001
  (kb dir "add" "Low priority" "--priority" "0")      ;; 002
  (kb dir "add" "High priority B" "--priority" "5")   ;; 003

  ;; First pull: should pick 001 (priority 5, created first — FIFO tiebreak)
  (let [r (kb dir "pull" "--agent" "test")]
    (T "priority pull: exits 0" (:ok r) (:err r))
    (T "priority pull: picks highest priority (FIFO first)" (str/includes? (txt r) "001") (txt r)))

  ;; Second pull: should pick 003 (priority 5, next by FIFO)
  (let [r (kb dir "pull" "--agent" "test")]
    (T "priority pull: FIFO tiebreak (second)" (str/includes? (txt r) "003") (txt r)))

  ;; Third pull: should pick 002 (priority 0 — lowest)
  (let [r (kb dir "pull" "--agent" "test")]
    (T "priority pull: lowest priority last" (str/includes? (txt r) "002") (txt r)))

  (println "\n== Priority-aware pull cleanup ==")
  (cleanup dir))

;; ── kb undo / kb trash ─────────────────────────────────────────

(let [dir (make-repo)]

  (println "\n== undo / trash ==")
  (kb dir "init")
  (kb dir "add" "Undo test card")
  ;; Pull without positional card-id so --lane flag is parsed correctly
  (kb dir "pull" "--agent" "test-bot" "--lane" "in-progress")

  ;; Reject card and verify it moves back to backlog
  (let [r (kb dir "reject" "001" "--reason" "test rejection")]
    (T "undo: reject exits 0" (:ok r) (:err r)))

  (let [s (kb dir "show" "001")]
    (T "undo: card in backlog after reject" (str/includes? (txt s) "Lane:     backlog") (txt s)))

  ;; Trash list should have one entry
  (let [r (kb dir "trash" "list")]
    (T "undo: trash list exits 0" (:ok r) (:err r))
    (T "undo: trash list shows reject entry" (str/includes? (txt r) "reject") (txt r)))

  ;; kb undo restores card to in-progress
  (let [r (kb dir "undo")]
    (T "undo: exits 0" (:ok r) (:err r))
    (T "undo: reports restored" (str/includes? (txt r) "Undone") (txt r)))

  (let [s (kb dir "show" "001")]
    (T "undo: card restored to in-progress" (str/includes? (txt s) "Lane:     in-progress") (txt s)))

  ;; Trash list should now be empty after undo consumed the entry
  (let [r (kb dir "trash" "list")]
    (T "undo: trash list empty after undo" (str/includes? (txt r) "empty") (txt r)))

  ;; Test cleanup: snapshots card, undo restores worktree field
  (kb dir "cleanup" "001")
  (let [r (kb dir "undo")]
    (T "undo: cleanup undo exits 0" (:ok r) (:err r))
    (T "undo: cleanup undo reports restored" (str/includes? (txt r) "Undone") (txt r)))

  ;; Test trash purge with 9999 days — nothing to purge, but command should work
  (kb dir "reject" "001" "--reason" "purge test")
  (let [r (kb dir "trash" "purge" "--days" "9999")]
    (T "undo: trash purge exits 0" (:ok r) (:err r))
    (T "undo: trash purge reports count" (str/includes? (txt r) "Purged") (txt r)))

  ;; Recent entry survives the 9999-day purge
  (let [r (kb dir "trash" "list")]
    (T "undo: trash still has entry after 9999d purge" (str/includes? (txt r) "reject") (txt r)))

  ;; --json flag
  (let [r (kb dir "undo" "--json")]
    (T "undo: --json exits 0" (:ok r) (:err r))
    (T "undo: --json has restored field" (str/includes? (txt r) "restored") (txt r)))

  ;; Board should have nothing to undo now
  (let [r (kb dir "undo")]
    (T "undo: nothing to undo" (str/includes? (txt r) "Nothing") (txt r)))

  (println "\n== undo cleanup ==")
  (cleanup dir))

;; ── Context rejection warning test ─────────────────────────────

(let [dir (make-repo)]
  (println "\n== Context rejection warning ==")
  (let [r (kb dir "init")]
    (T "ctx-rej: init exits 0" (:ok r) "init failed"))

  ;; Add a card and pull it to in-progress
  (let [r (kb dir "add" "Rejection warning test card")]
    (T "ctx-rej: add exits 0" (:ok r) "add failed"))
  (let [r (kb dir "pull" "001" "--agent" "test-bot" "--lane" "in-progress")]
    (T "ctx-rej: pull exits 0" (:ok r) "pull failed"))

  ;; Reject the card with a reason
  (let [r (kb dir "reject" "001" "--reason" "logic is wrong, fix the algorithm")]
    (T "ctx-rej: reject exits 0" (:ok r) (:err r))
    (T "ctx-rej: reject moved to backlog" (str/includes? (txt r) "backlog") (txt r)))

  ;; Re-move the card to in-progress (simulating re-pull after rejection)
  (let [r (kb dir "move" "001" "in-progress")]
    (T "ctx-rej: re-move to in-progress exits 0" (:ok r) (:err r)))

  ;; Check context output contains rejection warning
  (let [r (kb dir "context" "001")]
    (T "ctx-rej: context exits 0" (:ok r) (:err r))
    (T "ctx-rej: context contains rejection warning header"
       (str/includes? (txt r) "Previous attempt rejected")
       (str "context output missing warning: " (subs (txt r) 0 (min 300 (count (txt r))))))
    (T "ctx-rej: context contains rejection reason"
       (str/includes? (txt r) "logic is wrong, fix the algorithm")
       (str "context output missing reason: " (subs (txt r) 0 (min 300 (count (txt r)))))))

  (println "\n== Context rejection warning cleanup ==")
  (cleanup dir))

;; ── Summary ────────────────────────────────────────────────────

(println (str "\n" (apply str (repeat 50 "="))))
(println (str "  " @pass " passed, " @fail " failed"))
(when (seq @errors)
  (println "\n  Failures:")
  (doseq [[name msg] @errors]
    (println (str "    " name ": " msg))))
(println (apply str (repeat 50 "=")))
(System/exit (if (zero? @fail) 0 1))