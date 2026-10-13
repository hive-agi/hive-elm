(ns hive-elm.notify.hivemind
  "IBuildNotifier that shouts on the host's hivemind.

   The host is a runtime, not a dependency: its shout function is resolved
   when a notice is delivered, never required. Outside a host the notifier
   answers with an err Result and the watcher carries on.

   A shout reaches the model two ways. It is piggybacked on the next tool
   result of the reader it is addressed to, and sixth-sense perceives it as
   `:sense/build-failed` (see hive-elm.notify.sense) and pushes it to an idle
   session. Both address by `:parent-id`: without one the shout goes to
   coordinator readers only."
  (:require [hive-dsl.result :as r]
            [hive-elm.ports :as ports]))

(def shout-sym 'hive-mcp.hivemind.messaging/shout!)

(defn soft-resolve
  "The var named by `sym`, or nil when its namespace cannot be loaded."
  [sym]
  ;; `requiring-resolve` throws when the namespace is absent, which is the
  ;; standalone case and not an error: callers branch on nil.
  (try (requiring-resolve sym) (catch Throwable _host-absent nil)))

(defn shout-data
  "The data map of the shout for `notice`. Keys the host does not own are
   carried to readers under the shout's :data; hive-elm.notify.sense
   whitelists the build-* ones into a sense's detail."
  [{:keys [parent-id project-id]} {:notice/keys [root text problems title file line]}]
  (cond-> {:message        text
           :directory      root
           :build-tool     "elm"
           :build-problems problems}
    parent-id  (assoc :parent-id parent-id)
    project-id (assoc :project-id project-id)
    title      (assoc :build-title title)
    file       (assoc :build-file file)
    line       (assoc :build-line line)))

(defrecord HivemindNotifier [opts resolve-fn]
  ports/IBuildNotifier
  (notify! [_ notice]
    (if-let [shout! (resolve-fn shout-sym)]
      (try
        (r/ok {:delivered (boolean (shout! (:agent-id opts)
                                           (:notice/kind notice)
                                           (shout-data opts notice)))
               :channel   :hivemind})
        (catch Throwable t
          (r/err :elm/shout-threw {:cause (.getMessage t)})))
      (r/err :elm/no-host {:wanted shout-sym}))))

(defn notifier
  "`opts`: {:agent-id s :parent-id s? :project-id s?}."
  ([opts] (notifier opts soft-resolve))
  ([opts resolve-fn] (->HivemindNotifier opts resolve-fn)))

(defrecord NoopNotifier []
  ports/IBuildNotifier
  (notify! [_ _] (r/ok {:delivered false :channel :none})))
