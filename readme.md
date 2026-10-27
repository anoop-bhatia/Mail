# FairEmail Native REST API Project

This repository contains source-code changes that add native REST-based mail-provider integrations to FairEmail. It is intended for users who want to inspect the code, build it themselves, or adapt it for their own use. It is **not an official FairEmail release**, and this project does not separately distribute a FairEmail app or prebuilt app package.

The upstream project's original root README is preserved as [`readme_fairemail.md`](readme_fairemail.md).

## Implemented functionality

- **Gmail REST API:** Native Gmail mailbox synchronization, including labels and messages; message content and attachment handling; sending; and supported mailbox actions.
- **Zoho Mail REST API:** Native Zoho Mail synchronization for folders and messages, message content and attachment handling, sending, and supported mailbox actions.
- **Microsoft Graph REST API:** Native Graph-backed folder and message synchronization, message and attachment handling, and supported message operations, including sending.
- **Shared mail-client behavior:** Provider-specific account handling is integrated with FairEmail's existing account, folder, message, and background-operation infrastructure.
- **Existing transports retained:** Existing IMAP and POP account paths remain available, including Gmail-hosted IMAP for accounts configured to use IMAP. The native Gmail REST path is additional; it does not replace those existing paths.

Exact capabilities and behavior depend on the provider and the operation supported by its API. The source tree also includes tests for provider-specific and shared functionality.

## OAuth setup and credential ownership

These integrations use provider OAuth rather than asking for a mailbox password for REST access. For a personal build or adaptation, create and manage OAuth registrations in accounts and developer consoles that you control. Existing client IDs in source identify a public OAuth application; a client ID is not a password. Never commit access or refresh tokens, client secrets, signing keys, or private keystores.

Native Android applications are public clients and cannot keep a confidential client secret safe if it is bundled in the APK. Match each provider registration to the application ID, redirect/callback URI, account type, and signing certificate used by the build you are actually running.

### Gmail

1. In a Google Cloud project you control, enable the Gmail API and configure the OAuth consent screen and intended audience/test users.
2. The native Gmail flow uses Google Identity Services in [`GmailAuthorizationGms.java`](app/src/gms/java/eu/faircode/email/GmailAuthorizationGms.java). Its requested grants are defined in [`GmailScopes.java`](app/src/main/java/eu/faircode/email/GmailScopes.java): `gmail.readonly`, `gmail.modify`, `gmail.send`, and `https://mail.google.com/`.
3. Google classifies `https://mail.google.com/` as a **restricted** scope. This project uses it for operations that permanently delete Gmail messages without first moving them to Trash. Public distribution using restricted Gmail data may require Google's verification and other policy steps; review the current requirements before distributing a build.
4. Google Android authorization can depend on the app's package name and signing-certificate SHA-1. Check the signing report for the exact build variant with `./gradlew :app:signingReport`. If the debug keystore is renewed or replaced, update the relevant Google registration with the new fingerprint.
5. The browser-based authorization alternative is implemented in [`GmailWebOAuth.java`](app/src/main/java/eu/faircode/email/GmailWebOAuth.java) and wired from [`FragmentGmailApi.java`](app/src/main/java/eu/faircode/email/FragmentGmailApi.java). Review its public client and redirect configuration against Google's current supported flow when adapting the project. Do not add a confidential client secret to the app.

