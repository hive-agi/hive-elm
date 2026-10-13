(ns hive-elm.live-test
  "The subject here is the real compiler: these run a real `elm` over a
   scratch copy of test/resources/elm/project. Run with `clojure -M:integration`.
   They fail, not skip, on a machine without elm."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-elm.boundary :as boundary]
            [hive-elm.process-test-support :as support]
            [hive-elm.stubs :as stubs]
            [hive-elm.watch.supervisor :as supervisor]
            [clojure.string :as str]
            [hive-elm.compiler.process :as process]
            [hive-elm.ports :as ports]))

(def good-main (slurp (io/resource "elm/project/src/Main.elm")))

(def bad-main
  (str "module Main exposing (main)\n\n"
       "import Html exposing (Html, text)\n"
       "import Theme.Tokens as Tokens\n\n\n"
       "main : Html msg\nmain =\n    text \"hive-elm\"\n"))

(defn- project!
  "A scratch project whose elm.json asks for the elm that is installed: the
   compiler refuses any other version outright (ELM VERSION MISMATCH)."
  []
  (let [root    (support/empty-dir!)
        version (:ok (ports/version (process/compiler (process/default-command root)) root))]
    (support/write! root "elm.json"
                    (str/replace (slurp (io/resource "elm/project/elm.json"))
                                 "\"elm-version\": \"0.19.1\""
                                 (str "\"elm-version\": \"" version "\"")))
    (support/write! root "src/Main.elm" good-main)
    root))

(defn- live-supervisor [root notifier]
  (let [manifest (:ok (boundary/load-manifest root))]
    (supervisor/supervisor root manifest
                           (assoc (boundary/deps root manifest {}) :notifier notifier))))

(deftest ^:integration a-real-build-breaks-and-recovers
  (let [root     (project!)
        notifier (stubs/notifier)
        sup      (live-supervisor root notifier)]
    (testing "a project that compiles tells nobody"
      (let [outcome (supervisor/check! sup)]
        (is (r/ok? outcome) (pr-str outcome))
        (is (= :ok (get-in outcome [:ok :report :report/state]))
            (pr-str (get-in outcome [:ok :report])))
        (is (empty? @(:notices notifier)))))
    (testing "a missing import is told once, located in the source"
      (support/write! root "src/Main.elm" bad-main)
      (supervisor/check! sup)
      (supervisor/check! sup)
      (let [[notice & more] @(:notices notifier)]
        (is (empty? more))
        (is (= :build-failed (:notice/kind notice)))
        (is (= "MODULE NOT FOUND" (:notice/title notice)))
        (is (= "src/Main.elm" (:notice/file notice)))
        (is (= 4 (:notice/line notice)))))
    (testing "the fix is told as a recovery"
      (support/write! root "src/Main.elm" good-main)
      (supervisor/check! sup)
      (is (= [:build-failed :build-recovered]
             (mapv :notice/kind @(:notices notifier)))))))
