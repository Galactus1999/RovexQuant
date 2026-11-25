# Mobile App Session Security Operations

## Current security boundary

RovexQuant mobile app sessions are a **single-owner pilot**. The server currently authenticates one configured username/password pair using QUANT_APP_USERNAME and QUANT_APP_PASSWORD. The signed session subject is that configured username; this is not a multi-user identity system and does not provide tenant isolation.

Do not expose this deployment as a multi-user service. Before doing so, implement persistent per-user identities, password hashing and recovery, tenant-scoped authorization/data access, account-level abuse controls, and security tests for cross-tenant access.

## Signing-key requirements

Set QUANT_APP_SESSION_SIGNING_KEY to a randomly generated secret containing at least 32 UTF-8 bytes. Prefer 48 random bytes:

```sh
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Store the value only in the deployment secret manager. Do not commit it, place it in an Android resource, expose it to the client, or print it in CI logs. The API fails closed with HTTP 503 if the configured key is shorter than 32 bytes.

The current session format uses HMAC-SHA256 with a fixed HS256 algorithm, issuer quant-api, audience quant-mobile, and a bounded lifetime (four hours by default, at most 24 hours). Verification rejects malformed/non-canonical segments, invalid claim types, expired tokens, future nbf values, and lifetimes longer than 24 hours.

## Signing-key rotation

The current implementation has one active signing key and no multi-key verification window. Rotation therefore intentionally invalidates every outstanding app session:

1. Generate a new random key using the command above.
2. Replace QUANT_APP_SESSION_SIGNING_KEY in the deployment secret manager.
3. Restart/redeploy every API instance together so no instance continues accepting tokens signed by the old key.
4. Have the mobile app owner sign in again to obtain a new session.
5. Verify the authenticated-session endpoint and a protected API route; confirm old sessions receive HTTP 401.
6. Record the rotation time and deployment revision without recording either key.

Do not roll instances gradually while old and new keys differ: users may otherwise see intermittent authentication failures depending on which instance receives a request. If zero-downtime multi-key rotation becomes necessary, design and test an explicit key identifier (kid) and bounded previous-key overlap before adding it.

## Login throttling and deployment topology

The login endpoint currently applies a thread-safe, process-local sliding-window limit of five requests per direct peer IP per 60 seconds. The limiter intentionally does not trust arbitrary X-Forwarded-For values.

This is a single-process abuse-control layer, not a distributed rate limiter. It is not sufficient for multiple workers, replicas, or a public multi-user deployment. Before horizontal scaling, use an atomic shared store or gateway-level limiter, configure trusted proxy CIDRs, and retain a per-account/global control in addition to IP/network controls. Ensure rate-limit state and retry behavior are observable without logging credentials or bearer tokens.

## Operational checks

- Keep credentials and signing keys in the host's secret manager.
- Use HTTPS/WSS at the public edge.
- Keep API request/exception logs free of passwords, bearer tokens, and signing keys.
- Verify key length and protected-route authentication during deployment.
- Treat a signing-key change as a forced reauthentication event.