Official guidance: [Gmail API scopes and verification](https://developers.google.com/workspace/gmail/api/auth/scopes), [Google OAuth consent configuration](https://developers.google.com/workspace/guides/configure-oauth-consent), [OAuth for installed apps](https://developers.google.com/identity/protocols/oauth2/native-app), and [Android signing-certificate fingerprints](https://developers.google.com/android/guides/client-auth).

### Microsoft Graph

1. Register an app in the [Microsoft Entra admin center](https://entra.microsoft.com/) and choose supported account types that match the authority used by the FairEmail OAuth profile.
2. The native Graph client ID, redirect URI, and authority are defined in [`GraphOAuth.java`](app/src/main/java/eu/faircode/email/GraphOAuth.java). For your own registration, configure the Android platform and exact package/signature-hash redirect for the build you use, then update the matching source configuration. The current native profile uses the `consumers` authority; adapt the authority and registration if you need a different Microsoft account audience, such as work or school accounts.
3. The Graph OAuth profile includes `Mail.Send` and `offline_access`; [`FragmentOAuth.java`](app/src/main/java/eu/faircode/email/FragmentOAuth.java) adds delegated `Mail.ReadWrite` for the REST flow. Microsoft documents that `Mail.ReadWrite` does not itself grant send permission, so `Mail.Send` is separate. Tenant policies may require user or administrator consent.
4. Do not put a confidential client secret in this Android client. Treat OAuth tokens as private user credentials.

Official guidance: [Register an app](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app), [configure an Android redirect](https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-mobile-app-android-prepare-app), [supported account types](https://learn.microsoft.com/en-us/entra/identity-platform/v2-supported-account-types), and [Microsoft Graph permissions](https://learn.microsoft.com/en-us/graph/permissions-reference).

### Zoho Mail

1. Register an application in the [Zoho API Console](https://api-console.zoho.com/). Use the callback URI shown by the FairEmail account setup flow and register it exactly. This source defaults to `eu.faircode.email://oauth2redirect`; the app validates the callback scheme for the build.
2. The Zoho setup flow requests a client ID, client secret, and redirect URI. The current implementation uses authorization code with PKCE, but it also requires the client secret during code exchange and refresh. Zoho notes that native/mobile clients are public clients and cannot keep a client secret confidential; do not commit or publish account credentials, and review this design before distributing a wider build.
3. Requested Mail API permissions are defined in [`ZohoOAuth.java`](app/src/main/java/eu/faircode/email/ZohoOAuth.java): account, folder, and message read; message create, update, and delete; and folder update. The authorization response supplies regional API information. The build defaults to `https://accounts.zoho.com`; source builds can override `zohoAuthorizationServer` locally for another supported Zoho data center.

Official guidance: [Register an OAuth app](https://www.zoho.com/developer/oauth/register-app.html), [mobile/desktop OAuth and PKCE](https://www.zoho.com/developer/oauth/mobile-and-desktop-apps/overview.html), and [Zoho Mail OAuth](https://www.zoho.com/mail/help/api/using-oauth-2.html).

## Source map

The provider implementations are under `app/src/main/java/eu/faircode/email/`:

- **Gmail:** [`GmailApi.java`](app/src/main/java/eu/faircode/email/GmailApi.java), [`GmailReceiver.java`](app/src/main/java/eu/faircode/email/GmailReceiver.java), [`GmailMailMutator.java`](app/src/main/java/eu/faircode/email/GmailMailMutator.java), and the authorization files linked above.
- **Microsoft Graph:** [`GraphMailApi.java`](app/src/main/java/eu/faircode/email/GraphMailApi.java), [`GraphMailReceiver.java`](app/src/main/java/eu/faircode/email/GraphMailReceiver.java), [`GraphMailMutator.java`](app/src/main/java/eu/faircode/email/GraphMailMutator.java), and [`GraphOAuth.java`](app/src/main/java/eu/faircode/email/GraphOAuth.java).
- **Zoho Mail:** [`ZohoApi.java`](app/src/main/java/eu/faircode/email/ZohoApi.java), [`ZohoReceiver.java`](app/src/main/java/eu/faircode/email/ZohoReceiver.java), [`ZohoMailMutator.java`](app/src/main/java/eu/faircode/email/ZohoMailMutator.java), and [`ZohoOAuth.java`](app/src/main/java/eu/faircode/email/ZohoOAuth.java).
- **Shared account/OAuth setup:** [`EmailProvider.java`](app/src/main/java/eu/faircode/email/EmailProvider.java), [`FragmentOAuth.java`](app/src/main/java/eu/faircode/email/FragmentOAuth.java), and [`app/build.gradle`](app/build.gradle).
- **Regression tests:** `app/src/test/java/eu/faircode/email/`.

## Synchronization and privacy

- Initial synchronization fetches up to **10 messages per folder with synchronization enabled**.
- **Fetch more** loads **10 older messages per request**.
- API updates rely entirely on polling. **No webhooks are implemented**, for privacy reasons.

## Build a local debug APK

Install Android Studio with the Android SDK and a JDK supported by this repository's Gradle wrapper. From the repository root, use the included wrapper:

```bash
./gradlew :app:assembleGithubDebug
```

The APK is produced under `app/build/outputs/apk/github/debug/`. To run the local unit tests:

```bash
./gradlew :app:testGithubDebugUnitTest
```

To inspect signing information for OAuth registration, including the debug certificate fingerprint, run:

```bash
./gradlew :app:signingReport
```

On Windows, use `gradlew.bat` in place of `./gradlew`. The debug application ID has the `.debug` suffix, so provider package/redirect registrations must match the variant you build.

## Project scope and caveats

This is a **code-only source project** for inspection, source builds, and user adaptation. It is not presented as an official FairEmail release, and no separate app distribution is provided here. OAuth consent, provider verification, account eligibility, tenant policy, and API limits remain subject to the provider. Available operations differ across services; consult the provider documentation and corresponding code/tests rather than assuming that every provider has identical behavior.

Use and modification remain subject to the applicable project and upstream licenses. See [`LICENSE`](LICENSE).

- **Author:** [Anoop Bhatia](https://github.com/anoopspeaks)
- <strong>Tool used:</strong> <img src="assets/kern-badge.jpg" width="48" height="48" alt="Kern badge" align="middle"> <a href="https://cue.im/">Cue by Manus</a>
