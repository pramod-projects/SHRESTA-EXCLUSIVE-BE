# ADR 0009: Provider-Independent Transactional Email

## Status

Accepted

## Context

SHRESTA currently sends email synchronously through Spring SMTP from business request transactions. The implementation has inline templates, no durable delivery state, no provider isolation, and no safe retry or failover model. Provider failures can therefore affect authentication and order request paths. The platform needs Brevo, Resend, Mailjet, MailerSend, and Elastic Email now, with Amazon SES remaining an additive future adapter.

Current scale does not justify a message broker or separate service. PostgreSQL, Spring scheduling, Micrometer, the existing admin change-request workflow, and the existing OTP challenge table provide the required foundation.

## Decision

Email remains a module in the Spring Boot modular monolith. Business transactions call a provider-independent `EmailNotificationService`, which validates a strongly typed `NotificationType`, applies server-side notification policy, and inserts an `email_outbox` record in the same PostgreSQL transaction as the business change.

A scheduled worker claims due records with `FOR UPDATE SKIP LOCKED`, assigns a bounded lease and monotonically increasing fencing generation, renders application-owned Thymeleaf HTML and plain-text templates, and delegates delivery to a centralized provider router. Every provider is an isolated package under `platform.email.provider` and implements only shared email contracts. Provider packages must not import one another.

The provider order is environment configuration. The initial default is Brevo, Resend, Mailjet, MailerSend, then Elastic Email. Routing never appears in business modules. Disabled, unconfigured, or omitted providers are excluded; omission from the order cannot create an implicit last-resort fallback. Known transient non-acceptance may retry or fail over; permanent failures do not. Connection refusal, DNS failure, and connect timeout are known pre-acceptance failures. A read timeout or unclassified transport failure is `OUTCOME_UNKNOWN` and is not blindly failed over because the provider may have accepted the message.

Application templates are versioned resources and providers receive fully rendered HTML and text. Provider-hosted templates are not authoritative. Template variables are allowlisted per notification type, escaped by default, and validated before enqueueing. Subjects, addresses, and URLs are validated against header injection and unsafe schemes.

## Module Boundaries

```text
platform/email/
  application/       provider-independent commands and notification facade
  configuration/     runtime policy, worker, routing, and environment safety
  domain/            notification types, states, failures, and provider SPI
  persistence/       outbox, attempts, webhook events, and configuration
  routing/           provider selection and failover
  template/          application-owned template rendering and validation
  worker/            row claiming, leases, retries, and cleanup
  webhook/           normalized webhook application service
  provider/
    brevo/
    resend/
    mailjet/
    mailersend/
    elasticemail/
    ses/              future extension boundary only
```

Each active provider package owns its properties, HTTP client, request and response DTOs, mapping, error classification, webhook parsing, authentication verification, and adapter tests. The shared provider contract exposes enablement, send readiness, and authenticated-webhook readiness so production startup can reject partial configuration without importing provider-specific properties.

## Database Design

`notification_configuration` stores one row per notification type, its enabled state, security lock, optimistic version, and audit metadata. Security-critical types are server-locked enabled in production.

`notification_configuration_audit` is append-only history for applied configuration changes. The approved update locks the current configuration row, records the actual previous and new values with reviewer identity, reason, and timestamp, and commits the update and audit row in one transaction. The existing admin change request remains the approval workflow and preserves submission/review context.

`email_outbox` stores notification identity, unique idempotency key, type, recipient snapshot, template variables and version, priority, state, attempt count, next attempt, lease, correlation identifiers, timestamps, and expiry. Its claim index is `(state, next_attempt_at, priority, created_at)`. Reuse of an idempotency key is accepted only for the same notification type and normalized recipient; conflicting identity reuse fails atomically. Sensitive short-lived values such as OTPs are removed after terminal processing or expiry. Maintenance transitions expired pending, retrying, and stale-leased work to `FAILED` before terminal retention removes it.

`email_delivery_attempts` stores one immutable row per provider attempt: provider, provider idempotency key, timing, outcome, HTTP status, provider message ID, safe error classification, and retry decision. It never stores credentials, rendered content, OTPs, or raw provider responses.

`email_webhook_events` stores a provider event ID or deterministic payload digest, normalized event type, provider message ID, occurrence time, processing state, and timestamps. `(provider, provider_event_id)` is unique for replay safety. A callback that arrives before the worker commits provider correlation remains `PENDING`; a bounded scheduled reconciliation pass applies it after correlation exists and marks it `PROCESSED`.

The delivery state machine is:

