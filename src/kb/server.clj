(ns kb.server
  "Web UI server: HTTP + WebSocket with filesystem watching.
   Uses org.httpkit.server which is built into Babashka."
  (:require [kb.util :as util]
            [kb.board :as board]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as hk])
  (:import [java.nio.file Path Files]))

;; ── MIME types ───────────────────────────────────────────────

(def mime-types
  {".html"  "text/html; charset=utf-8"
   ".js"    "application/javascript"
   ".css"   "text/css"
   ".svg"   "image/svg+xml"
   ".png"   "image/png"
   ".ico"   "image/x-icon"
   ".json"  "application/json"
   ".woff"  "font/woff"
   ".woff2" "font/woff2"
   ".map"   "application/json"})

;; ── WebSocket client registry ────────────────────────────────

;; atom holding a set of open http-kit channels
(def ws-clients (atom #{}))

(defn- add-client! [ch]
  (swap! ws-clients conj ch))

(defn- remove-client! [ch]
  (swap! ws-clients disj ch))

(defn- broadcast! [msg-str]
  (doseq [ch @ws-clients]
    (try
      (hk/send! ch msg-str)
      (catch Exception _
        (remove-client! ch)))))

;; ── Board state ──────────────────────────────────────────────

(defn get-board-state
  "Returns a map representing the full board state for JSON serialisation.
   Uses all-cards once + group-by to avoid N+1 directory scans per lane."
  ([]
   (get-board-state nil))
  ([kanban-root]
   (try
    (let [b        (board/make-board kanban-root)
          cards    (board/all-cards b)
          by-lane  (group-by :lane cards)]
      {:project     (get (:config b) "project" "")
       :base_branch (board/base-branch b)
       :lanes       (mapv (fn [lane-conf]
                            (let [lane-name (get lane-conf "name")]
                              (assoc lane-conf
                                     "cards"
                                     (mapv (fn [card]
                                             (let [history   (board/load-history b (:id card))
                                                   diff-stat (board/get-diff-stat b (:id card))]
                                               (assoc card
                                                      :history   history
                                                      :diff_stat (if (str/includes? diff-stat "(no branch)")
                                                                   ""
                                                                   diff-stat))))
                                           (get by-lane lane-name [])))))
                          (get (:config b) "lanes" []))
       :timestamp   (util/now-epoch)})
    (catch Exception _
      {:error "No .kanban/ found"}))))

;; ── Command dispatch ─────────────────────────────────────────

(defn handle-ui-command
  "Execute a UI command map and return a result map."
  [{:keys [action card_id lane reason message title description question answer] :as cmd}]
  (let [b (board/make-board)]
    (case action
      "move"
      (let [[ok? msg gate-results] (board/move! b card_id lane :agent "human-ui")]
        {:success      ok?
         :message      msg
         :gate_results (mapv identity gate-results)})

      "reject"
      (let [card (board/reject! b card_id :reason (or reason "") :agent "human-ui")]
        {:success true :card card})

      "block"
      (do (board/block! b card_id (or reason "Blocked via UI"))
          {:success true})

      "unblock"
      (do (board/unblock! b card_id)
          {:success true})

      "approve"
      (try
        (let [card (board/approve! b card_id :agent "human-ui")]
          {:success true :card card})
        (catch Exception e
          {:success false :message (.getMessage e)}))

      "ask"
      (try
        (let [card (board/ask! b card_id (or question "") :agent "human-ui")]
          {:success true :card card})
        (catch Exception e
          {:success false :message (.getMessage e)}))

      "answer"
      (try
        (let [card (board/answer! b card_id (or answer "") :agent "human")]
          {:success true :card card})
        (catch Exception e
          {:success false :message (.getMessage e)}))

      "note"
      (do (board/add-note! b card_id (or message "") :agent "human")
          {:success true})

      "add"
      (let [card (board/create-card! b (or title "New task")
                                     :description (or description ""))]
        {:success true :card card})

      "edit"
      (try
        (let [card (board/edit-card! b card_id
                                    :title title
                                    :description description
                                    :priority (when-let [p (get cmd :priority)] p))]
          {:success true :card card})
        (catch Exception e
          {:success false :message (.getMessage e)}))

      "heartbeat"
      (try
        (let [card (board/heartbeat! b card_id :agent "human-ui")]
          {:success true :card card})
        (catch Exception e
          {:success false :message (.getMessage e)}))

      "diff"
      {:success true :diff (board/get-diff b card_id)}

      "context"
      {:success true :context (board/get-context b card_id)}

      "gates"
      {:success true :gates (board/gates-for-card b card_id)}

      ;; unknown action
      {:success false :message (str "Unknown action: " action)})))

;; ── Static file helpers ──────────────────────────────────────

(defn- file-extension
  "Return the lowercase extension including the dot, e.g. \".js\"."
  [^String filename]
  (let [dot (.lastIndexOf filename ".")]
    (if (>= dot 0)
      (str/lower-case (subs filename dot))
      "")))

(defn- serve-file
  "Return a Ring response map for a static file, or nil if not found."
  [^Path file-path]
  (try
    (let [file      (.toFile file-path)
          ext       (file-extension (.getName file))
          ct        (get mime-types ext "application/octet-stream")
          data      (Files/readAllBytes file-path)]
      {:status  200
       :headers {"Content-Type"   ct
                 "Content-Length" (str (alength data))}
       :body    (io/input-stream data)})
    (catch Exception _
      {:status 404 :headers {} :body "Not found"})))

(defn- resolve-dist-dir
  "Find the web/dist directory relative to project-root (where bb.edn lives)."
  [project-root]
  (or (util/path-resolve project-root "kb" "web" "dist") (util/path-resolve project-root "web" "dist")))

(defn- get-html
  "Return the HTML string for the main UI page."
  [dist-dir project-root]
  (let [dist-index (util/path-resolve dist-dir "index.html")]
    (if (util/path-exists? dist-index)
      (slurp (str dist-index))
      ;; Fallback: bundled ui.html alongside this source file
      (let [resource (io/resource "kb/ui.html")
            fallback (io/file (str (util/path-resolve project-root "kb" "ui.html")))]
        (cond
          resource      (slurp resource)
          (.exists fallback) (slurp fallback)
          :else "<h1>ui.html not found</h1>")))))

;; ── Request handler ──────────────────────────────────────────

(defn- make-handler
  "Build a Ring handler function closed over the resolved dist-dir, kanban-root, and cached HTML."
  [dist-dir project-root kanban-root]
  (let [cached-html (get-html dist-dir project-root)]
    (fn [req]
    (let [uri (:uri req)]
      (cond
        ;; WebSocket upgrade
        (:websocket? req)
        (hk/as-channel req
          {:on-open
           (fn [ch]
             (add-client! ch)
             ;; Send current board state on connect
             (hk/send! ch (json/generate-string {:type "state"
                                                  :data (get-board-state kanban-root)})))

           :on-receive
           (fn [ch raw-msg]
             (try
               (let [cmd    (json/parse-string raw-msg true)
                     result (handle-ui-command cmd)]
                 ;; Send command result back to this client
                 (hk/send! ch (json/generate-string {:type "result" :data result}))
                 ;; Broadcast updated state to all connected clients
                 (broadcast! (json/generate-string {:type "state"
                                                     :data (get-board-state kanban-root)})))
               (catch Exception e
                 (hk/send! ch (json/generate-string {:type "error"
                                                      :data (.getMessage e)})))))

           :on-close
           (fn [ch _status]
             (remove-client! ch))})

        ;; REST: GET /api/state
        (= uri "/api/state")
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string (get-board-state kanban-root))}

        ;; Root / index
        (contains? #{"/" "/index.html"} uri)
        {:status  200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    cached-html}

        ;; Static files from dist/
        :else
        (if (util/path-directory? dist-dir)
          (let [;; Strip query string and leading slash to get relative path
                clean-path  (-> uri (str/split #"\?") first (str/replace #"^/" ""))
                file-path   (-> (util/path-resolve dist-dir clean-path)
                                (.normalize))
                dist-real   (-> (util/->path dist-dir) (.toAbsolutePath) (.normalize))]
            (cond
              ;; Serve asset if it exists within dist/ (path traversal guard)
              (and (.startsWith file-path dist-real)
                   (util/path-file? file-path))
              (serve-file file-path)

              ;; SPA fallback: serve cached HTML for non-API paths
              (not (str/starts-with? uri "/api/"))
              {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body cached-html}

              :else
              {:status 404 :headers {} :body "Not found"}))
          {:status 404 :headers {} :body "Not found"}))))))

;; ── File watcher ─────────────────────────────────────────────

(defn- scan-dir
  "Return a map of {path-string -> mtime-long} for all files under root.
   Uses File.lastModified() to avoid generic-type issues with Files/readAttributes."
  [^Path root]
  (try
    (let [result (atom {})]
      (Files/walkFileTree root
        (reify java.nio.file.FileVisitor
          (preVisitDirectory [_ _dir _attrs] java.nio.file.FileVisitResult/CONTINUE)
          (visitFile [_ ^Path file _attrs]
            (try
              (swap! result assoc (str file)
                     (.lastModified (.toFile file)))
              (catch Exception _))
            java.nio.file.FileVisitResult/CONTINUE)
          (visitFileFailed [_ _file _exc] java.nio.file.FileVisitResult/CONTINUE)
          (postVisitDirectory [_ _dir _exc] java.nio.file.FileVisitResult/CONTINUE)))
      @result)
    (catch Exception _ {})))

(defn- start-file-watcher!
  "Poll kanban-root every interval-ms and broadcast state on changes.
   Returns a future that can be cancelled."
  [^Path kanban-root interval-ms]
  (future
    (loop [last-snapshot (scan-dir kanban-root)]
      (Thread/sleep interval-ms)
      (let [current (scan-dir kanban-root)]
        (when (not= current last-snapshot)
          (try
            (broadcast! (json/generate-string {:type "state"
                                               :data (get-board-state (str kanban-root))}))
            (catch Exception _)))
        (recur current)))))

;; ── Public entry point ───────────────────────────────────────

(defn run-server
  "Start the HTTP/WebSocket server and file watcher.
   Options: {:host \"0.0.0.0\" :port 8741}"
  [{:keys [host port]
    :or   {host "0.0.0.0" port 8741}}]
  (let [kanban-root (try
                      (util/find-root)
                      (catch Exception _
                        (binding [*out* *err*]
                          (println "Error: No .kanban/ found. Run `kb init` first."))
                        (System/exit 1)))
        ;; project-root is the parent of .kanban/
        project-root (.getParent ^Path (util/->path kanban-root))
        dist-dir     (resolve-dist-dir project-root)
        handler      (make-handler dist-dir project-root kanban-root)
        server       (hk/run-server handler {:host host :port port})]

    (let [url (str "http://" host ":" port)]
      (println (str "kb web UI -> " url))
      (println "Ctrl+C to stop.")
      ;; Open browser (cross-platform, non-blocking)
      (future
        (try
          (Thread/sleep 500) ;; wait for server to bind
          (let [opener (condp = (System/getProperty "os.name")
                         "Mac OS X"  "open"
                         "Windows"   "cmd"
                         "Linux"     "xdg-open"
                         nil)]
            (when opener
              (let [args (if (= opener "cmd") ["cmd" "/c" "start" url] [opener url])]
                (.start (ProcessBuilder. (into-array String args))))))
          (catch Exception _))))

    ;; Start background file watcher (polls every 1 second)
    (start-file-watcher! kanban-root 1000)

    ;; Block until interrupted
    (try
      @(promise)
      (catch Exception _
        (server)
        (println "\nStopped.")))))
