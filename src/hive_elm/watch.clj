(ns hive-elm.watch
  "Pure decisions of the watcher: when a change is settled enough to compile,
   and whether a report is news."
  (:require [hive-elm.report :as report]))

(defn step
  "Advance the debounce state with a fresh `stamps` sample taken at `now`.
   Returns `[state compile?]`.

   A change is compiled once the same stamps have been seen for
   `debounce-ms`: an editor that writes several files in a burst triggers
   one build, not one per file. The first sample is itself a change, so a
   watcher reports the state of the build it was started on."
  [{:keys [seen pending changed-at] :as state} stamps now debounce-ms]
  (cond
    (= stamps seen)
    [(dissoc state :pending :changed-at) false]

    (not= stamps pending)
    [(assoc state :pending stamps :changed-at now) false]

    (>= (- now changed-at) debounce-ms)
    [(-> state (assoc :seen stamps) (dissoc :pending :changed-at)) true]

    :else
    [state false]))

(defn memory-of
  "What to remember of `report` for the next `decide`."
  [report]
  {:memory/state       (:report/state report)
   :memory/fingerprint (report/fingerprint report)})

(defn decide
  "Is `report` news, given the `memory` of the previous one (nil at first)?

   A failure is news when it differs from the last one. The same failure
   seen again is not: a save that fixes nothing must not repeat the message.
   A clean build is news only right after a failure."
  [memory report]
  {:decision/kind
   (cond
     (report/failed? report)
     (if (and (= :failed (:memory/state memory))
              (= (:memory/fingerprint memory) (report/fingerprint report)))
       :quiet
       :notify-failure)

     (= :failed (:memory/state memory)) :notify-recovery
     :else                              :quiet)})

(defn notice-of
  "The Notice `decision` calls for, or nil for a quiet one."
  [root report decision]
  (case (:decision/kind decision)
    :notify-failure
    (let [{:problem/keys [title path line]} (first (:report/problems report))]
      (cond-> {:notice/kind     :build-failed
               :notice/root     root
               :notice/text     (report/render report)
               :notice/problems (count (:report/problems report))}
        title (assoc :notice/title title)
        path  (assoc :notice/file path)
        line  (assoc :notice/line line)))

    :notify-recovery
    {:notice/kind     :build-recovered
     :notice/root     root
     :notice/text     "elm build recovered: compiles clean"
     :notice/problems 0}

    nil))
