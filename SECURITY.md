# Security Policy

## Supported Versions

| Version | Support |
|---------|---------|
| v1.5+   | ✅ Active |
| v1.4    | ⚠️ Critical fixes only |
| < v1.4  | ❌ End of life |

## Reporting a Vulnerability

**Bitte keine Sicherheitslücken öffentlich melden.**

1. Schreibe eine E-Mail an `harrymachura6@gmail.com` mit Betreff `[Crisix Security]`
2. Beschreibe das Problem mit reproduzierbaren Schritten
3. Wir antworten innerhalb von 72 Stunden
4. Du erhältst Credit im Changelog (wenn gewünscht)

## Encryption

Crisix verwendet **X3DH** für den initialen Schlüsselaustausch und
**Double Ratchet** für Forward Secrecy. Alle Nachrichten werden
mit **AES-256-GCM** (Hardware-backed KeyStore) verschlüsselt.

- **Keine Schlüssel** werden jemals geloggt
- **Keine Nachrichteninhalte** werden unverschlüsselt persistiert
- Transport-Fallback kann ohne Re-Handshake erfolgen

## Reportable Issues

- E2EE-Schlüssel-Leakage / Entropy-Probleme
- Unsicherer Zufallszahlengenerator in der Krypto
- Denial-of-Service über Relay/DHT
- Metadaten-Leak durch Transport-Timing

## Nicht reportable

- "Feature X fehlt" → Issue-Template "Feature Request"
- "App stürzt ab" → Issue-Template "Bug Report"
- Code-Style, Dokumentation → normale Issues/PRs
