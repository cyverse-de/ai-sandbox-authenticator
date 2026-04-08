# AI Sandbox Authentictor

This repository contains the custom Keycloak authenticator to use for the AI Sandboxes user interface. This
authenticator is somewhat similar to Keycloak's built-in [Create User if Unique][1] authenticator with some important
caveats.

## User Experience

- The user navigates to our web application and is redirected to Keycloak as part of the usual OIDC authorization code
  flow.
- Keycloak immediately redirects the user to to the university's IDP for authentication. (I believe that we can use OIDC
  for this, but we may have to resort to SAML2. In either case, this should be possible because Keycloak supports both.)
- The user logs in with their university credentials and is redirected back to Keycloak.
- Keycloak notices that the university account isn't linked with the Keycloak account and searches for accounts that are
  associated with the user's email address. If one is found then Keycloak asks if the accounts should be linked, if the
  user confirms then Keycloak verifies either by asking the user to log into the existing account or by sending a
  confirmation email. Assuming that the confirmation succeeds, Keycloak links the accounts and this login flow
  succeeds. If an account exists with the same email address and the accounts are not linked, then administrative
  intervention is required, the user is instructed to contact support and the login fails.
- If the flow gets to this point then a new account should be created. At this point, Keycloak checks to see if an
  account with the user's preferred username already exists. If one doesn't, then a new account is created with the
  preferred username and linked to the university account, and the login succeeds. If an account with that username does
  exist then the user is asked to select a username, and once a suitable username that does not exist has been found, an
  account is created with that username and linked with the university account, and the login succeeds.

## Caveats

- Our platform has existed for a long time without being integrated with the university's authentication system, so the
  probability of encountering username collisions is fairly high.
- Checking to see if an account in Keycloak that is associated with the user's email address already exists isn't
  sufficient because we also have a database that is populated with user information before Keycloak's user information
  source (an OpenLDAP directory) is populated. We must also check that database before allowing a new account to be
  created.
- When a user's account is created, we have to record it in three places. It will also continue to be necessary to
  create accounts for users who are not affiliated with the university, so we'd like to use a web API call to register
  the accounts. This means that it would be preferable if Keycloak did not write to the LDAP driectory directly and
  instead creates the account via the authenticator.

[1]: https://www.keycloak.org/docs/26.5.7/server_admin/#default-first-login-flow-authenticators