```text
PENDING -> PROCESSING -> PROVIDER_ACCEPTED -> DELIVERED
                     -> RETRY_WAIT -> PROCESSING
                     -> OUTCOME_UNKNOWN
                     -> FAILED
PROVIDER_ACCEPTED -> BOUNCED | REJECTED | COMPLAINT
```

`PROVIDER_ACCEPTED` is not recipient delivery. Exactly-once delivery cannot be guaranteed across external providers; the design provides transactional enqueueing, at-least-once worker processing, provider idempotency where supported, and conservative handling of ambiguous outcomes.

## Notification Policy

Supported types are `OTP`, `EMAIL_VERIFICATION`, `PASSWORD_RESET`, `ACCOUNT_SECURITY`, `ORDER_CONFIRMATION`, `ORDER_CANCELLED`, `ORDER_SHIPPED`, `ORDER_OUT_FOR_DELIVERY`, `ORDER_DELIVERED`, `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `REFUND_INITIATED`, and `REFUND_COMPLETED`.

Every type is visible in admin configuration. `OTP`, `EMAIL_VERIFICATION`, `PASSWORD_RESET`, and `ACCOUNT_SECURITY` cannot be disabled in production. All other types are independently configurable. Updates require the existing admin change-request approval path; application records preserve submitter and reviewer context, while immutable configuration audit rows preserve the actual previous and new values, reviewer identity, reason, and timestamp.

Template support does not invent business events. Current workflow owners emit `OTP`, `ORDER_CONFIRMATION`, `ORDER_OUT_FOR_DELIVERY`, `ORDER_DELIVERED`, `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `REFUND_INITIATED`, and `REFUND_COMPLETED`. `EMAIL_VERIFICATION`, `PASSWORD_RESET`, `ACCOUNT_SECURITY`, `ORDER_CANCELLED`, and `ORDER_SHIPPED` remain dormant until an authoritative account, cancellation, or shipment/tracking workflow exists. Optional notifications disabled by approved administration policy return an empty enqueue result and create no outbox row; suppression must never roll back the owning business transaction.

## Retry, Failover, and Idempotency

Retries are bounded exponential backoff with jitter and notification expiry. Network connection failures before transmission, documented rate limiting, and provider `5xx` responses are transient. Valid `Retry-After` seconds or HTTP dates set a minimum retry delay, and no retry is scheduled beyond notification expiry. Invalid addresses, unauthorized senders, malformed requests, authentication failures, and suppressions are permanent until configuration or recipient data changes.

Resend receives its documented `Idempotency-Key`. Brevo receives the notification identifier as a custom message header. Other providers receive the notification identifier through documented custom ID, tag, channel, postback, or header facilities where supported, but these are correlation aids rather than assumed deduplication guarantees. A provider response is accepted only when its documented message or transaction identifier is present; HTTP `2xx` alone is insufficient. An otherwise successful response without that identifier is quarantined as `OUTCOME_UNKNOWN`, except MailerSend's documented 202 all-recipients-suppressed response, which is permanent.

No circuit-breaker dependency is introduced initially. Persisted retries, ordered failover after known transient failures, and health metrics are adequate for current volume. A formal closed/open/half-open component is deferred until measured traffic or provider behavior justifies it.

## Provider Contract Baseline

Implementation follows current official documentation reviewed on 2026-08-15:

| Provider | Send API | Authentication | Accepted identifier | Webhook security |
| --- | --- | --- | --- | --- |
| Brevo | `POST https://api.brevo.com/v3/smtp/email` | `api-key` header | `messageId` | Dedicated unguessable endpoint secret, replay digest, and provider IP filtering at the edge |
| Resend | `POST https://api.resend.com/emails` | Bearer token | response `id` | Svix ID, timestamp, and signature over raw body |
| Mailjet | `POST https://api.mailjet.com/v3.1/send` | HTTP Basic API/secret keys | `MessageUUID` and `MessageID` | HTTPS plus dedicated Basic credentials and replay digest |
| MailerSend | `POST https://api.mailersend.com/v1/email` | Bearer token | `x-message-id` | `Signature` HMAC-SHA256 over raw body |
| Elastic Email | `POST https://api.elasticemail.com/v4/emails/transactional` | `X-ElasticEmail-ApiKey` | Message-scoped `MessageID` (`MsgID` in events); `TransactionID` is aggregate context only | Dedicated unguessable endpoint secret plus replay digest where signed delivery is unavailable |

