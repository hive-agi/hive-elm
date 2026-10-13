(ns hive-elm.compiler.process
  "IElmCompiler over a child process."
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [hive-elm.ports :as ports])
  (:import [java.util List]))

(defn exec!
  "Run `argv` in `cwd` to completion. Result of {:exit :out :err}; a non-zero
   exit is data. stderr is drained on its own thread so a large report cannot
   block the child on a full pipe."
  [argv cwd]
  (try
    (let [pb   (doto (ProcessBuilder. ^List (vec argv)) (.directory (io/file cwd)))
          proc (.start pb)
          err  (future (slurp (.getErrorStream proc)))
          out  (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      (r/ok {:exit exit :out out :err @err}))
    (catch Exception e
      (r/err :elm/exec-failed {:argv (vec argv) :cwd (str cwd) :cause (.getMessage e)}))))

(defn make-argv
  "`elm make` for `entries`, type-checking only: the report goes to stderr as
   JSON and no artifact is written, so a watcher never races the project's
   own bundler over an output file."
  [command entries]
  (-> (vec command)
      (conj "make")
      (into entries)
      (conj "--report=json" "--output=/dev/null")))

(defn default-command
  "The elm to run in `root`: the project's own pinned binary when it has one,
   else whatever `elm` is on PATH."
  [root]
  (let [local (io/file root "node_modules" ".bin" "elm")]
    (if (.canExecute local) [(.getPath local)] ["elm"])))

(defrecord ProcessCompiler [command exec-fn]
  ports/IElmCompiler
  (compile! [_ root entries]
    (exec-fn (make-argv command entries) root))
  (version [_ root]
    (r/bind (exec-fn (conj (vec command) "--version") root)
            (fn [{:keys [exit out err]}]
              (if (zero? exit)
                (r/ok (.trim ^String out))
                (r/err :elm/version-failed {:exit exit :err err}))))))

(defn compiler
  ([command] (compiler command exec!))
  ([command exec-fn] (->ProcessCompiler (vec command) exec-fn)))
