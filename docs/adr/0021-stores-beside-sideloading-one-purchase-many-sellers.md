---
status: accepted
date: 2026-11-15
---

# Listed in stores beside sideloading, with one Purchase whoever sold it

Samsung TVs and iPhones take apps from their store and nowhere else, so Zugig is submitted to stores, and stays sideloadable wherever the platform allows it. This amends ADR 0006, which chose sideloading alone. Stores differ on payment (checked 2026-11-15 against each store's published rules), so the Seller differs per build while the Purchase stays one per Account at one price, enforced by the cloud as in ADR 0013:

| Build | Seller |
|---|---|
| iPhone, iPad, Mac App Store | Apple's in-app purchase. Apple forbids Unlocking by a code or QR, and honours a Purchase made elsewhere only when the app also sells it in-app. |
| Sideloaded Android, Windows, Linux, Mac download | Paddle |
| Google Play | None. The build signs in to an Account and never mentions buying, which Play allows as a consumption-only app. |
| Samsung | Open: Samsung's pages don't say whether its checkout is mandatory. Ask its seller support before building the Unlock screen there. |

## Consequences

- An Apple sale nets less than a Paddle one (15% against about 5%), at the same price.
- The cloud runs a Demo Provider speaking the Xtream API, since reviewers fail an app they can't test and may ask for proof of rights. Store screenshots and each platform's automated tests use it too.
- A bare player is submitted to Apple and Samsung early, to learn whether review passes before much is built.
- The Amazon Appstore is skipped: it demands its own payment with no consumption-only route, and Fire TV is served by the sideloaded APK and its updater.
- Stores have pulled IPTV players before. The sideloaded builds and the Account are what a household keeps if a listing is removed.
- The Trial stays one per device. Each platform sends the most durable ID it may read (on Apple, a token in the keychain) and the cloud records the platform beside it.
