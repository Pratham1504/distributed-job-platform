# Security Design and Threat Model

## 1. Assets and trust boundaries

Protect user identity, project API keys, job payloads, result artifacts, database data, RabbitMQ/Redis credentials, and Azure credentials.

Trust boundaries: public caller to API; API to private stateful services; workers to external providers; platform to a user-supplied webhook; operator to VM/dashboards.

## 2. Authorization model

MVP roles: `USER` and `OPERATOR`. A USER owns one or more personal projects.

- `USER`: can create/read/cancel/retry only resources inside own projects.
- `OPERATOR`: deployment-level account; may view operational health and redacted audit data, never arbitrary customer payloads without explicit operational need.
- API key: scoped to one project and can submit, read, cancel, and manually retry jobs in that exact project; it cannot manage users, projects, API keys, or operator endpoints.
- Inaccessible resource operations return `404` to avoid enumeration.

## 3. Threats and controls

| Threat | Control |
| --- | --- |
| Stolen password | BCrypt/Argon2, TLS, rate-limited login, 15-minute JWT, refresh rotation |
| Stolen API key | show raw key once, store hash only, prefix lookup, revoke/rotate, audit use |
| Cross-project access | project ID plus owner join on every resource query; authorisation tests; 404 on mismatch |
| Arbitrary code execution | fixed handler enum and strict schema; no shell, class, arbitrary URL/image, or executable in payload |
| Malicious file access | project-owned `file_asset` ID only, private storage, CSV/UTF-8/size limits, header/row validation, short-lived signed download access |
| SSRF through webhook | HTTPS only, DNS/IP validation, block private/link-local/metadata ranges, redirects disabled, time/size caps |
| Duplicate external effect | at-least-once contract plus stable per-run `effectId` and provider/durable-handler dedupe |
| Broker/DB compromise | private network, distinct credentials, least privilege, no public DB/Redis/RabbitMQ ports |
| Sensitive logs | redact tokens, API keys, passwords, recipient data, and marked-secret payload fields |
| Queue flooding | project quota, payload limits, rate limiting, queue-depth alerts |
| Upload exhaustion/malware | 10 MiB and 100,000-row limit, CSV-only allowlist, content sniffing, private storage, quota, optional malware scan before production |

## 4. Token, key, and secrets lifecycle

- JWT access token: 15 minutes; refresh token: 7 days, opaque, stored hashed, rotated on every use, and grouped into a revocable token family. Reuse of a rotated/revoked token revokes its active family.
- API keys: minimum 32 random bytes; shown once; replacement then revocation for rotation.
- Secrets: environment variables locally; Azure Key Vault before real production deployment.
- Credentials never enter Git, Docker images, logs, or dashboard responses.

## 5. Audit events

Record login success/failure, API-key creation/revocation/use, job submission, cancellation, manual retry, payload rejection, execution lease recovery/finalisation, and operator configuration changes. Keep append-only audit records for 180 days.
