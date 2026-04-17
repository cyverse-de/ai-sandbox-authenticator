(ns org.cyverse.ai.sandboxes.broker.authenticator
  (:gen-class
   :name org.cyverse.ai.sandboxes.broker.AiSandboxAuthenticator
   :extends org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator)
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as log])
  (:import
   [org.keycloak.authentication AuthenticationFlowContext AuthenticationFlowError]
   [org.keycloak.authentication.authenticators.broker AbstractIdpAuthenticator]
   [org.keycloak.authentication.authenticators.broker.util ExistingUserInfo SerializedBrokeredIdentityContext]
   [org.keycloak.broker.provider BrokeredIdentityContext]
   [org.keycloak.models UserModel]
   [org.keycloak.services.messages Messages]
   [jakarta.ws.rs.core Response$Status]))

;;; ---------------------------------------------------------------------------
;;; External API stubs - replace these with actual implementations
;;; ---------------------------------------------------------------------------

(defn check-external-database-for-email
  "Check external database to see if a user with this email already exists.
     Returns {:exists? true :user-id \"...\" :username \"...\"} or {:exists? false}"
  [email]
  ;; TODO: Call your external database API here
  ;; This should check the database that is populated before LDAP
  (log/debug "Checking external database for email:" email)
  {:exists? false})

(defn check-external-database-for-username
  "Check external database to see if a username is already taken.
     Returns true if username exists, false otherwise."
  [username]
  ;; TODO: Call your external database API here
  (log/debug "Checking external database for username:" username)
  false)

(defn create-user-via-api!
  "Create user account via web API, which handles creating the account
     in all three systems (Keycloak/LDAP, external DB, and other subsystems).
     Returns the created user info or throws on failure."
  [user-info]
  ;; TODO: Call your account creation API here
  ;; user-info contains :username :email :first-name :last-name :attributes
  ;; The API should:
  ;; 1. Create account in external database
  ;; 2. Create account in LDAP
  ;; 3. Create account in other subsystems
  (log/info "Creating user via API:" (:username user-info))
  {:success? true
   :user-id (str (java.util.UUID/randomUUID))})

;;; ---------------------------------------------------------------------------
;;; Authenticator implementation
;;; ---------------------------------------------------------------------------

(defn- get-username
  "Extract username from broker context."
  [^AuthenticationFlowContext context ^BrokeredIdentityContext broker-context]
  (let [realm (.getRealm context)]
    (if (.isRegistrationEmailAsUsername realm)
      (.getEmail broker-context)
      (.getModelUsername broker-context))))

(defn- username-available?
  "Check if username is available in both Keycloak and external database."
  [^AuthenticationFlowContext context username]
  (let [realm (.getRealm context)
        session (.getSession context)
        keycloak-user (.getUserByUsername (.users session) realm username)
        external-exists? (check-external-database-for-username username)]
    (and (nil? keycloak-user) (not external-exists?))))

(defn- show-username-selection-form
  "Display form for user to select an alternative username."
  [^AuthenticationFlowContext context ^SerializedBrokeredIdentityContext serialized-ctx
   attempted-username error-message]
  (let [form (-> context
                 .form
                 (.setAttribute "attemptedUsername" attempted-username)
                 (.setAttribute "brokerContext" serialized-ctx))]
    (when error-message
      (.setError form error-message (into-array Object [])))
    (-> form
        ;; You'll need to create this FTL template
        (.createForm "ai-sandbox-username-selection.ftl")
        (->> (.challenge context)))))

(defn- create-federated-user!
  "Create the user account via the external API and register in Keycloak."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   ^BrokeredIdentityContext broker-context
   username]
  (let [session (.getSession context)
        realm (.getRealm context)
        user-info {:username username
                   :email (.getEmail broker-context)
                   :first-name (.getFirstName broker-context)
                   :last-name (.getLastName broker-context)
                   :attributes (.getAttributes serialized-ctx)}]

    ;; TODO: Create user via your API
    ;; This API call should create the user in all three systems
    (let [api-result (create-user-via-api! user-info)]
      (if (:success? api-result)
        (do
          ;; After API creates user in LDAP, Keycloak should be able to find them
          ;; You may need to clear caches or use a different approach depending
          ;; on your user storage provider configuration
          (let [federated-user (.getUserByUsername (.users session) realm username)]
            (if federated-user
              (do
                (.setEnabled federated-user true)
                ;; Set any additional attributes from the broker context
                (doseq [[attr-name attr-values] (.getAttributes serialized-ctx)]
                  (when-not (= UserModel/USERNAME (.equalsIgnoreCase attr-name))
                    (.setAttribute federated-user attr-name attr-values)))

                (.setUser context federated-user)
                (.setAuthNote (.getAuthenticationSession context)
                              "BROKER_REGISTERED_NEW_USER" "true")
                (log/info "Successfully created user:" username)
                (.success context))

              ;; User was created via API but can't be found in Keycloak
              ;; This might happen if LDAP sync is delayed
              (do
                (log/error "User created via API but not found in Keycloak:" username)
                (-> context
                    .form
                    (.setError Messages/INTERNAL_SERVER_ERROR (into-array Object []))
                    (.createErrorPage Response$Status/INTERNAL_SERVER_ERROR)
                    (->> (.failure context AuthenticationFlowError/INTERNAL_ERROR)))))))

        ;; API call failed
        (do
          (log/error "Failed to create user via API:" username)
          (-> context
              .form
              (.setError Messages/INTERNAL_SERVER_ERROR (into-array Object []))
              (.createErrorPage Response$Status/INTERNAL_SERVER_ERROR)
              (->> (.failure context AuthenticationFlowError/INTERNAL_ERROR))))))))

