(ns hive-elm.addon.lifecycle
  "IAddon record and constructor for the hive.elm addon."
  (:require [hive-addon.protocol :as proto]
            [hive-addon.registry.commands :as addon-cmds]
            [hive-elm.addon.handlers :as h]
            [hive-elm.addon.registry :as registry]
            [hive-elm.notify.sense :as sense]))

(def addon-id "hive.elm")

(def addon-key
  "Ownership key for contributions, so retraction removes only ours."
  :hive-elm)

(def host-tool "code")

(defrecord ElmAddon [config-ref]
  proto/IAddon
  (addon-id [_] addon-id)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :schema :health-reporting})
  (initialize! [_ config]
    (reset! config-ref (or (:addon/config config) {}))
    (addon-cmds/contribute! host-tool addon-key registry/code-contributions)
    {:success? true
     :errors   []
     :metadata {:subdomain (str host-tool " " registry/subdomain)
                :sense     (sense/register!)}})
  (shutdown! [_]
    (addon-cmds/retract! host-tool addon-key)
    (h/stop-all!)
    (sense/unregister!)
    nil)
  ;; No standalone root: the surface is the `code elm ...` subdomain.
  (tools [_] [])
  (schema-extensions [_] {host-tool registry/schema-ext})
  (health [_] {:status :ok :details {:watchers (h/running-watchers)}})
  (excluded-tools [_] #{})
  (hooks [_] {}))

(defn addon-ctor
  ([] (addon-ctor {}))
  ([_config] (->ElmAddon (atom {}))))

(defn init-as-addon!
  ([] (init-as-addon! {}))
  ([config]
   (let [addon (addon-ctor config)]
     (proto/initialize! addon config)
     addon)))
