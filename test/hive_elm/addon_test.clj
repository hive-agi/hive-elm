(ns hive-elm.addon-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as proto]
            [hive-addon.registry.commands :as addon-cmds]
            [hive-dsl.result :as r]
            [hive-elm.addon :as addon]
            [hive-elm.addon.registry :as registry]
            [hive-elm.manifest :as manifest]
            [hive-elm.process-test-support :as support]))

(deftest the-addon-manifest-names-a-loadable-constructor
  (let [m    (edn/read-string (slurp (io/resource "META-INF/hive-addons/hive-elm.edn")))
        ctor (requiring-resolve (symbol (:addon/init-ns m) (:addon/init-fn m)))]
    (is (= addon/addon-id (:addon/id m)))
    (is (satisfies? proto/IAddon (ctor)))))

(deftest initialize-contributes-and-shutdown-retracts
  (let [a (addon/addon-ctor)]
    (try
      (let [result (proto/initialize! a {})]
        (is (:success? result))
        (is (contains? (addon-cmds/get-commands "code") "elm"))
        (is (= {:class false :fields 0} (get-in result [:metadata :sense]))
            "no sixth-sense on this classpath: registration degrades to a no-op"))
      (finally (proto/shutdown! a)))
    (is (not (contains? (addon-cmds/get-commands "code") "elm")))))

(deftest commands-normalize-and-dispatch
  (is (= "watch start" (registry/normalize-command "  Watch   START ")))
  (is (= "help" (registry/normalize-command nil)))
  (is (= "watch start" (registry/strip-subdomain-prefix "elm watch start")))
  (is (= "" (registry/strip-subdomain-prefix "elm")))
  (is (r/ok? (registry/dispatch {:command "help"})))
  (is (r/err? (registry/dispatch {:command "nope"}))))

(deftest the-subdomain-answers-in-the-host-response-shape
  (let [help (registry/dispatch-subdomain {:command "elm"})
        bad  (registry/dispatch-subdomain {:command "elm nope"})]
    (is (= "text" (:type help)))
    (is (not (:isError help)))
    (is (:isError bad))))

(deftest a-directory-without-elm-json-is-an-error-value
  (is (r/err? (registry/dispatch {:command "check" :directory (support/empty-dir!)}))))

(deftest the-manifest-defaults-and-validates
  (testing "nothing declared: defaults, and source dirs from elm.json"
    (let [m (:ok (manifest/resolve-manifest nil {"source-directories" ["src" ".elm-land/src"]}
                                            ["src/Main.elm"]))]
      (is (= ["src/Main.elm"] (:hive.elm/entries m)))
      (is (= ["src" ".elm-land/src"] (:hive.elm/source-dirs m)))
      (is (= {:poll-ms 500 :debounce-ms 300} (:hive.elm/watch m)))))
  (testing "a declared key wins, a partial :watch keeps the other default"
    (let [m (:ok (manifest/resolve-manifest {:hive.elm/entries ["src/Admin.elm"]
                                             :hive.elm/watch   {:debounce-ms 50}}
                                            {} ["src/Main.elm"]))]
      (is (= ["src/Admin.elm"] (:hive.elm/entries m)))
      (is (= ["src"] (:hive.elm/source-dirs m)))
      (is (= {:poll-ms 500 :debounce-ms 50} (:hive.elm/watch m)))))
  (testing "an unknown key or a bad value is refused"
    (is (r/err? (manifest/resolve-manifest {:hive.elm/entrys ["x"]} {} ["src/Main.elm"])))
    (is (r/err? (manifest/resolve-manifest {:hive.elm/entries []} {} ["src/Main.elm"])))))
