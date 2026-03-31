# Security Checklist

## Admin Plane Exposure

- Do not expose `/asl` and `/asl/api` publicly without authentication
- Put admin UI and REST behind network controls, gateway policy, or service mesh rules
- Restrict admin operations to privileged operators only
- Audit all stop, enable, replay, clear, and delete actions

## Authentication and Authorization

- Protect admin endpoints with Spring Security before production rollout
- Enforce role-based access for method control actions
- Separate read-only visibility from mutating control operations
- Require stronger auth for replay, clear, and delete operations

## Data Protection

- Assume queue payloads may contain sensitive business data
- Do not enqueue secrets, raw credentials, or unnecessary personal data
- Encrypt the underlying storage volume if payload sensitivity requires it
- Review queue payload retention and deletion requirements
- Prefer a custom `AsyncPayloadCodec` if payloads need redaction, field-level filtering, or controlled on-disk encoding

## API Hygiene

- Validate and encode `serviceId`, `methodId`, and `entryId` when routing through external systems
- Log control-plane access with request identity and source
- Avoid returning sensitive exception content directly to untrusted clients
- Review `lastError` exposure if exceptions may contain internal details

## Operational Safety

- Require approval or change control for disabling critical methods
- Guard high-risk admin routes with rate limits if exposed through shared gateways
- Disable admin UI entirely in environments where it is not needed
- Separate production and non-production queue paths

## Spring Boot Hardening

- Add CSRF strategy if admin UI is used from a browser session
- Review actuator and admin endpoint coexistence so both are not overexposed
- Ensure generated governed beans are not bypassed via unsafe direct implementation wiring
- Keep dependency versions current, especially Spring Boot and MapDB
