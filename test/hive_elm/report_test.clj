(ns hive-elm.report-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-elm.report :as report]
            [hive-elm.schema :as schema]))

;; Fixtures are stderr of a real `elm make --report=json` (elm 0.19.1), with
;; the project root rewritten to /work/app.
(defn- fixture [name]
  (slurp (io/resource (str "elm/" name ".json"))))

(defn- failed [name]
  (report/report-of "/work/app" {:exit 1 :out "" :err (fixture name)}))

(deftest a-clean-exit-is-an-ok-report
  (is (= {:report/state :ok :report/problems []}
         (report/report-of "/work/app" {:exit 0 :out "Success!" :err ""}))))

(deftest a-missing-module-is-one-located-problem
  (let [report (failed "module-not-found")
        [p]    (:report/problems report)]
    (is (schema/valid-report? report))
    (is (= 1 (count (:report/problems report))))
    (is (= "MODULE NOT FOUND" (:problem/title p)))
    (is (= "src/Main.elm" (:problem/path p)) "the path is relative to the root")
    (is (= "Main" (:problem/module p)))
    (is (= [11 8] [(:problem/line p) (:problem/column p)]))
    (testing "styled spans are flattened into the text, in order"
      (is (str/includes? (:problem/message p) "import Theme.Tokens as Tokens"))
      (is (str/includes? (:problem/message p) "^^^^^^^^^^^^"))
      (is (str/includes? (:problem/message p) "Hint: If it is not a typo")))))

(deftest a-type-error-keeps-its-region
  (let [[p] (:report/problems (failed "type-mismatch"))]
    (is (= "TYPE MISMATCH" (:problem/title p)))
    (is (= 98 (:problem/line p)))
    (is (str/includes? (:problem/message p) "(++)"))))

(deftest a-broken-elm-json-is-a-problem-without-a-region
  (let [report (failed "bad-elm-json")
        [p]    (:report/problems report)]
    (is (schema/valid-report? report))
    (is (= "UNFINISHED OBJECT" (:problem/title p)))
    (is (= "elm.json" (:problem/path p)))
    (is (nil? (:problem/line p)))
    (is (= "-- UNFINISHED OBJECT -- elm.json" (report/headline p)))))

(deftest output-that-is-not-json-is-kept-verbatim
  (let [report (report/report-of "/work/app" {:exit 127 :out "" :err "sh: elm: not found\n"})
        [p]    (:report/problems report)]
    (is (schema/valid-report? report))
    (is (= :failed (:report/state report)))
    (is (= "BUILD FAILED" (:problem/title p)))
    (is (= "sh: elm: not found" (:problem/message p)))))

(deftest a-failure-with-no-output-is-still-a-failure
  (let [report (report/report-of "/work/app" {:exit 1 :out "" :err ""})]
    (is (schema/valid-report? report))
    (is (report/failed? report))
    (is (= 1 (count (:report/problems report))))))

(deftest relativize-only-strips-a-true-prefix
  (is (= "src/A.elm" (report/relativize "/work/app" "/work/app/src/A.elm")))
  (is (= "src/A.elm" (report/relativize "/work/app/" "/work/app/src/A.elm")))
  (is (= "/work/app2/src/A.elm" (report/relativize "/work/app" "/work/app2/src/A.elm")))
  (is (= "elm.json" (report/relativize "/work/app" "elm.json")))
  (is (nil? (report/relativize "/work/app" nil))))

(deftest the-fingerprint-tells-failures-apart
  (is (= (report/fingerprint (failed "module-not-found"))
         (report/fingerprint (failed "module-not-found"))))
  (is (not= (report/fingerprint (failed "module-not-found"))
            (report/fingerprint (failed "type-mismatch")))))

(deftest render-reads-like-the-compiler
  (let [text (report/render (failed "module-not-found"))]
    (is (str/starts-with? text "elm build failed: 1 problem\n\n-- MODULE NOT FOUND -- src/Main.elm:11:8"))
    (is (str/includes? text "You are trying to import a `Theme.Tokens` module:"))))

(deftest render-counts-what-it-leaves-out
  (let [p      (first (:report/problems (failed "type-mismatch")))
        report {:report/state :failed :report/problems (vec (repeat 5 p))}
        text   (report/render report {:max-problems 2})]
    (is (str/starts-with? text "elm build failed: 5 problems"))
    (is (str/ends-with? text "… and 3 more"))
    (is (<= (count (report/render report {:max-chars 100})) 102))))
