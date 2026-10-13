(ns hive-elm.addon.registry
  "The `elm` subdomain of the host's `code` tool: command table, dispatch,
   and the shape the host is handed."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [hive-addon.cli.response :as response]
            [hive-dsl.result :as r]
            [hive-elm.addon.handlers :as h]))

(def subdomain "elm")

(def subcommands
  "command string -> {:fn handler :doc str :params [...]}"
  {"check"        {:fn h/check
                   :doc "Compile once (`elm make --report=json`) and return the problems"
                   :params []}
   "watch start"  {:fn h/watch-start
                   :doc "Watch the sources; shout each new build failure and each recovery on the hivemind"
                   :params [:parent :project :agent]}
   "watch stop"   {:fn h/watch-stop
                   :doc "Stop the watcher of this project"
                   :params []}
   "watch status" {:fn h/watch-status
                   :doc "State of this project's watcher and its recent checks"
                   :params []}
   "doctor"       {:fn h/doctor
                   :doc "Which elm would run, whether it runs, and where a failure would be delivered"
                   :params []}})

(defn- words [s]
  (enumeration-seq (java.util.StringTokenizer. (str s))))

(defn normalize-command [command]
  (let [ws (words (str/lower-case (str/trim (str command))))]
    (if (seq ws) (str/join " " ws) "help")))

(defn strip-subdomain-prefix
  "`elm watch start` -> `watch start`; a bare `elm` -> `help`."
  [command]
  (let [ws (words command)]
    (if (= subdomain (some-> (first ws) str/lower-case))
      (str/join " " (rest ws))
      (str command))))

(defn help [_params]
  (r/ok {:subdomain subdomain
         :commands  (into (sorted-map)
                          (map (fn [[k {:keys [doc params]}]] [k {:doc doc :params params}]))
                          subcommands)}))

(defn dispatch [{:keys [command] :as params}]
  (let [c (normalize-command command)]
    (if (= "help" c)
      (help params)
      (if-let [{handler :fn} (get subcommands c)]
        (try (handler params)
             (catch Exception e
               (r/err :elm/handler-threw {:command c :cause (.getMessage e)})))
        (r/err :elm/unknown-command {:command c :known (vec (sort (keys subcommands)))})))))

(defn ->response [result]
  (if (r/err? result)
    (response/error (pr-str result))
    (response/text (with-out-str (pp/pprint (:ok result))))))

(defn dispatch-subdomain [params]
  (->response (dispatch (update params :command strip-subdomain-prefix))))

(def code-contributions
  {subdomain {:handler     dispatch-subdomain
              :description (str "Elm build watching: "
                                (str/join ", " (sort (keys subcommands)))
                                ". A watcher shouts each new compiler failure on the hivemind.")}})

(def schema-ext
  "Params this subdomain adds to the host tool's schema. `directory` and
   `command` are the host's own."
  {"parent"  {:type "string"
              :description "[elm watch start] Agent id a failure is addressed to (your own id to hear it). Without it only coordinator readers do."}
   "project" {:type "string"
              :description "[elm watch start] Project id to shout under. Default: resolved from the directory."}
   "agent"   {:type "string"
              :description "[elm watch start] Agent id the watcher shouts as. Default: elm:<directory name>."}})
