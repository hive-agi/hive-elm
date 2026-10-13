(ns hive-elm.addon.handlers
  "Command handlers. Each takes the parsed params map and returns a Result.
   MCP delivers every param as a string."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-elm.boundary :as boundary]
            [hive-elm.notify.hivemind :as hivemind]
            [hive-elm.notify.sense :as sense]
            [hive-elm.ports :as ports]
            [hive-elm.report :as report]
            [hive-elm.watch.supervisor :as supervisor]))

(defonce ^:private watchers (atom {}))

(defn root-of [{:keys [directory root]}]
  (or root directory (System/getProperty "user.dir")))

(defn- blank->nil [s]
  (when-not (str/blank? (str s)) (str s)))

(defn- notify-overrides
  "Who the caller wants told. `parent` is the reader a failure is addressed
   to: without it only coordinator readers hear of one."
  [{:keys [parent project agent]}]
  {:parent-id  (blank->nil parent)
   :project-id (blank->nil project)
   :agent-id   (blank->nil agent)})

(defn- with-session
  "Call `f` with {:root :manifest :deps} of the project the params name."
  [params f]
  (let [root (root-of params)]
    (r/bind (boundary/load-manifest root)
            (fn [manifest]
              (f {:root     root
                  :manifest manifest
                  :deps     (boundary/deps root manifest (notify-overrides params))})))))

(defn- summary
  "A check's outcome as the caller reads it."
  [root {:keys [report decision delivery]}]
  (cond-> {:root     root
           :state    (:report/state report)
           :problems (:report/problems report)
           :text     (report/render report {:max-problems 20 :max-chars 20000})}
    decision (assoc :decision (:decision/kind decision))
    delivery (assoc :delivery delivery)))

(defn check
  "Compile once and return the report. Tells nobody: the caller asked, and
   reads the answer in the response."
  [params]
  (with-session params
    (fn [{:keys [root manifest deps]}]
      (r/bind (supervisor/check!
               (supervisor/supervisor root manifest
                                      (assoc deps :notifier (hivemind/->NoopNotifier))))
              (fn [outcome] (r/ok (summary root (dissoc outcome :decision :delivery))))))))

(defn watch-start [params]
  (with-session params
    (fn [{:keys [root manifest deps]}]
      (if (get @watchers root)
        (r/ok {:already-running true :root root})
        (let [sup (supervisor/start! (supervisor/supervisor root manifest deps))]
          (swap! watchers assoc root sup)
          (r/ok {:started true
                 :root    root
                 :entries (:hive.elm/entries manifest)
                 :notify  (:opts (:notifier deps))}))))))

(defn watch-stop [params]
  (let [root (root-of params)]
    (if-let [sup (get @watchers root)]
      (do (supervisor/stop! sup)
          (swap! watchers dissoc root)
          (r/ok {:stopped true :root root}))
      (r/ok {:stopped false :root root :reason "no watcher for this root"}))))

(defn watch-status [params]
  (let [root (root-of params)]
    (if-let [sup (get @watchers root)]
      (r/ok (supervisor/status sup))
      (r/ok {:root root :running? false :watching (vec (keys @watchers))}))))

(defn running-watchers [] (vec (keys @watchers)))

(defn stop-all! []
  (doseq [[root sup] @watchers]
    (supervisor/stop! sup)
    (swap! watchers dissoc root))
  nil)

(defn- plain
  "A Result as data that prints: its value, or its error."
  [result]
  (if (r/err? result) {:error result} (:ok result)))

(defn doctor
  "What this machine and host can do for the project: which elm would run,
   whether it runs, and whether a failure has anywhere to go."
  [params]
  (with-session params
    (fn [{:keys [root manifest deps]}]
      (r/ok {:root        root
             :entries     (:hive.elm/entries manifest)
             :source-dirs (:hive.elm/source-dirs manifest)
             :command     (:command (:compiler deps))
             :elm-version (plain (ports/version (:compiler deps) root))
             :hivemind    (if (hivemind/soft-resolve hivemind/shout-sym) :present :absent)
             :sixth-sense (if (hivemind/soft-resolve sense/register-class-sym) :present :absent)
             :notify      (:opts (:notifier deps))}))))
