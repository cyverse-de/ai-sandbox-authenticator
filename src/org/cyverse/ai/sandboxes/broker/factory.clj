(ns org.cyverse.ai.sandboxes.broker.factory
  (:import
   [org.keycloak Config Config$Scope]
   [org.keycloak.models KeycloakSession KeycloakSessionFactory]
   [org.keycloak.provider Provider ProviderConfigProperty]
   [org.cyverse.ai.sandboxes.broker AiSandboxAuthenticator]))

(gen-class
 :name org.cyverse.ai.sandboxes.broker.AiSandboxAuthenticatorFactory
 :implements [org.keycloak.authentication.AuthenticatorFactory]
 :prefix "factory-")

(def provider-id "ai-sandbox-create-user")

(def config-properties
  "Configuration properties for the AI Sandbox authenticator."
  [(doto (ProviderConfigProperty.)
     (.setName "portalConductorUrl")
     (.setLabel "Portal Conductor URL")
     (.setHelpText "Base URL of the portal-conductor service (e.g. https://portal-conductor:443)")
     (.setType ProviderConfigProperty/STRING_TYPE))
   (doto (ProviderConfigProperty.)
     (.setName "portalConductorUsername")
     (.setLabel "Portal Conductor Username")
     (.setHelpText "HTTP Basic Auth username for portal-conductor")
     (.setType ProviderConfigProperty/STRING_TYPE))
   (doto (ProviderConfigProperty.)
     (.setName "portalConductorPassword")
     (.setLabel "Portal Conductor Password")
     (.setHelpText "HTTP Basic Auth password for portal-conductor")
     (.setType ProviderConfigProperty/PASSWORD))
   (doto (ProviderConfigProperty.)
     (.setName "portalConductorInsecure")
     (.setLabel "Portal Conductor Insecure TLS")
     (.setHelpText "Set to true to disable TLS certificate verification when calling portal-conductor (NOT recommended for production).")
     (.setType ProviderConfigProperty/BOOLEAN_TYPE))])

(defn factory-getId [_this] provider-id)

(defn factory-getDisplayType [_this] "AI Sandbox - Create User")

(defn factory-getReferenceCategory [_this] "aiSandbox")

(defn factory-getHelpText [_this]
  "Creates a user account via external API if the user doesn't exist.
     Checks both Keycloak and external database for existing users.
     Handles username collisions by prompting for alternative username.")

(defn factory-isConfigurable [_this] true)

(defn factory-getRequirementChoices [_this]
  (into-array org.keycloak.models.AuthenticationExecutionModel$Requirement
              [org.keycloak.models.AuthenticationExecutionModel$Requirement/REQUIRED
               org.keycloak.models.AuthenticationExecutionModel$Requirement/ALTERNATIVE
               org.keycloak.models.AuthenticationExecutionModel$Requirement/DISABLED]))

(defn factory-isUserSetupAllowed [_this] false)

(defn factory-getConfigProperties [_this]
  (java.util.ArrayList. config-properties))

(defn factory-create [_this ^KeycloakSession _session]
  (AiSandboxAuthenticator.))

(defn factory-init [_this ^Config$Scope _config])

(defn factory-postInit [_this ^KeycloakSessionFactory _factory])

(defn factory-close [_this])

;; Default method implementations required because Clojure gen-class does not
;; inherit Java 8 default interface methods.

(defn factory-order [_this]
  0)

(defn factory-getConfigMetadata [_this]
  (java.util.ArrayList.))

(defn factory-dependsOn [_this]
  (java.util.HashSet.))

(defn factory-getOptionalReferenceCategories [_this ^KeycloakSession _session]
  (java.util.HashSet.))

(defn factory-getConfig [_this]
  nil)
