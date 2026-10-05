# 7. Shared API key authentication

- Status: Accepted
- Date: 2026-10-02

## Context and problem

Notification Hub is called by other services, never by people in a browser. There is one
deployment and one consuming system, and no identity provider in the environment.

Every endpoint except the health probe must refuse an unknown caller. The question is how a
caller proves who it is, at the lowest cost that still leaves a clean way to something stronger.

## Considered options

1. **One shared API key** in `Authorization: ApiKey <key>`, configured through an environment
   variable.
2. **Hashed per-client keys**, one key per consuming system, stored as hashes and revocable one
   by one.
3. **OAuth 2.0 client credentials**, with a JWT access token sent as `Authorization: Bearer` and
   validated against the issuer's public keys.

### 1. One shared API key

- Good: no extra infrastructure, nothing to store, one value to configure.
- Good: easy to try. A `curl` with one header is the whole client setup.
- Bad: every caller is the same caller, so logs and metrics cannot say who sent a request.
- Bad: one leaked key can only be revoked by rotating it for everyone at once.
- Bad: no expiry and no scopes. Whoever holds the key can do everything.

### 2. Hashed per-client keys

- Good: each request is tied to a named client, and one client can be revoked alone.
- Good: stays a plain header, so clients barely change.
- Bad: needs a table, an issuing process and a way to hand keys to clients.
- Bad: still long-lived secrets with no expiry unless one is added by hand.

### 3. OAuth 2.0 client credentials with JWT

- Good: the industry default for service-to-service calls. Tokens expire, carry scopes and are
  issued and revoked by a dedicated identity provider.
- Good: Spring Security supports it out of the box as a resource server.
- Bad: needs an identity provider running next to the service, which for one service with one
  consumer would outweigh the service itself.

## Decision

Option 1, a single shared API key, because it protects the service without adding infrastructure
that one consumer cannot justify, and options 2 and 3 can replace it later without moving the
credential.

How it is applied:

- The key travels in `Authorization` with the `ApiKey` scheme, not in a custom `X-API-Key`
  header. `Authorization` is where RFC 9110 puts credentials, so a `401` can carry a
  `WWW-Authenticate` challenge, and proxies and log collectors already mask it by convention.
  `Bearer` is not used because RFC 6750 reserves it for OAuth 2.0 access tokens.
- The presented key is compared in constant time, so response timing reveals nothing about the
  configured one.
- The filter only recognises the caller and never rejects. The single access rule lives in the
  security configuration: deny by default, with the health probe always open and the API
  documentation open only when it is enabled.
- The service is stateless. There is no session, no CSRF token and no logout route.

## Consequences

- Every authenticated request is attributed to one principal, `api-client`. The logs cannot tell
  one consuming system from another.
- Rotating the key means changing it in every caller and restarting the service at the same
  time. There is no overlap window during which both the old and the new key work.
- A leaked key grants full access until that rotation happens.
- If `NOTIFICATIONS_API_KEY` is not set, the service starts with a development default. Any real
  deployment must set it. [ADR 0006](0006-development-credentials.md) covers that default and
  the step that makes the service refuse it.
- Deny by default also covers the container's `/error` dispatch. An unauthenticated request to a
  route that does not exist answers `401`, the same as a protected one, so routes cannot be
  enumerated. The price is that some status codes are less precise: `POST /actuator/health`
  without a key answers `401` instead of `405`.

## Path to production

The steps below can ship one at a time, and none of them moves the credential out of
`Authorization`.

1. **Per-client keys.** Add a table of clients with a hashed key, a name and a revocation date.
   The filter looks up the hash instead of comparing against one value, and the client name
   becomes the principal, so logs and metrics show who called. Two keys per client make rotation
   possible without downtime.
2. **OAuth 2.0 client credentials.** Replace the custom filter with Spring Security's OAuth 2.0
   resource server, trusting an external identity provider. The scheme changes from `ApiKey` to
   `Bearer`, tokens expire on their own, and scopes such as `notifications:write` and
   `notifications:read` become the authorities the access rules check.

If a platform ever requires mutual TLS, it belongs at the proxy or service mesh and complements
either step rather than replacing it.
