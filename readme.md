# Mail by Anoop Bhatia

An independent Android email client fork based on [FairEmail](https://github.com/M66B/FairEmail), with native REST API integrations for Gmail, Zoho Mail, and Microsoft Graph.

This repository is for inspecting, building, and adapting the source. It is not an official FairEmail release, and prebuilt APKs are not committed here.

For upstream context, see the preserved [FairEmail README](assets/fairemail_readme.md) and [attribution](assets/fairemail_attribution.md).

## Features

- **Gmail REST API:** Synchronize messages and labels, read message content and attachments, send mail, and perform supported mailbox actions.
- **Zoho Mail REST API:** Synchronize folders and messages, read content and attachments, send mail, and perform supported mailbox actions.
- **Microsoft Graph REST API:** Synchronize folders and messages, handle message content and attachments, send mail, and perform supported message operations.
- **Existing mail transports:** IMAP and POP account paths remain available, with SMTP sending where supported. Native Gmail REST is an additional option and does not replace Gmail IMAP.
- **Shared mail-client behavior:** Provider integrations use the app’s existing account, folder, message, and background-operation infrastructure.

Capabilities vary by provider and operation. Native API synchronization uses polling; webhooks are not implemented. Initial sync fetches up to 10 messages per sync-enabled folder, and Fetch more loads 10 older messages per request.

## What differs from upstream

This fork removes or simplifies some inherited distribution and app features, including billing and store-specific build variants, the in-app update checker, some announcement/onboarding/report/rating links or menus, and some generic OAuth choices. Some language resources, tutorials, and inherited documentation or privacy-page content were also removed. This is a summary of changes recorded in this repository’s history, not a complete comparison with every upstream release.

## Modernization highlights

- JDK 25, Gradle 9.7.1, Android Gradle Plugin 9.4.1, Kotlin 2.4.21, Android SDK 37, NDK `30.0.16248370`, and CMake 4.4.4. Minimum Android version remains API 28.
- Lifecycle Extensions was replaced by individual Lifecycle modules (2.11.0); the project uses RxJava 3.1.12, Paging 3.5.3, and Room 2.8.5.
- CLD3’s native dependency stack uses Protobuf 36.2, C++ runtime 7.36.2, and Abseil 20250512.1, with regenerated vendor code.
- Deprecation diagnostics remain visible; warnings are not suppressed.

See [`MODERNIZATION.md`](MODERNIZATION.md) for the full status and remaining limitations.

## Source map

The Android package is `in.anoopbhatia.mail`; app source is under `app/src/main/java/in/anoopbhatia/mail/`.

- Gmail: `GmailApi.java`, `GmailReceiver.java`, `GmailMailMutator.java`
- Microsoft Graph: `GraphMailApi.java`, `GraphMailReceiver.java`, `GraphMailMutator.java`
- Zoho Mail: `ZohoApi.java`, `ZohoReceiver.java`, `ZohoMailMutator.java`
- Shared API credentials: `ApiCredentials.java`
- Provider and OAuth callback definitions: `app/src/main/res/xml/providers.xml`

Release application ID: `in.anoopbhatia.mail`

Debug application ID: `in.anoopbhatia.mail.debug`

## Configure OAuth for your own build

Create your own provider OAuth registrations. In `app/src/main/res/xml/providers.xml`, edit the `<api>` entries identified by `key="gmail"`, `key="graph"`, and `key="zoho"` (currently around lines 4, 13, and 19; line numbers may change):

- **Gmail, line 4:** `clientId`, `debugClientId`, `redirectUri`, and `debugRedirectUri`.
- **Microsoft Graph, line 13:** `clientId`, `redirectUri`, and `debugRedirectUri`.
- **Zoho, line 19:** `clientId`, `clientSecret`, and `redirectUri`.

If you also use Gmail or Microsoft OAuth over IMAP, update the matching provider-level `<oauth>` entry in that same XML. Make each callback match the package ID and build variant you use. The optional Zoho `zohoAuthorizationServer` can be set as a Gradle property or in the root `local.properties` file.

Use your own registrations; don’t commit private credentials. OAuth values included in an Android app can be extracted from its APK, so an embedded client secret cannot be treated as confidential.

## Build a local debug APK

Install JDK 25, Android SDK Platform 37, NDK `30.0.16248370`, and CMake 4.4.4. Set `sdk.dir` in the untracked root `local.properties` to your Android SDK path, then run from the repository root:

```bash
./gradlew :app:assembleDebug
```

The APK is generated under `app/build/outputs/apk/debug/`. Run the unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

Available build types are Debug, Release, and Large. Large uses the Release configuration with a larger heap. Debug has the `.debug` application ID and Debug OAuth callbacks.

### Optional signing

For local signing, set these Gradle properties in your user-level `~/.gradle/gradle.properties` and keep the keystore and file out of Git:

```properties
storeFilePath=/path/to/your/keystore.p12
keyAlias=YOUR_KEY_ALIAS
storePassword=YOUR_KEYSTORE_PASSWORD
```

The current build uses `storePassword` for both the keystore and key password. Without the signing properties, the APK may be unsigned.

For GitHub Actions signing, add these repository secrets under Settings → Secrets and variables → Actions:

- `KEYSTORE_BASE64` — your keystore encoded as Base64.
- `KEY_ALIAS` — your signing key alias.
- `KEYSTORE_PASSWORD` — the keystore password, also used as the key password by the current build.

Use only a signing key you own. Never commit the keystore or passwords.

## License

This project is licensed under the GNU General Public License v3. See [`LICENSE`](LICENSE). FairEmail-derived code and third-party components are credited in the app’s [attribution file](app/src/main/assets/ATTRIBUTION.md); third-party components remain subject to their own licenses.

## Credits

- **Author:** [Anoop Bhatia](https://github.com/anoopspeaks)
- **Tools used:**
  - <a href="https://cue.im/"><img src="assets/kern-badge.jpg" width="36" height="36" alt="Kern badge" align="middle">&nbsp;Cue by Manus</a>
  - <a href="https://freebuff.com/"><img src="assets/freebuff.jpg" width="24" height="24" alt="Freebuff" align="middle">&nbsp;Freebuff</a>
