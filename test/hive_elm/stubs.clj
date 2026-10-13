(ns hive-elm.stubs
  "Stub adapters for the ports. Tests inject these; nothing here touches a
   process, the filesystem or a host."
  (:require [hive-dsl.result :as r]
            [hive-elm.ports :as ports]))

;; Answers each compile with the next queued Result; the last one repeats.
;; Records the [root entries] of every call.
(defrecord ScriptedCompiler [script calls]
  ports/IElmCompiler
  (compile! [_ root entries]
    (swap! calls conj [root entries])
    (let [[head & more] @script]
      (when (seq more) (reset! script more))
      head))
  (version [_ _root] (r/ok "0.19.1")))

(defn compiler [& results]
  (->ScriptedCompiler (atom (vec results)) (atom [])))

(defrecord RecordingNotifier [notices answer]
  ports/IBuildNotifier
  (notify! [_ notice]
    (swap! notices conj notice)
    answer))

(defn notifier
  ([] (notifier (r/ok {:delivered true :channel :stub})))
  ([answer] (->RecordingNotifier (atom []) answer)))

(defrecord MutableStamps [value]
  ports/IFileStamps
  (stamps [_ _root _dirs] @value))

(defrecord MutableClock [value]
  ports/IClock
  (now-ms [_] @value))

(def ok-exec (r/ok {:exit 0 :out "Success!" :err ""}))

(defn failed-exec [err]
  (r/ok {:exit 1 :out "" :err err}))
