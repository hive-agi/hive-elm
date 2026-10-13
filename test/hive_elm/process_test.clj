(ns hive-elm.process-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-elm.boundary :as boundary]
            [hive-elm.compiler.process :as process]
            [hive-elm.ports :as ports]
            [hive-elm.process-test-support :as support]
            [hive-elm.report :as report]
            [hive-elm.stamps.fs :as fs]))

(deftest make-argv-type-checks-without-writing-an-artifact
  (is (= ["npx" "elm" "make" "src/Main.elm" "src/Admin.elm" "--report=json" "--output=/dev/null"]
         (process/make-argv ["npx" "elm"] ["src/Main.elm" "src/Admin.elm"]))))

(deftest the-compiler-runs-the-argv-it-was-built-with
  (let [calls    (atom [])
        compiler (process/compiler ["elm"] (fn [argv cwd]
                                             (swap! calls conj [argv cwd])
                                             (r/ok {:exit 0 :out "0.19.1\n" :err ""})))]
    (is (r/ok? (ports/compile! compiler "/work/app" ["src/Main.elm"])))
    (is (= "0.19.1" (:ok (ports/version compiler "/work/app"))))
    (is (= [[["elm" "make" "src/Main.elm" "--report=json" "--output=/dev/null"] "/work/app"]
            [["elm" "--version"] "/work/app"]]
           @calls))))

(deftest a-binary-that-does-not-exist-is-an-error-value
  (is (r/err? (process/exec! ["hive-elm-no-such-binary"] (support/empty-dir!)))))

(deftest exec-keeps-both-streams-and-the-exit-code
  (let [result (:ok (process/exec! ["sh" "-c" "echo out; echo err >&2; exit 3"] (support/empty-dir!)))]
    (is (= {:exit 3 :out "out\n" :err "err\n"} result))))

(deftest stamps-cover-elm-sources-and-elm-json
  (let [root (support/empty-dir!)]
    (support/write! root "elm.json" "{}")
    (support/write! root "src/Main.elm" "module Main exposing (..)")
    (support/write! root "src/Page/Home.elm" "module Page.Home exposing (..)")
    (support/write! root "src/notes.txt" "not a source")
    (let [stamps (ports/stamps (fs/->FsStamps) root ["src" "missing-dir"])
          names  (set (map #(report/relativize root %) (keys stamps)))]
      (is (= #{"elm.json" "src/Main.elm" "src/Page/Home.elm"} names)))))

(deftest a-project-is-described-by-its-elm-json
  (testing "no elm.json: not an Elm project"
    (is (r/err? (boundary/load-manifest (support/empty-dir!)))))
  (testing "an elm-land project's generated entry is found without a manifest"
    (let [root (support/empty-dir!)]
      (support/write! root "elm.json" "{\"source-directories\": [\"src\", \".elm-land/src\"]}")
      (support/write! root ".elm-land/src/Main.elm" "module Main exposing (..)")
      (let [m (:ok (boundary/load-manifest root))]
        (is (= [".elm-land/src/Main.elm"] (:hive.elm/entries m)))
        (is (= ["src" ".elm-land/src"] (:hive.elm/source-dirs m))))))
  (testing "hive-elm.edn overrides"
    (let [root (support/empty-dir!)]
      (support/write! root "elm.json" "{}")
      (support/write! root "hive-elm.edn" "{:hive.elm/entries [\"src/Admin.elm\"] :hive.elm/command [\"npx\" \"elm\"]}")
      (let [m (:ok (boundary/load-manifest root))]
        (is (= ["src/Admin.elm"] (:hive.elm/entries m)))
        (is (= ["npx" "elm"] (:command (:compiler (boundary/deps root m {}))))))))
  (testing "a manifest that does not parse is an error value"
    (let [root (support/empty-dir!)]
      (support/write! root "elm.json" "{}")
      (support/write! root "hive-elm.edn" "{:hive.elm/entries")
      (is (r/err? (boundary/load-manifest root))))))

(deftest the-watcher-shouts-as-its-project-unless-told-otherwise
  (let [root (support/empty-dir!)
        opts (boundary/notify-opts root {:hive.elm/notify {:parent-id "from-manifest"}}
                                   {:parent-id "from-caller" :project-id nil})]
    (is (str/starts-with? (:agent-id opts) "elm:hive-elm-test"))
    (is (= "from-caller" (:parent-id opts)))
    (is (not (contains? opts :project-id)))))
