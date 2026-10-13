(ns hive-elm.schema
  "Value objects of the Elm build domain."
  (:require [malli.core :as m]))

(def Problem
  "One compiler problem. Location is nil for a problem that is not tied to a
   source region (a broken elm.json, raw non-JSON output)."
  [:map {:closed true}
   [:problem/title :string]
   [:problem/path [:maybe :string]]
   [:problem/module [:maybe :string]]
   [:problem/line [:maybe :int]]
   [:problem/column [:maybe :int]]
   [:problem/message :string]])

(def Report
  "The outcome of one `elm make`."
  [:map {:closed true}
   [:report/state [:enum :ok :failed]]
   [:report/problems [:vector Problem]]])

(def Memory
  "What the watcher remembers of the previous report: enough to tell a new
   failure from the same one seen again."
  [:maybe
   [:map {:closed true}
    [:memory/state [:enum :ok :failed]]
    [:memory/fingerprint :string]]])

(def Decision
  [:map {:closed true}
   [:decision/kind [:enum :notify-failure :notify-recovery :quiet]]])

(def Notice
  "What a notifier is handed. Port-neutral: no hivemind vocabulary here."
  [:map {:closed true}
   [:notice/kind [:enum :build-failed :build-recovered]]
   [:notice/root :string]
   [:notice/text :string]
   [:notice/problems :int]
   [:notice/title {:optional true} :string]
   [:notice/file {:optional true} :string]
   [:notice/line {:optional true} :int]])

(def ExecResult
  "A finished compiler process. A non-zero exit is data, not an error."
  [:map
   [:exit :int]
   [:out :string]
   [:err :string]])

(def Manifest
  [:map {:closed true}
   [:hive.elm/entries [:vector {:min 1} :string]]
   [:hive.elm/source-dirs [:vector {:min 1} :string]]
   [:hive.elm/command {:optional true} [:vector {:min 1} :string]]
   [:hive.elm/watch
    [:map {:closed true}
     [:poll-ms pos-int?]
     [:debounce-ms nat-int?]]]
   [:hive.elm/notify
    [:map {:closed true}
     [:agent-id {:optional true} :string]
     [:parent-id {:optional true} [:maybe :string]]
     [:project-id {:optional true} [:maybe :string]]]]])

(def valid-report? (m/validator Report))
(def valid-notice? (m/validator Notice))
(def valid-manifest? (m/validator Manifest))
(def explain-manifest (m/explainer Manifest))
