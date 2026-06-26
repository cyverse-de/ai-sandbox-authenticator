(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.cyverse/ai-sandbox-authenticator)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-basis (delay (b/create-basis {:project "deps.edn" :aliases [:uber]})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[org.cyverse.ai.sandboxes.broker.authenticator
                                org.cyverse.ai.sandboxes.broker.factory]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @uber-basis
           ;; Keycloak and its transitive dependencies are provided by the
           ;; server at runtime. Keep only our runtime dependencies.
           :exclude ["org/keycloak/.*"
                     "jakarta/.*"
                     "org/jboss/.*"
                     "com/google/protobuf/.*"]}))
