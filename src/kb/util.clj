(ns kb.util
  (:require [babashka.process :as proc]
            [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files Path Paths StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

;; ── Constants ────────────────────────────────────────────────

(def kanban-dir ".kanban")
(def board-file "board.yaml")
(def cards-dir "cards")
(def worktrees-dir "worktrees")

;; ── Path helpers ─────────────────────────────────────────────

(defn ->path
  "Coerce to java.nio.file.Path."
  ^Path [x]
  (cond
    (instance? Path x) x
    (instance? File x) (.toPath ^File x)
    (string? x)        (Paths/get x (into-array String []))
    :else              (throw (ex-info (str "Cannot coerce to Path: " (type x)) {:val x}))))

(defn path-resolve
  "Resolve child under parent."
  ^Path [parent & children]
  (reduce (fn [^Path p child] (.resolve p (str child)))
          (->path parent)
          children))

(defn path-exists? [p] (Files/exists (->path p) (into-array java.nio.file.LinkOption [])))

(defn path-directory? [p]
  (Files/isDirectory (->path p) (into-array java.nio.file.LinkOption [])))

(defn path-file? [p]
  (Files/isRegularFile (->path p) (into-array java.nio.file.LinkOption [])))

(defn mkdirs [p]
  (Files/createDirectories (->path p) (into-array FileAttribute [])))

(defn list-dirs
  "List immediate subdirectories of p, sorted."
  [p]
  (when (path-exists? p)
    (->> (.toFile (->path p))
         (.listFiles)
         (filter #(.isDirectory ^File %))
         (sort-by #(.getName ^File %))
         (mapv #(.toPath ^File %)))))

;; ── File I/O ─────────────────────────────────────────────────

(defn slurp-yaml [path]
  (when (path-exists? path)
    (yaml/parse-string (slurp (str path)) :keywords false)))

(defn spit-yaml [path data]
  (spit (str path) (yaml/generate-string data :dumper-options {:flow-style :block})))

(defn slurp-json-lines
  "Read a JSONL file into a vector of maps."
  [path]
  (if (path-exists? path)
    (->> (str/split-lines (slurp (str path)))
         (remove str/blank?)
         (mapv #(json/parse-string % false)))
    []))

(defn atomic-write!
  "Write content atomically via temp file + rename."
  [path content]
  (let [p      (->path path)
        parent (.getParent p)
        tmp    (Files/createTempFile parent "kb-" ".tmp" (into-array FileAttribute []))]
    (try
      (spit (str tmp) content)
      (Files/move tmp p (into-array [StandardCopyOption/REPLACE_EXISTING
                                     StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception e
        (Files/deleteIfExists tmp)
        (throw e)))))

(defn flock-append!
  "Append a line to a file with flock-based locking via shell flock.
   The >> operator creates the file if it doesn't exist, so no pre-check needed."
  [path line]
  (let [f (str path)]
    (apply proc/shell {:out :string :err :string :continue true}
           ["flock" f "sh" "-c" (str "printf '%s\\n' " (pr-str line) " >> " (pr-str f))])))

;; ── Git helpers ──────────────────────────────────────────────

(defn git
  "Run a git command. Returns {:exit int :out str :err str}."
  [& {:keys [args cwd]}]
  (let [cmd  (into ["git"] args)
        opts (cond-> {:out :string :err :string :continue true}
               cwd (assoc :dir (str cwd)))
        result (apply proc/shell opts cmd)]
    {:exit (:exit result)
     :out  (or (:out result) "")
     :err  (or (:err result) "")}))

;; ── Text helpers ─────────────────────────────────────────────

(defn slugify [title]
  (let [slug (-> title
                 str/lower-case
                 str/trim
                 (str/replace #"[^a-z0-9 ]" "")
                 str/trim
                 (str/replace #"\s+" "-"))]
    (subs slug 0 (min (count slug) 40))))

;; ── Find root ────────────────────────────────────────────────

(defn find-root
  "Walk up from start to find nearest .kanban/ directory. Returns Path."
  ([] (find-root "."))
  ([start]
   (loop [p (.toAbsolutePath (->path start))]
     (let [candidate (path-resolve p kanban-dir)]
       (cond
         (path-directory? candidate) candidate
         (= p (.getParent p))        (throw (ex-info "No .kanban/ directory found. Run `kb init` first." {}))
         :else                        (recur (.getParent p)))))))

;; ── Time helpers ─────────────────────────────────────────────

(defn now-epoch
  "Current unix epoch as double (seconds.millis)."
  []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn- format-epoch [ts pattern]
  (let [instant (java.time.Instant/ofEpochMilli (long (* ts 1000)))
        zdt     (.atZone instant (java.time.ZoneId/systemDefault))
        fmt     (java.time.format.DateTimeFormatter/ofPattern pattern)]
    (.format zdt fmt)))

(defn fmt-ts
  "Format epoch timestamp as YYYY-MM-DD HH:MM:SS."
  [ts]
  (format-epoch ts "yyyy-MM-dd HH:mm:ss"))

(defn fmt-time
  "Format epoch timestamp as HH:MM."
  [ts]
  (format-epoch ts "HH:mm"))

(defn parse-since
  "Parse a --since value: unix timestamp or ISO date string."
  [value]
  (or
   ;; Try numeric
   (try (Double/parseDouble value)
        (catch Exception _ nil))
   ;; Try ISO dates
   (some (fn [fmt]
           (try
             (let [dtf (java.time.format.DateTimeFormatter/ofPattern fmt)
                   ld  (java.time.LocalDate/parse value dtf)
                   zdt (.atStartOfDay ld (java.time.ZoneId/systemDefault))]
               (/ (double (.toEpochMilli (.toInstant zdt))) 1000.0))
             (catch Exception _ nil)))
         ["yyyy-MM-dd"])
   (some (fn [fmt]
           (try
             (let [dtf (java.time.format.DateTimeFormatter/ofPattern fmt)
                   ldt (java.time.LocalDateTime/parse value dtf)
                   zdt (.atZone ldt (java.time.ZoneId/systemDefault))]
               (/ (double (.toEpochMilli (.toInstant zdt))) 1000.0))
             (catch Exception _ nil)))
         ["yyyy-MM-dd'T'HH:mm:ss" "yyyy-MM-dd HH:mm:ss"])
   (throw (ex-info (str "Cannot parse timestamp: " (pr-str value)
                        ". Use a unix timestamp or ISO date.")
                   {:value value}))))
