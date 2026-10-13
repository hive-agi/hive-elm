(ns hive-elm.watch.supervisor
  "Pipeline: sample the sources, compile when a change settles, tell the
   notifier when the report is news. All effects go through the ports in
   `deps` ({:compiler :notifier :stamps :clock})."
  (:require [hive-dsl.result :as r]
            [hive-elm.ports :as ports]
            [hive-elm.report :as report]
            [hive-elm.watch :as watch]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(def max-log 50)

(defrecord Supervisor [root manifest deps state-ref])

(defn supervisor [root manifest deps]
  (->Supervisor root manifest deps (atom {:watch {} :memory nil :log []})))

(defn- record! [state-ref entry]
  (swap! state-ref update :log #(vec (take-last max-log (conj % entry)))))

(defn- delivery-of
  "A notifier's Result as plain data for the log."
  [result]
  (if (r/err? result)
    {:delivered false :error (dissoc result :ok)}
    (:ok result)))

(defn check!
  "Compile once and act on the report. Result of
   {:report :decision :delivery}; an err Result when the compiler could not
   be run (recorded, never notified: a missing binary is a setup fault of
   this machine, not news about the code)."
  [{:keys [root manifest deps state-ref]}]
  (let [at     (ports/now-ms (:clock deps))
        result (ports/compile! (:compiler deps) root (:hive.elm/entries manifest))]
    (if (r/err? result)
      (do (record! state-ref {:at at :error result})
          result)
      (let [report   (report/report-of root (:ok result))
            decision (watch/decide (:memory @state-ref) report)
            notice   (watch/notice-of root report decision)
            delivery (when notice
                       (delivery-of (ports/notify! (:notifier deps) notice)))]
        (swap! state-ref assoc :memory (watch/memory-of report) :report report)
        (record! state-ref (cond-> {:at       at
                                    :state    (:report/state report)
                                    :problems (count (:report/problems report))
                                    :decision (:decision/kind decision)}
                             delivery (assoc :delivery delivery)))
        (r/ok {:report report :decision decision :delivery delivery})))))

(defn tick!
  "One sample of the sources. Compiles when a change has settled; returns
   what `check!` returned, or nil when there was nothing to do."
  [{:keys [root manifest deps state-ref] :as sup}]
  (let [stamps   (ports/stamps (:stamps deps) root (:hive.elm/source-dirs manifest))
        now      (ports/now-ms (:clock deps))
        debounce (get-in manifest [:hive.elm/watch :debounce-ms])
        [w compile?] (watch/step (:watch @state-ref) stamps now debounce)]
    (swap! state-ref assoc :watch w)
    (when compile? (check! sup))))

(defn- daemon-factory ^ThreadFactory [root]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable (str "hive-elm-watch " root))
        (.setDaemon true)))))

(defn start!
  "Begin sampling on a daemon thread. Idempotent. Returns the supervisor."
  [{:keys [root manifest state-ref] :as sup}]
  (when-not (:executor @state-ref)
    (let [period   (get-in manifest [:hive.elm/watch :poll-ms])
          executor (Executors/newSingleThreadScheduledExecutor (daemon-factory root))]
      (swap! state-ref assoc :executor executor)
      (.scheduleWithFixedDelay
       executor
       ;; A throwing task is never rescheduled by the executor, so one bad
       ;; tick would silently end the watch: log it and keep sampling.
       (fn [] (try (tick! sup)
                   (catch Throwable t
                     (log/warn t "hive-elm: watch tick failed" {:root root}))))
       0 period TimeUnit/MILLISECONDS)))
  sup)

(defn stop!
  "Stop sampling. Idempotent."
  [{:keys [state-ref]}]
  (when-let [^ScheduledExecutorService executor (:executor @state-ref)]
    (.shutdownNow executor)
    (swap! state-ref dissoc :executor))
  nil)

(defn status [{:keys [root manifest state-ref]}]
  (let [{:keys [executor memory report log]} @state-ref]
    {:root     root
     :running? (boolean executor)
     :entries  (:hive.elm/entries manifest)
     :state    (or (:memory/state memory) :unknown)
     :problems (count (:report/problems report))
     :log      log}))