(defn- error-challenge
  "Returns a challenge that will display an error message to the user."
  [^AuthenticationFlowContext context msg ^Response$Status status]
  (.failure context
            AuthenticationFlowError/IDENTITY_PROVIDER_ERROR
            (.createErrorPage (.setError (.form context) msg (object-array [])) status)))

(defn- get-user-by-email
  "Check for existing user by email in both Keycloak and external database."
  [^AuthenticationFlowContext context ^BrokeredIdentityContext broker-context]
  (let [email (.getEmail broker-context)
        realm (.getRealm context)
        session (.getSession context)]
    (when (and email (not (.isDuplicateEmailsAllowed realm)))
      ;; First check Keycloak
      (if-let [existing-user (.getUserByEmail (.users session) realm email)]
        (do
          (log/debug "Found existing user in Keycloak with email:" email)
          (ExistingUserInfo. (.getId existing-user) UserModel/EMAIL email))
        ;; Then check external database
        (let [external-result (check-external-database-for-email email)]
          (when (:exists? external-result)
            (log/debug "Found existing user in external database with email:" email)
            ;; Return a sentinel ExistingUserInfo - the user exists externally
            ;; but not in Keycloak, which requires admin intervention
            (ExistingUserInfo. (:user-id external-result) UserModel/EMAIL email)))))))

(defn- handle-email-collision
  "Checks for an email address collision, and if a collision exists, presents a challenge to the user in order to
   display an error. Returns true if an email address collision is detected."
  [^AuthenticationFlowContext context ^BrokeredIdentityContext broker-context]
  (when-let [duplicate (get-user-by-email context broker-context)]
    (log/warn "Email collition detected, admin intervention required: " (.getDuplicateAttributeValue duplicate))
    (error-challenge
     context
     "An account with this email address already exists. Please contact support."
     Response$Status/CONFLICT)
    true))

(defn- handle-username-collision
  "Checks for a username collision. and if a collision exists, presents a challenge to the user so that they can either
   link the accounts or select a new username. Returns true if a username collision is detected."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   username]
  (when-not (username-available? context username)
    (show-username-selection-form context serialized-ctx username "Please choose an available username.")
    true))

(defn authenticate-impl
  "Main authentication logic."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   ^BrokeredIdentityContext broker-context]

  (let [broker             (.getIdpConfig broker-context)
        preferred-username (get-username context broker-context)]
    (cond
      ;; Transient users aren't supported.
      (.isTransientUsers broker)
      (do
        (log/warn "Transient users not supported by AI Sandboxes:" (.getAlias broker))
        (let [challenge (-> context
                            .form
                            (.setError "Transient users are not supported for this application"
                                       (into-array Object []))
                            (.createErrorPage Response$Status/BAD_REQUEST))]
          (.failure context AuthenticationFlowError/IDENTITY_PROVIDER_ERROR challenge)))

      ;; If we've already identified an existing user, there's nothing left to do.
      (.getAuthNote (.getAuthenticationSession context) AbstractIdpAuthenticator/EXISTING_USER_INFO)
      (.attempted context)

      ;; We need a username in order to proceed.
      (string/blank? preferred-username)
      (do
        (log/info "No username available, enforcing profile update")
        (.setAuthNote (.getAuthenticationSession context) AbstractIdpAuthenticator/ENFORCE_UPDATE_PROFILE "true")
        (.resetFlow context))

      :else
      (or (handle-email-collision context broker-context)
          (handle-username-collision context serialized-ctx preferred-username)
          (create-federated-user! context serialized-ctx broker-context preferred-username)))))

(defn action-impl
  "Handle form submission for username selection."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   ^BrokeredIdentityContext broker-context]

  (let [form-data (.getDecodedFormParameters (.getHttpRequest context))
        selected-username (-> form-data (.getFirst "username") str .trim)]

    (cond
      ;; Validate username is provided
      (or (nil? selected-username) (.isEmpty selected-username))
      (show-username-selection-form
       context serialized-ctx "" "Please enter a username.")

      ;; TODO: Add any additional username validation (length, characters, etc.)

      ;; Check if selected username is available
      (not (username-available? context selected-username))
      (show-username-selection-form
       context serialized-ctx selected-username
       "This username is also taken. Please choose a different one.")

      ;; Username is valid and available, create the user
      :else
      (create-federated-user! context serialized-ctx broker-context selected-username))))

(defn -authenticateImpl
  [_this context serialized-ctx broker-context]
  (authenticate-impl context serialized-ctx broker-context))

(defn -actionImpl
  [_this context serialized-ctx broker-context]
  (action-impl context serialized-ctx broker-context))

(defn -requiresUser
  "Returns a value indicating whether or not the Keycloak user has to be identified before calling this authenticator.
  This authenticator is intended to be used with the first broker login flow, so the user does not have to be identified
  in advance."
  [_this]
  false)

(defn -configuredFor
  "Returns a value indicating whether or not a user is configured for this authenticator. This authenticator requires no
  user-specific configuration, so the return value is always `true`."
  [_this _session _realm _user]
  true)
