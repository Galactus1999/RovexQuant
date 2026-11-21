# Security

- Never commit broker, market-data or AI keys.
- Android secrets must use Android Keystore-backed encryption; no plaintext preferences, logs, analytics or backups.
- Backend secrets via environment or managed secret store; redact headers and tokens.
- AI keys are optional per provider. Minimize evidence sent and disclose provider routing.
- Broker tokens must be revocable and follow official OAuth/session requirements.
- No order-placement scopes or order endpoints in this project.
