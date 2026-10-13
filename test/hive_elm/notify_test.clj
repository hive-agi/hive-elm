(ns hive-elm.notify-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-elm.notify.hivemind :as hivemind]
            [hive-elm.notify.sense :as sense]
            [hive-elm.ports :as ports]))

(def notice
  {:notice/kind     :build-failed
   :notice/root     "/work/app"
   :notice/text     "elm build failed: 1 problem"
   :notice/problems 1
   :notice/title    "MODULE NOT FOUND"
   :notice/file     "src/Main.elm"
   :notice/line     7})

;; A host is a resolver from symbol to fn: the seam the adapters are built on.
(defn- host [fns]
  (fn [sym] (get fns sym)))

(deftest a-notice-becomes-one-shout
  (let [shouts   (atom [])
        notifier (hivemind/notifier
                  {:agent-id "elm:app" :parent-id "coordinator:1"}
                  (host {hivemind/shout-sym (fn [& args] (swap! shouts conj (vec args)) true)}))
        result   (ports/notify! notifier notice)]
    (is (= {:delivered true :channel :hivemind} (:ok result)))
    (is (= [["elm:app" :build-failed
             {:message        "elm build failed: 1 problem"
              :directory      "/work/app"
              :parent-id      "coordinator:1"
              :build-tool     "elm"
              :build-problems 1
              :build-title    "MODULE NOT FOUND"
              :build-file     "src/Main.elm"
              :build-line     7}]]
           @shouts))))

(deftest an-unaddressed-shout-carries-no-parent
  (is (not (contains? (hivemind/shout-data {:agent-id "elm:app"} notice) :parent-id))))

(deftest without-a-host-the-notifier-answers-with-an-error
  (testing "an injected empty host"
    (is (r/err? (ports/notify! (hivemind/notifier {:agent-id "elm:app"} (host {})) notice))))
  (testing "the real resolver, on a classpath with no host"
    (is (r/err? (ports/notify! (hivemind/notifier {:agent-id "elm:app"}) notice)))))

(deftest a-shout-that-throws-is-an-error-value
  (let [notifier (hivemind/notifier
                  {:agent-id "elm:app"}
                  (host {hivemind/shout-sym (fn [& _] (throw (ex-info "backbone down" {})))}))]
    (is (r/err? (ports/notify! notifier notice)))))

(deftest the-sense-class-perceives-only-build-failures
  (is (sense/build-failed? {:agent-id "elm:app" :event-type :build-failed}))
  (is (not (sense/build-failed? {:agent-id "elm:app" :event-type :build-recovered})))
  (is (not (sense/build-failed? {:agent-id "elm:app" :event-type :error}))))

(deftest registration-is-a-no-op-without-sixth-sense
  (is (= {:class false :fields 0} (sense/register! (host {}))))
  (is (nil? (sense/unregister! (host {})))))

(deftest registration-hands-sixth-sense-the-class-and-its-fields
  (let [classes (atom [])
        fields  (atom [])
        result  (sense/register!
                 (host {sense/register-class-sym (fn [spec] (swap! classes conj spec) (:sense/class spec))
                        sense/register-field-sym (fn [spec] (swap! fields conj spec) (:detail/key spec))
                        sense/data-at-sym        (fn [k] (fn [shout] (get-in shout [:data k])))}))]
    (is (= {:class true :fields 5} result))
    (is (= [:sense/build-failed] (mapv :sense/class @classes)))
    (is (= #{:build-tool :build-title :build-file :build-line :build-problems}
           (set (map :detail/key @fields))))
    (testing "each field reads its key out of the shout's data"
      (let [read-file (:read (first (filter #(= :build-file (:detail/key %)) @fields)))]
        (is (= "src/Main.elm" (read-file {:data {:build-file "src/Main.elm"}})))))))
