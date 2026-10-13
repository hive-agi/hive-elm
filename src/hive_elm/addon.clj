(ns hive-elm.addon
  "IAddon facade for hive-mcp-compatible hosts. The host loads this
   namespace by name from META-INF/hive-addons/hive-elm.edn."
  (:require [hive-elm.addon.lifecycle :as lifecycle]
            [hive-elm.addon.registry :as registry]))

(def addon-id lifecycle/addon-id)
(def addon-ctor lifecycle/addon-ctor)
(def init-as-addon! lifecycle/init-as-addon!)
(def ->ElmAddon lifecycle/->ElmAddon)

(def subdomain registry/subdomain)
(def dispatch registry/dispatch)
(def dispatch-subdomain registry/dispatch-subdomain)
(def code-contributions registry/code-contributions)
