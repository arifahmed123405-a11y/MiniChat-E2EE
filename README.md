# MiniChat E2EE v1.0

MiniChat is a compact native Android 1-to-1 messenger built with Kotlin + Jetpack Compose. Message/file contents are encrypted on the phone before they are uploaded.

## Product features

- Email/password authentication through Supabase Auth
- Existing shared-Supabase users can finish MiniChat onboarding by choosing a handle
- Unique `@handle` discovery
- Chats inbox with encrypted last-message previews and unread counts
- End-to-end encrypted text messages
- End-to-end encrypted arbitrary file attachments up to 25 MB
- Reply-to-message metadata kept inside the encrypted payload
- Sender-side sent / delivered / read state
- Contact blocking
- Local “delete for me”
- Open received files with any installed Android app that supports the decrypted MIME type
- Share a decrypted received file through Android’s share sheet
- Password-reset request
- Own/contact security fingerprints
- Trust-on-first-use contact key pinning; changed keys are blocked until explicitly trusted
- Automatic Supabase session refresh
- GitHub Actions APK build

## Cryptography

- Message encryption: Google Tink HPKE using X25519 + HKDF-SHA256 + AES-256-GCM
- Attachment encryption: fresh Tink AES-256-GCM key per attachment
- Sender authentication: Android Keystore P-256 ECDSA / SHA-256
- HPKE private keyset: encrypted locally with an AES-256-GCM KEK held in Android Keystore
- Attachment keyset and filename/MIME metadata travel only inside the HPKE-encrypted message payload

The server stores ciphertext plus routing metadata needed to operate the service. It can see account IDs, who is talking to whom, timestamps, ciphertext sizes, delivery/read timestamps and encrypted blob paths. It cannot read message text, filenames, attachment contents or attachment keys.

MiniChat v1 is **not** a Signal protocol implementation. It does not yet provide a Double Ratchet, forward secrecy per message, sealed-sender metadata hiding, multi-device key sync or encrypted cloud key backup.

## Backend

This repository is configured to share the existing EarnWall Supabase project while keeping MiniChat data isolated in:

- `public.minichat_profiles`
- `public.minichat_messages`
- `public.minichat_blocks`
- private Storage bucket `minichat-files`

The schema is in `supabase/schema.sql`.

Because Supabase Auth is project-wide, EarnWall and MiniChat share the same `auth.users` identity pool. Their application tables remain separate.

Row Level Security is enabled on every MiniChat table. Anonymous users have no access. Message update permission is column-limited to `delivered_at` and `read_at`, so clients cannot modify ciphertext, sender IDs or recipient IDs after insertion.

## Client configuration

`gradle.properties` contains the Supabase Project URL and **publishable** client key. A publishable key is intended for client applications; RLS is the authorization boundary.

Never put a Supabase secret/service-role key in the APK or repository.

## Build

Requirements used by CI:

- JDK 17
- Gradle 8.9
- Android SDK 35
- Android Build Tools 35.0.0

Build:

```bash
gradle --no-daemon :app:lintDebug :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Every push to `main` runs `.github/workflows/android.yml` and uploads the APK as a GitHub Actions artifact.

## Single-device identity rule

The private E2EE identity lives in Android Keystore / app-private storage. Uninstalling the app or clearing its data destroys that identity. Reinstalling creates a new identity, contacts will see a security-key change, and messages encrypted to the old device identity cannot be recovered.

That behavior is intentional for v1 rather than weakening E2EE by silently uploading private keys.
