(ns hive-elm.process-test-support
  "Scratch directories for tests that need a real project root."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn empty-dir!
  "A fresh empty directory, deleted when the JVM exits. Returns its path."
  []
  (let [dir (.toFile (Files/createTempDirectory "hive-elm-test" (make-array FileAttribute 0)))]
    (.deleteOnExit dir)
    (.getPath dir)))

(defn write!
  "Write `content` to `relative` under `root`, creating parent directories."
  [root relative content]
  (let [f (io/file root relative)]
    (io/make-parents f)
    (spit f content)
    (.getPath f)))
