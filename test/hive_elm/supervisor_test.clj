(ns hive-elm.supervisor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-elm.stubs :as stubs]
            [hive-elm.watch.supervisor :as supervisor]))

(def manifest
  {:hive.elm/entries     ["src/Main.elm"]
   :hive.elm/source-dirs ["src"]
   :hive.elm/watch       {:poll-ms 500 :debounce-ms 300}
   :hive.elm/notify      {}})

(def module-not-found
  (stubs/failed-exec (slurp (io/resource "elm/module-not-found.json"))))

(def type-mismatch
  (stubs/failed-exec (slurp (io/resource "elm/type-mismatch.json"))))

(defn- system [& compiles]
  (let [compiler (apply stubs/compiler compiles)
        notifier (stubs/notifier)
        stamps   (atom {"src/Main.elm" 1})
        clock    (atom 0)]
    {:compiler compiler
     :notifier notifier
     :stamps   stamps
     :clock    clock
     :sup      (supervisor/supervisor "/work/app" manifest
                                      {:compiler compiler
                                       :notifier notifier
                                       :stamps   (stubs/->MutableStamps stamps)
                                       :clock    (stubs/->MutableClock clock)})}))

(defn- kinds [{:keys [notifier]}]
  (mapv :notice/kind @(:notices notifier)))

(deftest a-failing-build-is-told-once
  (let [{:keys [sup] :as sys} (system module-not-found)]
    (supervisor/check! sup)
    (supervisor/check! sup)
    (is (= [:build-failed] (kinds sys)))
    (let [[notice] @(:notices (:notifier sys))]
      (is (= "MODULE NOT FOUND" (:notice/title notice)))
      (is (= "src/Main.elm" (:notice/file notice)))
      (is (= 11 (:notice/line notice))))))

(deftest a-new-failure-and-the-recovery-are-each-told
  (let [{:keys [sup] :as sys} (system module-not-found type-mismatch stubs/ok-exec)]
    (dotimes [_ 4] (supervisor/check! sup))
    (is (= [:build-failed :build-failed :build-recovered] (kinds sys)))))

(deftest a-clean-build-tells-nobody
  (let [{:keys [sup] :as sys} (system stubs/ok-exec)
        outcome (supervisor/check! sup)]
    (is (r/ok? outcome))
    (is (= [] (kinds sys)))
    (is (= :ok (:state (supervisor/status sup))))))

(deftest a-compiler-that-cannot-run-is-recorded-not-told
  (let [{:keys [sup] :as sys} (system (r/err :elm/exec-failed {:cause "no such file"}))
        outcome (supervisor/check! sup)]
    (is (r/err? outcome))
    (is (= [] (kinds sys)))
    (is (some? (:error (last (:log (supervisor/status sup))))))
    (is (= :unknown (:state (supervisor/status sup))))))

(deftest a-notifier-that-fails-does-not-stop-the-watch
  (let [compiler (stubs/compiler module-not-found)
        notifier (stubs/notifier (r/err :elm/no-host {}))
        sup      (supervisor/supervisor "/work/app" manifest
                                        {:compiler compiler
                                         :notifier notifier
                                         :stamps   (stubs/->MutableStamps (atom {}))
                                         :clock    (stubs/->MutableClock (atom 0))})
        outcome  (supervisor/check! sup)]
    (is (r/ok? outcome))
    (is (false? (get-in outcome [:ok :delivery :delivered])))
    (is (= :failed (:state (supervisor/status sup))))))

(deftest ticks-compile-only-settled-changes
  (let [{:keys [sup compiler stamps clock] :as sys} (system module-not-found stubs/ok-exec)
        compiles #(count @(:calls compiler))]
    (testing "the build the watcher starts on is checked after the debounce"
      (supervisor/tick! sup)
      (is (= 0 (compiles)))
      (reset! clock 300)
      (supervisor/tick! sup)
      (is (= 1 (compiles)))
      (is (= [["/work/app" ["src/Main.elm"]]] @(:calls compiler)))
      (is (= [:build-failed] (kinds sys))))
    (testing "idle ticks compile nothing"
      (reset! clock 5000)
      (supervisor/tick! sup)
      (is (= 1 (compiles))))
    (testing "a fix is compiled once it settles, and told as a recovery"
      (swap! stamps assoc "src/Main.elm" 2)
      (supervisor/tick! sup)
      (reset! clock 5300)
      (supervisor/tick! sup)
      (is (= 2 (compiles)))
      (is (= [:build-failed :build-recovered] (kinds sys))))))

(deftest start-and-stop-are-idempotent
  (let [{:keys [sup]} (system stubs/ok-exec)]
    (supervisor/start! sup)
    (supervisor/start! sup)
    (is (:running? (supervisor/status sup)))
    (supervisor/stop! sup)
    (supervisor/stop! sup)
    (is (not (:running? (supervisor/status sup))))))
