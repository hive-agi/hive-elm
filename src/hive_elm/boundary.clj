(ns hive-elm.boundary
  "Boundary: reads the project's files and assembles the adapters."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [hive-elm.compiler.process :as process]
            [hive-elm.manifest :as manifest]
            [hive-elm.notify.hivemind :as hivemind]
            [hive-elm.stamps.fs :as fs]))

(def entry-candidates
  "Where a project's entry module lives when it does not say: a plain
   project, then an elm-land one."
  [manifest/default-entry ".elm-land/src/Main.elm"])

(defn- default-entries [root]
  [(or (first (filter #(.isFile (io/file root %)) entry-candidates))
       manifest/default-entry)])

(defn- read-file [root name read-fn category]
  (let [f (io/file root name)]
    (try (r/ok (read-fn (slurp f)))
         (catch Exception e
           (r/err category {:file (.getPath f) :cause (.getMessage e)})))))

(defn load-manifest
  "Result of the Manifest for the project at `root`."
  [root]
  (if-not (.isFile (io/file root "elm.json"))
    (r/err :elm/no-elm-json {:root (str root)})
    (r/bind (read-file root "elm.json" json/read-str :elm/unreadable-elm-json)
            (fn [elm-json]
              (r/bind (if (.isFile (io/file root manifest/file-name))
                        (read-file root manifest/file-name edn/read-string :elm/unreadable-manifest)
                        (r/ok nil))
                      (fn [declared]
                        (manifest/resolve-manifest declared elm-json (default-entries root))))))))

(defn agent-id
  "The id a watcher shouts under: one per project root."
  [root]
  (str "elm:" (.getName (.getCanonicalFile (io/file root)))))

(defn notify-opts
  "Who the watcher of `root` shouts as, and to whom. `overrides` (from the
   caller) win over the manifest."
  [root manifest overrides]
  (merge {:agent-id (agent-id root)}
         (:hive.elm/notify manifest)
         (into {} (remove (comp nil? val)) overrides)))

(defn deps
  "The adapters a supervisor runs on, for the project at `root`."
  [root manifest notify-overrides]
  {:compiler (process/compiler (or (:hive.elm/command manifest)
                                   (process/default-command root)))
   :notifier (hivemind/notifier (notify-opts root manifest notify-overrides))
   :stamps   (fs/->FsStamps)
   :clock    (fs/->SystemClock)})
