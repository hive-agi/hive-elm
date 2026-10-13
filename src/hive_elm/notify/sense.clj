(ns hive-elm.notify.sense
  "Registers `:sense/build-failed` with sixth-sense, when it is loaded.

   sixth-sense turns a shout into a sense only when a registered class
   perceives it; a `:build-failed` shout matches none of the built-ins. The
   registration is one map. sixth-sense belongs to hive-agent, which this
   library does not depend on, so its registry is resolved softly: without
   it the shout is still piggybacked, only never pushed."
  (:require [hive-elm.notify.hivemind :as hivemind]))

(def sense-class :sense/build-failed)

(def register-class-sym   'hive-agent.sixth-sense.classes/register-class!)
(def unregister-class-sym 'hive-agent.sixth-sense.classes/unregister-class!)
(def register-field-sym   'hive-agent.sixth-sense.detail/register-field!)
(def data-at-sym          'hive-agent.sixth-sense.detail/data-at)

(defn build-failed?
  "The predicate sixth-sense runs. It sees the canonical :event-type keyword."
  [shout]
  (= :build-failed (:event-type shout)))

(def class-spec
  {:sense/class sense-class
   :perceives?  build-failed?
   :doc         "A watched build stopped compiling. Not terminal: the watcher keeps running."})

(def detail-keys
  "Shout data keys exposed on the sense, with their wire names."
  {:build-tool     "build_tool"
   :build-title    "build_title"
   :build-file     "build_file"
   :build-line     "build_line"
   :build-problems "build_problems"})

(defn register!
  "Register the class and its detail fields. Returns
   {:class registered? :fields n}; a no-op without sixth-sense."
  ([] (register! hivemind/soft-resolve))
  ([resolve-fn]
   (let [register-class! (resolve-fn register-class-sym)
         register-field! (resolve-fn register-field-sym)
         data-at         (resolve-fn data-at-sym)]
     {:class  (boolean (when register-class! (register-class! class-spec)))
      :fields (if (and register-field! data-at)
                (count (for [[k wire] detail-keys]
                         (register-field! {:detail/key k :wire wire :read (data-at k)})))
                0)})))

(defn unregister!
  ([] (unregister! hivemind/soft-resolve))
  ([resolve-fn]
   (when-let [unregister-class! (resolve-fn unregister-class-sym)]
     (unregister-class! sense-class))
   nil))
