#!/usr/bin/env bb
;; kb CLI integration tests
;; Run from project root: bb tests/integration.clj

(ns kb.test
  (:require [babashka.process :as proc]
            [clojure.string :as str]
            [clojure.java.io :as io]))

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

;; ── Summary ────────────────────────────────────────────────────

(println (str "\n" (apply str (repeat 50 "="))))
(println (str "  " @pass " passed, " @fail " failed"))
(when (seq @errors)
  (println "\n  Failures:")
  (doseq [[name msg] @errors]
    (println (str "    " name ": " msg))))
(println (apply str (repeat 50 "=")))
(System/exit (if (zero? @fail) 0 1))