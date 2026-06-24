(ns org.cyverse.ai.sandboxes.broker.authenticator
  (:gen-class
   :name org.cyverse.ai.sandboxes.broker.AiSandboxAuthenticator
   :extends org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator
   :state state
   :init init)
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [clj-http.client :as http])
  (:import
   [org.keycloak.authentication AuthenticationFlowContext AuthenticationFlowError]
   [org.keycloak.authentication.authenticators.broker AbstractIdpAuthenticator]
   [org.keycloak.authentication.authenticators.broker.util ExistingUserInfo SerializedBrokeredIdentityContext]
   [org.keycloak.broker.provider BrokeredIdentityContext]
   [org.keycloak.models UserModel]
   [org.keycloak.services.messages Messages]
   [jakarta.ws.rs.core Response$Status]))

;;; ---------------------------------------------------------------------------
;;; Constructor
;;; ---------------------------------------------------------------------------

(defn -init
  "Initialize the authenticator instance."
  []
  [[] (atom {})])

;;; ---------------------------------------------------------------------------
;;; Portal Conductor API client
;;; ---------------------------------------------------------------------------

(defn- get-authenticator-config
  "Retrieve the authenticator configuration from the execution context."
  [^AuthenticationFlowContext context]
  (when-let [auth-config (.getAuthenticatorConfig context)]
    (.getConfig auth-config)))

(defn- config-available?
  "Returns true if the authenticator configuration has a portalConductorUrl set."
  [config]
  (boolean (and config
                (not (string/blank? (get config "portalConductorUrl"))))))

