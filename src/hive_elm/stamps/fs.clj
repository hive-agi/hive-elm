(ns hive-elm.stamps.fs
  "IFileStamps over the filesystem, plus the system clock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-elm.ports :as ports])
  (:import [java.io File]))

(defn- elm-file? [^File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".elm")))

(defrecord FsStamps []
  ports/IFileStamps
  (stamps [_ root dirs]
    (let [sources (for [dir dirs
                        ^File f (file-seq (io/file root dir))
                        :when (elm-file? f)]
                    f)
          ;; elm.json decides dependencies and source directories, so a
          ;; change to it can break or fix the build just as a source can.
          manifest (io/file root "elm.json")]
      (into {}
            (map (fn [^File f] [(.getPath f) (.lastModified f)]))
            (cond-> sources (.isFile manifest) (conj manifest))))))

(defrecord SystemClock []
  ports/IClock
  (now-ms [_] (System/currentTimeMillis)))
