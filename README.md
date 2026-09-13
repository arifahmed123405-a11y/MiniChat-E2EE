# MiniChat E2EE (Android)

A deliberately small 1-to-1 Android messenger where message/file content is encrypted on-device before it reaches Supabase.

## v0.1 features

- Native Kotlin + Jetpack Compose
- Email/password auth via Supabase Auth
- Search contacts by unique handle
- End-to-end encrypted text messages
- Encrypted arbitrary file attachments (MVP cap: 25 MB)
- Sender + recipient encrypted copies so both sides can read the thread
- ECDSA signatures with the private signing key held in Android Keystore
- Trust-on-first-use fingerprint pinning; changed keys block incoming decrypts until accepted
- Private Supabase Storage bucket stores ciphertext only
- Android system picker uses `*/*`
- Received files decrypt into app cache then open through Android FileProvider / ACTION_VIEW
- GitHub Actions builds `app-debug.apk`

## Cryptography

- Message/key encapsulation: Google Tink HPKE `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM`
- Attachment data: fresh Tink AES-256-GCM key per file
- Sender authenticity: Android Keystore P-256 ECDSA / SHA-256
- HPKE private keyset is encrypted locally with an AES-256-GCM key stored in Android Keystore
- Server stores public keys, ciphertext, routing IDs, timestamps, signatures and encrypted blobs — not plaintext message/file contents.

This is an MVP, not an audited Signal replacement. It does **not** yet implement the Signal Double Ratchet, multi-device key sync, disappearing messages, metadata hiding, push notifications, or large-file streaming encryption.

## 1. Supabase

Create a fresh project and run:

`supabase/schema.sql`

For easiest testing, Auth -> Providers -> Email: disable **Confirm email**. Re-enable a proper verification flow before public deployment.

Copy:

- Project URL
- Publishable/anon key

Never put the Supabase service-role key in the Android app.

## 2. Local build

Create `~/.gradle/gradle.properties` or pass environment variables:

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLISHABLE_OR_ANON_KEY
```

Then build with Gradle 8.9 / JDK 17:

```bash
gradle :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## 3. GitHub APK build

Add repository secrets:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`

Push to `main`. GitHub Actions uploads an artifact named `minichat-debug-apk`.

## Security notes

The first key seen for a contact is pinned locally (TOFU). If that contact's server-advertised key changes later, MiniChat blocks received decryption and requires the user to explicitly trust the new fingerprint. For stronger identity verification, compare fingerprints out-of-band; QR safety-number verification is planned next.

A lost/uninstalled phone currently means a lost E2EE identity. This is intentional in v0.1 rather than uploading private keys to the server.
