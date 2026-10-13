(ns hive-elm.ports
  "Ports the watcher depends on. Adapters live in their own namespaces;
   tests supply stub records.")

(defprotocol IElmCompiler
  (compile! [this root entries]
    "Run one compile of `entries` in `root`. Result of schema/ExecResult.
     A failing compile is an ok Result with a non-zero :exit; an err Result
     means the compiler could not be run at all.")
  (version [this root]
    "Result of the compiler's version string."))

(defprotocol IBuildNotifier
  (notify! [this notice]
    "Deliver a schema/Notice to whoever should hear of it. Result of a map
     describing the delivery; an err Result when nothing could receive it."))

(defprotocol IFileStamps
  (stamps [this root dirs]
    "{path modified-ms} of every watched file under `dirs` of `root`."))

(defprotocol IClock
  (now-ms [this]))