(defn- portal-conductor-request
  "Make an authenticated HTTP request to portal-conductor.

  Args:
    config: Map with keys \"portalConductorUrl\", \"portalConductorUsername\",
            \"portalConductorPassword\"
    method: :get or :post
    path: URL path (e.g. \"/portal/users/foo/exists\")
    opts: Additional options (e.g. :body for POST requests)

  Returns:
    Parsed JSON response body as a map with keyword keys."
  [config method path & [opts]]
  (let [base-url   (get config "portalConductorUrl")
        username   (get config "portalConductorUsername")
        password   (get config "portalConductorPassword")
        insecure?  (Boolean/parseBoolean (get config "portalConductorInsecure"))
        url        (str (-> base-url
                            string/trim
                            (string/replace #"/+$" ""))
                        path)
        request-fn (case method
                     :get  http/get
                     :post http/post)]
    (request-fn url
                (merge {:basic-auth [username password]
                        :content-type :json
                        :accept :json
                        :as :json
                        :json-opts {:key-fn keyword}
                        :throw-exceptions false
                        :insecure? insecure?}
                       opts))))

(defn- check-external-database-for-email
  "Check portal-conductor to see if a user with this email already exists.

  Returns {:exists? true/false}."
  [config email]
  (log/debug "Checking portal-conductor for email:" email)
  (let [response (portal-conductor-request
                  config :get
                  (str "/portal/emails/" (java.net.URLEncoder/encode email "UTF-8") "/exists"))]
    (if (= 200 (:status response))
      {:exists? (get-in response [:body :exists] false)}
      (throw
       (ex-info "Unexpected response from portal-conductor email check"
                {:email  email
                 :status (:status response)})))))

(defn- check-external-database-for-username
  "Check portal-conductor to see if a username is already taken.

  Returns true if username exists or is restricted, false otherwise."
  [config username]
  (log/debug "Checking portal-conductor for username:" username)
  (let [response (portal-conductor-request
                  config :get
                  (str "/portal/users/" (java.net.URLEncoder/encode username "UTF-8") "/exists"))]
    (if (= 200 (:status response))
      (get-in response [:body :exists] false)
      (throw
       (ex-info "Unexpected response from portal-conductor username check"
                {:username username
                 :status   (:status response)})))))

(defn- create-user-via-api!
  "Create user account via portal-conductor, which handles creating the account
  in all systems (Portal DB, LDAP, iRODS).

  The request only requires username, email, first_name, and last_name.
  Portal-conductor handles password generation and default values for all
  other fields.

  Returns {:success? true :user-id \"...\"} or {:success? false :error \"...\"}."
  [config user-info]
  (log/info "Creating user via portal-conductor:" (:username user-info))
  (try
    (let [body     {:username   (:username user-info)
                    :email      (:email user-info)
                    :first_name (:first-name user-info)
                    :last_name  (:last-name user-info)}
          response (portal-conductor-request
                    config :post "/portal/users"
                    {:body (json/write-str body)})]
      (case (:status response)
        201 {:success? true
             :user-id  (str (get-in response [:body :user_id]))}
        400 (do
              (log/warn "User creation rejected by portal-conductor:"
                        (get-in response [:body :detail]))
              {:success? false
               :error    (get-in response [:body :detail] "User creation rejected")})
        ;; else
        (do
          (log/error "Unexpected response from portal-conductor user creation:"
                     (:status response) (:body response))
          {:success? false
           :error    (str "Portal conductor returned status " (:status response))})))
    (catch Exception e
      (log/error e "Failed to create user via portal-conductor:" (:username user-info))
      {:success? false
       :error    (.getMessage e)})))

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
  "Check if username is available in both Keycloak and the external database."
  [^AuthenticationFlowContext context config username]
  (let [realm            (.getRealm context)
        session          (.getSession context)
        keycloak-user    (.getUserByUsername (.users session) realm username)
        external-exists? (check-external-database-for-username config username)]
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
        (.createForm "ai-sandbox-username-selection.ftl")
        (->> (.challenge context)))))

(defn- error-challenge
  "Returns a challenge that will display an error message to the user."
  [^AuthenticationFlowContext context
   ^AuthenticationFlowError error-type
   msg
   ^Response$Status status]
  (.failure context
            error-type
            (.createErrorPage (.setError (.form context) msg (object-array [])) status)))

(defn- internal-server-error-challenge
  [context]
  (error-challenge
   context
   AuthenticationFlowError/INTERNAL_ERROR
   Messages/INTERNAL_SERVER_ERROR
   Response$Status/INTERNAL_SERVER_ERROR))

(defn- set-up-keycloak-user
  "Finishes setting up the new Keycloak user and selects the user for the authentication context."
  [^UserModel user
   ^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx]
  (.setEnabled user true)
  (doseq [[attr-name attr-values] (.getAttributes serialized-ctx)]
    (when-not (.equalsIgnoreCase UserModel/USERNAME attr-name)
      (.setAttribute user attr-name attr-values)))
  (.setUser context user)
  (.setAuthNote (.getAuthenticationSession context) "BROKER_REGISTERED_NEW_USER" "true")
  (log/info "Successfully created user:" (.getUsername user)))

(defn- create-federated-user!
  "Create the user account via portal-conductor and register in Keycloak."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   ^BrokeredIdentityContext broker-context
   config
   username]
  (let [session   (.getSession context)
        realm     (.getRealm context)
        user-info {:username   username
                   :email      (.getEmail broker-context)
                   :first-name (.getFirstName broker-context)
                   :last-name  (.getLastName broker-context)
                   :attributes (.getAttributes serialized-ctx)}
        result    (create-user-via-api! config user-info)]
    (if-not (:success? result)
      (do
        (log/error "User creation failed:" (:error result))
        (internal-server-error-challenge context))
      ;; Portal-conductor created the user in LDAP. Since Keycloak federates
      ;; against that LDAP, the user should now be visible to Keycloak.
      (if-let [user (.getUserByUsername (.users session) realm username)]
        (set-up-keycloak-user user context serialized-ctx)
        (do
          (log/error "User was created in portal-conductor but not found in Keycloak."
                     "Check LDAP federation sync settings.")
          (internal-server-error-challenge context))))))

(defn- get-user-by-email
  "Check for existing user by email in both Keycloak and the external database."
  [^AuthenticationFlowContext context ^BrokeredIdentityContext broker-context config]
  (let [email   (.getEmail broker-context)
        realm   (.getRealm context)
        session (.getSession context)]
    (when (and email (not (.isDuplicateEmailsAllowed realm)))
      ;; First check Keycloak
      (if-let [existing-user (.getUserByEmail (.users session) realm email)]
        (do
          (log/debug "Found existing user in Keycloak with email:" email)
          (ExistingUserInfo. (.getId existing-user) UserModel/EMAIL email))
        ;; Then check portal-conductor
        (let [external-result (check-external-database-for-email config email)]
          (when (:exists? external-result)
            (log/debug "Found existing user in external database with email:" email)
            ;; Return a sentinel ExistingUserInfo - the user exists externally
            ;; but not in Keycloak, which requires admin intervention
            (ExistingUserInfo. "external-user" UserModel/EMAIL email)))))))

(defn- handle-email-collision
  "Checks for an email address collision. Returns true if a collision is detected."
  [^AuthenticationFlowContext context ^BrokeredIdentityContext broker-context config]
  (try
    (when-let [duplicate (get-user-by-email context broker-context config)]
      (log/warn "Email collision detected, admin intervention required:"
                (.getDuplicateAttributeValue duplicate))
      (error-challenge
       context
       AuthenticationFlowError/IDENTITY_PROVIDER_ERROR
       "An account with this email address already exists. Please contact support."
       Response$Status/CONFLICT)
      true)
    (catch Exception e
      (log/error e "Email lookup failed while checking for collisions")
      (internal-server-error-challenge context)
      true)))

(defn- handle-username-collision
  "Checks for a username collision. Returns true if a collision is detected."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   config
   username]
  (try
    (when-not (username-available? context config username)
      (show-username-selection-form context serialized-ctx username
                                    "This username is not available. Please choose a different one.")
      true)
    (catch Exception e
      (log/error e "Username lookup failed while checking availability")
      (internal-server-error-challenge context)
      true)))

