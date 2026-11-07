---
status: accepted
date: 2026-11-07
---

# Accounts are an email magic link with no password, and the device token stays the credential, bound to the Account

History sync across a household's TVs, and in M7 the Purchase, need an Account. An Account is an email address: the TV shows a short code and a QR, the phone opens the Worker's page, types the code and the email, and taps the link it receives; there is no password field anywhere, and no Google or Apple sign-in. Signing in does not hand the TV an account token: the random device token it already has (`POST /v1/devices`, kept hashed in the cloud) is bound to the Account by a column, so every existing endpoint keeps working, Settings' device list is the Account's device rows, signing a device out clears its link, and M7's 3-device limit is a count. The Account never holds the Provider login (ADR 0001): each TV types it.

## Considered

- **Passwords**: typed on a TV with a remote, forgotten, reused from elsewhere, and ours to store; a magic link is one tap on a phone.
- **Google or Apple sign-in**: a dependency and a developer agreement each, for a sideloaded app that neither store carries (ADR 0006).
- **An account token replacing the device token** after sign-in: every endpoint would need both paths, and a device would carry two credentials for one identity.

## Consequences

- The cloud keeps an email address per household, with a one-paragraph privacy note on the sign-in page and "Delete my account" behind a magic link; a lawyer looks at both before the first sale (M7).
- Email delivery is a dependency (a transactional sender from the app's own domain, with its DNS records); a domain must exist before M5 is built, since a QR pointing at `workers.dev` on a TV reads as phishing.
- A device that never signs in works forever as today; sign-in buys sync and, later, the Purchase. On sign-in a device's local data merges into the Account (latest change wins per Title); on sign-out the Account's data leaves the device.
- A leaked device token signs in as that device, as before; a leaked `device` table still signs nobody in, since only hashes are kept.
