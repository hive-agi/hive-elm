(ns hive-elm.watch-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [hive-elm.schema :as schema]
            [hive-elm.watch :as watch]
            [malli.generator :as mg]))

(def ok-report {:report/state :ok :report/problems []})

(defn- failure [title]
  {:report/state    :failed
   :report/problems [{:problem/title   title
                      :problem/path    "src/Main.elm"
                      :problem/module  "Main"
                      :problem/line    7
                      :problem/column  8
                      :problem/message "boom"}]})

(defn- kind [memory report]
  (:decision/kind (watch/decide memory report)))

(deftest a-first-failure-is-news
  (is (= :notify-failure (kind nil (failure "A")))))

(deftest the-same-failure-again-is-quiet
  (let [report (failure "A")]
    (is (= :quiet (kind (watch/memory-of report) report)))))

(deftest a-different-failure-is-news
  (is (= :notify-failure (kind (watch/memory-of (failure "A")) (failure "B")))))

(deftest a-clean-build-is-news-only-after-a-failure
  (is (= :notify-recovery (kind (watch/memory-of (failure "A")) ok-report)))
  (is (= :quiet (kind (watch/memory-of ok-report) ok-report)))
  (is (= :quiet (kind nil ok-report))))

(deftest the-same-failure-after-a-recovery-is-news-again
  (is (= :notify-failure (kind (watch/memory-of ok-report) (failure "A")))))

(defspec a-report-is-never-news-twice-in-a-row 100
  (prop/for-all [report (mg/generator schema/Report)]
    (= :quiet (kind (watch/memory-of report) report))))

(defspec every-notice-is-a-valid-notice 100
  (prop/for-all [before (mg/generator schema/Report)
                 report (mg/generator schema/Report)]
    (let [notice (watch/notice-of "/work/app" report
                                  (watch/decide (watch/memory-of before) report))]
      (or (nil? notice) (schema/valid-notice? notice)))))

(deftest a-failure-notice-names-the-first-problem
  (let [report (failure "MODULE NOT FOUND")
        notice (watch/notice-of "/work/app" report (watch/decide nil report))]
    (is (= {:notice/kind     :build-failed
            :notice/root     "/work/app"
            :notice/problems 1
            :notice/title    "MODULE NOT FOUND"
            :notice/file     "src/Main.elm"
            :notice/line     7}
           (dissoc notice :notice/text)))
    (is (nil? (watch/notice-of "/work/app" report {:decision/kind :quiet})))))

(deftest step-compiles-once-a-change-has-settled
  (let [a {"src/Main.elm" 1}
        b {"src/Main.elm" 2}
        c {"src/Main.elm" 3}]
    (testing "the first sample is a change, compiled after the debounce"
      (let [[s1 go1] (watch/step {} a 0 300)
            [s2 go2] (watch/step s1 a 100 300)
            [s3 go3] (watch/step s2 a 300 300)]
        (is (= [false false true] [go1 go2 go3]))
        (is (= {:seen a} s3))
        (testing "and an unchanged tree stays quiet"
          (is (= [s3 false] (watch/step s3 a 9999 300))))
        (testing "a burst of writes restarts the wait and compiles once"
          (let [[s4 go4] (watch/step s3 b 1000 300)
                [s5 go5] (watch/step s4 c 1200 300)
                [s6 go6] (watch/step s5 c 1400 300)
                [s7 go7] (watch/step s6 c 1500 300)]
            (is (= [false false false true] [go4 go5 go6 go7]))
            (is (= {:seen c} s7))))
        (testing "a change that is undone before it settles compiles nothing"
          (let [[s4 _]   (watch/step s3 b 1000 300)
                [s5 go5] (watch/step s4 a 1100 300)]
            (is (false? go5))
            (is (= {:seen a} s5))))))))
