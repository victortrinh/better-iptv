---
status: accepted
date: 2026-11-06
---

# Paid features are enforced by the cloud, not the APK

The repository and the APK are public (ADR 0010), so a paid-feature check inside the app would be removed in one commit by anyone who builds their own. Instead, what a Purchase buys is exactly what already runs through the cloud (Game schedules, subtitle search and download, shared Header reads), and the Worker refuses those endpoints to a device that isn't Unlocked. Everything that runs on the device alone stays free.

## Considered

- A flag in the app, honour system: free to anyone willing to build the APK, which on a sideloading platform is the audience.
- A private repository with a client-side gate: hides the code without making the gate real, and gives up the public releases the updater reads.

## Consequences

- A feature can be paid only if it needs the cloud. A good on-device feature can't be sold, and that is a constraint on the roadmap, not a bug.
- A Locked feature's teaser is what the cloud still serves to a free device (League counts, subtitle offers); the act it refuses is the paid part. The sports hub's Games themselves are the teaser's limit, since Playing a Feed is on-device.
- A fork gets the paid features only by running its own Worker with its own API keys and subtitle accounts, which is the cost the Purchase was covering.
