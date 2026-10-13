(ns hive-elm.report
  "Promote: the output of `elm make --report=json` to a Report value, and a
   Report to the text an LLM reads. Pure.

   Wire shapes, measured against elm 0.19.1 (fixtures in test/resources/elm):

     {\"type\" \"compile-errors\"
      \"errors\" [{\"path\" abs \"name\" module
                 \"problems\" [{\"title\" s \"region\" {\"start\" {\"line\" n \"column\" n}}
                              \"message\" [chunk ...]}]}]}
     {\"type\" \"error\" \"path\" \"elm.json\" \"title\" s \"message\" [chunk ...]}

   A chunk is a string, or a styled span {\"string\" s \"color\" ... \"bold\" ...}.
   The report is written to stderr; stdout carries progress only."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def max-raw-chars
  "Cap on non-JSON compiler output kept as a problem message."
  4000)

(defn chunk-text [chunk]
  (cond (string? chunk) chunk
        (map? chunk)    (str (get chunk "string"))
        :else           ""))

(defn message-text
  "The plain text of a styled message: colours and underlines dropped."
  [chunks]
  (str/join (map chunk-text chunks)))

(defn relativize
  "`path` relative to `root` when it lies under it, else `path` unchanged."
  [root path]
  (let [prefix (when (seq root)
                 (if (str/ends-with? root "/") root (str root "/")))]
    (if (and prefix path (str/starts-with? path prefix))
      (subs path (count prefix))
      path)))

(defn- problem [root path module p]
  {:problem/title   (str (get p "title"))
   :problem/path    (relativize root path)
   :problem/module  module
   :problem/line    (get-in p ["region" "start" "line"])
   :problem/column  (get-in p ["region" "start" "column"])
   :problem/message (message-text (get p "message"))})

(defn problems-of
  "Every problem in a decoded report, in compiler order."
  [root decoded]
  (case (get decoded "type")
    "compile-errors" (vec (for [e (get decoded "errors")
                                p (get e "problems")]
                            (problem root (get e "path") (get e "name") p)))
    "error"          [(problem root (get decoded "path") nil decoded)]
    []))

(defn decode
  "The JSON object in `s`, or nil when `s` is not one."
  [s]
  ;; Not JSON is an answer here, not a fault: `report-of` keeps the raw text
  ;; as the problem, so nothing is lost by returning nil.
  (try (let [v (json/read-str (str s))] (when (map? v) v))
       (catch Exception _not-json nil)))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "\n…") s))

(defn- raw-problem
  "A failure the compiler did not report as JSON (a crash, a wrapper script's
   own error): the text is kept verbatim so nothing is lost."
  [{:keys [out err]}]
  {:problem/title   "BUILD FAILED"
   :problem/path    nil
   :problem/module  nil
   :problem/line    nil
   :problem/column  nil
   :problem/message (-> (str/join "\n" (remove str/blank? [err out]))
                        str/trim
                        (truncate max-raw-chars))})

(defn report-of
  "The Report for a finished compiler process `{:exit :out :err}`."
  [root {:keys [exit err] :as result}]
  (if (zero? exit)
    {:report/state :ok :report/problems []}
    (let [problems (some->> (decode err) (problems-of root))]
      {:report/state    :failed
       :report/problems (if (seq problems) problems [(raw-problem result)])})))

(defn failed? [report] (= :failed (:report/state report)))

(defn fingerprint
  "Identity of a report's problems: equal for the same failure seen twice."
  [report]
  (str (hash (mapv (juxt :problem/title :problem/path :problem/line
                         :problem/column :problem/message)
                   (:report/problems report)))))

(defn location
  "`src/Main.elm:11:8`, as far as the problem is located."
  [{:problem/keys [path line column]}]
  (str/join ":" (remove nil? [path line (when line column)])))

(defn headline [{:problem/keys [title] :as p}]
  (let [loc (location p)]
    (str "-- " title " --" (when (seq loc) (str " " loc)))))

(defn render-problem [p]
  (str (headline p) "\n\n" (str/trim (:problem/message p))))

(defn render
  "The report as text for a reader that cannot see the terminal. At most
   `:max-problems` problems (default 3) and `:max-chars` characters
   (default 4000); what was left out is counted, never silently dropped."
  ([report] (render report {}))
  ([report {:keys [max-problems max-chars] :or {max-problems 3 max-chars 4000}}]
   (if-not (failed? report)
     "elm build ok"
     (let [problems (:report/problems report)
           n        (count problems)
           shown    (take max-problems problems)
           more     (- n (count shown))]
       (-> (str "elm build failed: " n (if (= 1 n) " problem" " problems") "\n\n"
                (str/join "\n\n" (map render-problem shown))
                (when (pos? more) (str "\n\n… and " more " more")))
           (truncate max-chars))))))
