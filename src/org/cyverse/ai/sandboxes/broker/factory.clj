(ns org.cyverse.ai.sandboxes.broker.factory
  (:import
   [org.keycloak.models KeycloakSession KeycloakSessionFactory]
   [com.example.keycloak AiSandboxAuthenticator]))

(gen-class
 :name com.example.keycloak.AiSandboxAuthenticatorFactory
 :implements [org.keycloak.authentication.AuthenticatorFactory]
 :prefix "factory-")

(def provider-id "ai-sandbox-create-user")

(defn factory-getId [_this] provider-id)

(defn factory-getDisplayType [_this] "AI Sandbox - Create User")

(defn factory-getReferenceCategory [_this] "aiSandbox")

(defn factory-getHelpText [_this]
  "Creates a user account via external API if the user doesn't exist.
     Checks both Keycloak and external database for existing users.
     Handles username collisions by prompting for alternative username.")

(defn factory-isConfigurable [_this] false)

(defn factory-getRequirementChoices [_this]
  (into-array org.keycloak.models.AuthenticationExecutionModel$Requirement
              [org.keycloak.models.AuthenticationExecutionModel$Requirement/REQUIRED
               org.keycloak.models.AuthenticationExecutionModel$Requirement/ALTERNATIVE
               org.keycloak.models.AuthenticationExecutionModel$Requirement/DISABLED]))

(defn factory-isUserSetupAllowed [_this] false)

(defn factory-getConfigProperties [_this]
  ;; TODO: Add any configuration properties your authenticator needs
  ;; e.g., API endpoint URLs, timeouts, etc.
  (java.util.Collections/emptyList))

(defn factory-create [_this ^KeycloakSession session]
  (AiSandboxAuthenticator.))

(defn factory-init [_this ^KeycloakSessionFactory factory])

(defn factory-postInit [_this ^KeycloakSessionFactory factory])

(defn factory-close [_this])