Provider webhook capabilities differ. The shared layer records only normalized accepted, delivered, deferred, bounced, rejected, and complaint events; unsupported events remain absent rather than inferred. Reconciliation uses each provider's documented occurrence time (`ts_event`, `created_at`, `time`, or `EventDate`) with server receipt time as the fallback for missing, malformed, pre-epoch, or implausibly future values.

Current transactional messages support HTML and plain-text bodies and do not accept attachments. An attachment capability is intentionally not advertised or modeled until a business notification requires one; adding it requires bounded size/type rules, provider-specific mapping, durable payload storage, and tests across every enabled provider.

## Environment and Secret Safety

All API keys and webhook secrets are backend-only environment variables. Brevo, Resend, Mailjet, MailerSend, and Elastic Email each have independent enablement and credential variables. Every enabled provider must have complete send and authenticated-webhook credentials in UAT/PROD preflight, and PROD repeats that check during Spring startup. No key is committed, logged, returned by an API, or exposed through a `NEXT_PUBLIC_` variable. Provider connect and read deadlines are configurable and must both remain below the worker lease duration. Worker polling and webhook reconciliation delays are configurable positive bounds; production rejects zero or negative scheduler delays.

DEV uses a capture provider by default and makes no external delivery. UAT requires an explicit recipient allowlist and may override all recipients with a controlled mailbox. Production permits real recipients and fails startup when email is enabled but no valid provider or sender is configured. The default sender is `SHRESTA <no-reply@notify.shrestaexclusive.com>`.

Automated tests and readiness scripts never send real email. A live verification is a separate operator-controlled procedure performed only after automated gates pass. `scripts/verify-live-email-providers` requires a deliberate confirmation phrase, controlled recipient, sender, and complete credentials for every provider enabled in `.env.uat`. It follows the configured provider order and runs the tagged `liveEmailProviderTest` in one isolated process per enabled provider, enables exactly one adapter per process, enqueues one application notification, invokes one worker poll, and requires one `ACCEPTED` attempt plus a non-empty provider message ID. Operators must separately confirm the reported number of inbox messages and authenticated webhook final states because provider acceptance is not recipient delivery. A disabled provider is omitted from that run and remains unverified until enabled and tested separately.

## Security and Observability

OTP generation remains cryptographically secure and PostgreSQL remains authoritative for challenge hashes, expiry, attempts, lockout, and one-time consumption. Redis enforces an atomic pair cooldown plus independent rolling email/mobile request limits using hashed identity keys. Existing-identity requests return the same non-secret response shape without enqueueing a notification, and invalid/missing/expired verification attempts share one external error. OTP and fixture-login values are returned only under local/DEV profiles, never UAT. OTPs, reset tokens, full addresses, rendered bodies, and credentials are never logged or added to metric labels.

Metrics cover queue age, attempts, provider latency, retry, failover, final worker outcomes, webhook events, maintenance, and aged unknown outcomes. The Actuator `email` health component reports only enablement, configured provider codes, actionable queue count/age, and unknown-outcome count; it reports `DEGRADED` for stale work and never exposes credentials. Logs use notification ID, correlation ID, type, provider, attempt, and safe failure classes without recipient addresses.

## Old Implementation Migration

`CustomerEmailDeliveryService` and `CustomerEmailProperties` belong exclusively to synchronous SMTP and are deleted after callers use `EmailNotificationService`. SMTP keys and `spring-boot-starter-mail` are removed. `CustomerNotificationService` currently combines SMS and email; it is removed after callers use the new email abstraction and the existing SMS service directly. The SMS provider system and OTP challenge tables are unrelated retained functionality.

## Amazon SES Extension Procedure

Amazon SES is added as another adapter, not as a business-module change:

1. Add `email/provider/ses` containing `SesEmailProvider`, validated `SesProperties`, request/response mapping, error classification, and an SNS webhook controller with AWS signature and certificate validation.
2. Implement the existing `EmailProvider` contract. SES v2 `SendEmail` must return its message-scoped `MessageId`; missing IDs and ambiguous transport results remain `OUTCOME_UNKNOWN`.
3. Add `SHRESTA_EMAIL_SES_*` environment bindings, enablement, startup/readiness validation, and `SES` as an allowed `SHRESTA_EMAIL_PROVIDER_ORDER` value. Credentials use the standard AWS credential chain or workload role, never source configuration.
4. Normalize SES delivery, bounce, complaint, rejection, and delay notifications through `EmailWebhookService`. SNS notification IDs provide replay identity and SES message IDs provide outbox correlation.
5. Add isolated request, response, error, retry, routing, webhook-authentication, replay, and live-verification tests. Extend the guarded verifier by one provider only after all automated tests pass.

