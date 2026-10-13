(ns hive-elm.manifest
  "The project's watch configuration as a value. Pure.

   A project needs no `hive-elm.edn`: everything has a default, and the
   source directories come from the project's own elm.json."
  (:require [hive-dsl.result :as r]
            [hive-elm.schema :as schema]))

(def file-name "hive-elm.edn")

(def default-entry "src/Main.elm")

(def defaults
  {:hive.elm/watch  {:poll-ms 500 :debounce-ms 300}
   :hive.elm/notify {}})

(defn source-dirs
  "The directories elm.json names, or `[\"src\"]` (a package has none)."
  [elm-json]
  (let [dirs (get elm-json "source-directories")]
    (if (and (sequential? dirs) (seq dirs)) (vec dirs) ["src"])))

(defn resolve-manifest
  "The Manifest for `declared` (the contents of hive-elm.edn, or nil) and the
   decoded `elm-json`. `entries` is the fallback entry list for a project that
   declares none. Result."
  [declared elm-json entries]
  (let [declared (or declared {})
        manifest (-> (merge defaults declared)
                     (update :hive.elm/watch #(merge (:hive.elm/watch defaults) %))
                     (update :hive.elm/entries #(or % entries))
                     (assoc :hive.elm/source-dirs (source-dirs elm-json)))]
    (if (schema/valid-manifest? manifest)
      (r/ok manifest)
      (r/err :elm/invalid-manifest {:file    file-name
                                    :explain (pr-str (:errors (schema/explain-manifest manifest)))}))))