(defn authenticate-impl
  "Main authentication logic."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   ^BrokeredIdentityContext broker-context]

  (let [config             (get-authenticator-config context)
        broker             (.getIdpConfig broker-context)
        preferred-username (get-username context broker-context)]
    (cond
      ;; Configuration must be present.
      (not (config-available? config))
      (do
        (log/error "AI Sandbox authenticator is not configured. Set portalConductorUrl in the authenticator config.")
        (internal-server-error-challenge context))

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

      ;; The preferred username must satisfy portal-conductor's constraints
      ;; (lowercase alphanumeric only). If it doesn't — for example because
      ;; registrationEmailAsUsername is enabled on the realm and the IdP
      ;; returned an email address — skip straight to the selection form rather
      ;; than attempting collision checks against an invalid username.
      (not (re-matches #"^[0-9a-z]+$" preferred-username))
      (do
        (log/info "Preferred username" preferred-username "does not meet format requirements; prompting for selection")
        (show-username-selection-form context serialized-ctx ""
                                      "Please choose a username containing only lowercase letters and numbers."))

      :else
      (or (handle-email-collision context broker-context config)
          (handle-username-collision context serialized-ctx config preferred-username)
          (create-federated-user! context serialized-ctx broker-context config preferred-username)))))

(defn action-impl
  "Handle form submission for username selection."
  [^AuthenticationFlowContext context
   ^SerializedBrokeredIdentityContext serialized-ctx
   ^BrokeredIdentityContext broker-context]

  (let [config            (get-authenticator-config context)
        form-data         (.getDecodedFormParameters (.getHttpRequest context))
        selected-username (-> form-data (.getFirst "username") str string/trim string/lower-case)]

    (cond
      ;; Configuration must be present — same guard as authenticate-impl.
      (not (config-available? config))
      (do
        (log/error "AI Sandbox authenticator is not configured. Set portalConductorUrl in the authenticator config.")
        (internal-server-error-challenge context))

      ;; Validate username is provided
      (string/blank? selected-username)
      (show-username-selection-form
       context serialized-ctx "" "Please enter a username.")

      ;; Validate username format (lowercase alphanumeric only, matching portal-conductor)
      (not (re-matches #"^[0-9a-z]+$" selected-username))
      (show-username-selection-form
       context serialized-ctx selected-username
       "Username must contain only lowercase letters and numbers.")

      ;; Username is valid and available, create the user
      :else
      (try
        (if (username-available? context config selected-username)
          (create-federated-user! context serialized-ctx broker-context config selected-username)
          (show-username-selection-form
           context serialized-ctx selected-username
           "This username is also taken. Please choose a different one."))
        (catch Exception e
          (log/error e "Username lookup failed during username selection")
          (internal-server-error-challenge context))))))

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