No order, payment, authentication, template, outbox, or worker code changes are required.

## Production Deployment Requirements

1. Apply Flyway migrations before serving traffic and verify `notification_configuration`, `notification_configuration_audit`, `email_outbox`, `email_delivery_attempts`, and `email_webhook_events` exist at their terminal component versions.
2. Export `SHRESTA_ENVIRONMENT_MODE=PROD`, `SHRESTA_EMAIL_ENABLED=true`, the verified sender, a nonempty unique provider order, and complete send plus webhook credentials for every enabled provider. Keys remain backend-only environment secrets.
3. Keep connect and read timeouts positive and below the worker lease. Keep retry, retention, polling, reconciliation, batch, and attempt values within the startup policy's positive bounds.
4. Run `sh scripts/verify-provider-readiness`. Deployment must stop on any failure; Spring repeats the production-critical checks during startup.
5. Route each provider webhook to its dedicated HTTPS endpoint. Preserve the raw body and signature headers, configure unguessable URL secrets where the provider has no signature mechanism, and apply provider IP filtering at the edge where documented.
6. Confirm PostgreSQL capacity for worker claims and retention, Redis availability for OTP abuse controls, and Prometheus collection of `shresta.email.*` metrics.
7. Verify Actuator email health, queue age, unknown-outcome count, and worker execution after deployment. Alert on `DOWN`, sustained `DEGRADED`, aged `OUTCOME_UNKNOWN`, repeated configuration failures, or provider failure-rate changes.
8. Perform the guarded one-message-per-provider UAT verification only with a controlled mailbox. Confirm inbox delivery and authenticated webhook terminal states manually before production activation.

## Known Limitations

- Exactly-once external delivery is impossible when a provider accepts a request but the network loses the response. Such outcomes are quarantined for reconciliation and are never automatically failed over.
- Provider acceptance is not recipient delivery. `PROVIDER_ACCEPTED` becomes delivered, bounced, rejected, or complaint only from an authenticated provider event.
- Brevo and Elastic Email do not supply a webhook signature mechanism used by this integration; they require unguessable endpoint secrets, replay digests, HTTPS, and edge filtering. This is weaker than Resend Svix or MailerSend HMAC verification.
- Attachments are intentionally unsupported until a business requirement defines durable storage and bounded size/type policy.
- Circuit breaking is deferred at current volume. Persisted retry, routing, and metrics remain the operational controls until measured failure patterns justify additional state.
- Dormant notification types have templates and configuration rows but emit nothing until an authoritative business workflow exists.

## Technology Decision Table

| Component | Decision | Why | Alternatives | Trade-offs | Future |
| --- | --- | --- | --- | --- | --- |
| Deployment | Existing modular monolith | Matches scale and ownership | Email microservice | Shared process resources | Extract behind application contract if measured need arises |
| Queue | PostgreSQL transactional outbox | Atomic with business writes | Kafka, RabbitMQ, Redis streams | Polling load | Broker can consume unchanged event contract later |
| Worker | Spring scheduler and JDBC claims | Existing runtime, low cost | External worker platform | Coarse polling latency | Separate deployment can reuse tables |
| Concurrency | `SKIP LOCKED` plus expiring leases | Multi-instance safe | In-memory locks | PostgreSQL-specific | Retained on RDS PostgreSQL |
| Templates | Thymeleaf HTML plus text resources | Escaping and reusable fragments | Inline strings, provider templates | Adds one dependency | Template versions remain application-owned |
| HTTP | Spring `RestClient` | Available in Spring 6, shared pooling via JDK client | Five provider SDKs | Explicit DTO maintenance | Replace inside one adapter without business changes |
| Retry | Persisted exponential backoff and jitter | Survives restarts | In-memory retry | More state transitions | Tune from metrics |
| Circuit breaker | Deferred | Current volume does not justify added state | Resilience4j | Slower automatic isolation | Add centrally from measured failures |
| Audit | Reviewed admin change request plus append-only configuration audit | Separates approval context from actual old/new database values | Mutable row metadata only | One additional insert per applied change | Generalize the immutable audit pattern where other configuration needs it |
| OTP | PostgreSQL challenge plus Redis abuse limits | Durable truth and fast throttling | Redis-only OTP | Two stores for separate concerns | Shared security rate-limit service |

## Consequences

Business requests no longer wait for provider APIs, and committed business changes cannot lose their email intent. Provider additions are physically isolated and require no business-module changes. The design adds database state, a worker, retention work, and operational monitoring. Ambiguous external outcomes remain a documented limitation and require reconciliation rather than unsafe automatic failover.