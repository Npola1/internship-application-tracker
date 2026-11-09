# Dedicated feature tests

The recorded verification run contains **134 passing checks, one blocked check and one check not run**. There are no remaining test failures. Full acceptance remains **PARTIAL** because a real OpenAI-generated CV is blocked by the API credit balance, and real signup-email delivery/confirmation has not been exercised.

Raw logs, screenshots and live deployment identifiers are excluded from this source distribution. New reports should remain local. Read the [generated summary](test-results/SUMMARY.md) or the [per-test JSON results](test-results/summary.json). These reports distinguish actual deployed-service tests from controlled HTTP/AWS responses. A successful test of an error screen is not a successful AI generation.

## Feature coverage

| Feature | Dedicated tests | Boundary and latest result |
|---|---|---|
| Create, view, edit and delete applications | `ApplicationApiTest`, `DemoUiTest`, `LiveFeatures`, `LiveClientTest` | Java API, real Android screens and real AWS calls: PASS |
| All seven application statuses | Parameterized `ApplicationApiTest`, dashboard filter/counter tests, live status transitions | Every status stored, filtered and reflected in counters: PASS |
| Dashboard names, links, CV references and account details | `DashboardTest`, live CRUD/dashboard test, native dashboard test | Required categories read from real DynamoDB-backed responses: PASS |
| Persistence between sessions | Repository-instance, screen reopening, encrypted-session, live OAuth refresh and DynamoDB checks | Local persistence and deployed persistence: PASS |
| Job links and navigation | `DemoUiTest` link, overview, CV-library, account and refresh tests | Android intent target and visible navigation: PASS |
| Date applied | Dedicated create/save, January zero-padding, cancel, clear and edit tests | Native date picker plus persisted ISO dates: PASS |
| Original CV upload/import and library | `CvServiceTest`, `DemoRepositoryTest`, real document-picker result tests, native live upload | PDF/DOCX/TXT; supported/unsupported names; empty/oversize/mismatched uploads; library metadata: PASS |
| Private CV retrieval/export | Signed-URL tests, actual picker export byte comparison, S3 live download, native viewer test | Bytes retained; owned CV opens; public and cross-account access denied: PASS |
| CV attachment and detachment | API tests, Android editor tests, native live CRUD | CV references and names persisted; saving during a CV-library outage preserves the attachment; deleting an application keeps the CV: PASS |
| PDF/DOCX/TXT text extraction | `CvServiceTest` generates actual documents, including a DOCX table | Actual parsers; blank scanned PDF/OCR guidance; malformed files; input limits: PASS |
| OpenAI request and response processing | `AiTransportTest`, `AiTailorServiceTest` | Controlled provider HTTP responses; factual prompt, source/job inputs, `store:false`, output handling and safe errors: PASS |
| Async worker, job isolation and duplicate delivery | `TailoringTest`, real live job test | Queuing, timeout, user ownership, duplicate suppression and failure preservation: PASS |
| Pending-job recovery and network failures | `AsyncTailoringUiTest` | Real Java activities, ApiClient, poller and preferences with controlled HTTP responses; recreation/resume and manual status check: PASS |
| AI preview, review, download and attachment controls | `AsyncTailoringUiTest` | Controlled completed-job response through the actual UI; original stays attached until confirmation: PASS |
| Generated DOCX validity and storage | `CvServiceTest`, `TailoringTest` | Actual DOCX generation/parser and S3 boundary substitute: PASS |
| **Real OpenAI CV generation, download and attachment** | `LiveFeatures.test_real_ai_docx_generation_download_and_attachment` | **BLOCKED:** real provider returns `credit_balance_exhausted`; no successful AI output claimed |
| Authentication and access gates | `SessionSecurityTest`, `OnlineGatingTest`, live Cognito tests | PKCE/state/callback, cancellation, token refresh, signed-out access, invalid tokens and user isolation: PASS |
| Secure token storage and sign-out | `SessionSecurityTest` | Actual Android Keystore; randomized ciphertext, restartable store, corruption/tampering rejection, clearing saved session: PASS |
| Signup form and email-verification settings | Live signup/configuration test | Actual Cognito form and pool settings: PASS |
| **Signup-email delivery and user confirmation** | Dedicated live test registered with a manual skip | **NOT RUN:** requires an authorized recipient inbox and its verification code; no email was sent |
| CloudFront, S3, API Gateway and DynamoDB integration | Dedicated live service tests plus native live client tests | Actual configured services, private origin, HTTPS policy, persisted records and authorization: PASS |
| Offline demo and its honest AI limitation | `DemoRepositoryTest`, `DemoUiTest` | Local CRUD/CV persistence and clear online-only AI guidance; no simulated AI result: PASS |
| Required status colours and readable metadata | `PresentationTest` | Actual Android badge colours, labels, date and file-size formatting: PASS |
| Deployment settings and secret-entry helper | Python helper tests | Missing settings, repeated configuration, account/TTY checks, private temporary-file transport, cleanup and redaction: PASS |

## Suites and commands

First copy `android/gradle.properties.example` to `android/gradle.properties`. Configure your own deployed outputs before running online instrumentation or live suites.

Prerequisites: JDK 21, Maven 3.9+, Android SDK 35/build tools, the project Gradle wrapper, and Python 3.9+. Start a separate test emulator. The Android runner deliberately requires an `emulator-...` serial because its tests reset synthetic demo data and authentication state. It does not select a physical Samsung phone.

Run the local and Android suites from the project root, with Maven on `PATH`:

```bash
python3 scripts/run_feature_tests.py --serial emulator-5580
```

For the deployed-service and native Android live checks, install the test dependencies in a Python virtual environment and authenticate AWS:

```bash
python3 -m venv .venv-tests
.venv-tests/bin/pip install -r scripts/requirements-test.txt
aws login --profile default
.venv-tests/bin/python scripts/run_feature_tests.py --serial emulator-5580 --live --account YOUR_AWS_ACCOUNT_ID
```

Set `MAVEN` to a Maven executable if it is not on `PATH`; set `JAVA_HOME` to an installed JDK 21 for Maven. Gradle already pins its daemon to Java 21. The SDK/ADB can be supplied through `ANDROID_HOME` or `ANDROID_SDK_ROOT`.

Individual suites:

```bash
mvn -f backend/pom.xml clean test
python3 -m unittest discover -s scripts/tests -v
(cd android && ./gradlew testDemoUnitTest)
python3 scripts/run_android_tests.py --serial emulator-5580 --variant debug
python3 scripts/run_android_tests.py --serial emulator-5580 --variant demo
python3 scripts/run_android_tests.py --serial emulator-5580 --suite async
.venv-tests/bin/python scripts/test_live_features.py --account YOUR_AWS_ACCOUNT_ID
.venv-tests/bin/python scripts/test_live_android.py --serial emulator-5580 --account YOUR_AWS_ACCOUNT_ID
```

The native live wrapper requires the current debug app and debug test APK installed; running the preceding Android core/async commands does that. Controlled async responses run in their own instrumentation process and require an explicit runner flag. They never replace the backend in the normal app. Live-client tests likewise require an explicit disposable-user fixture; ordinary instrumentation discovery does not execute them accidentally.

`run_feature_tests.py` exits **0** for fully passing requested coverage, **1** for failed tests/builds, and **2** for incomplete verification. The current full run correctly exits 2. Missing evidence, blocked AI and manual email checks never count as passes. `--collect-only --live` rebuilds the summary from the existing evidence without making requests or resetting emulator data.

## Findings corrected

1. Malformed Base64 request bodies returned HTTP 500. The handler now returns HTTP 400 with a safe validation message. The dedicated regression test passes, and the fix was deployed successfully before the final live service run.
2. The deployment-output writer silently left settings absent when their property lines were missing. It now adds missing public settings, retains unrelated content, rejects incomplete outputs before writing and stays idempotent. Dedicated tests cover each case.
3. Saving an existing application while the CV library failed to load silently removed its submitted CV. The editor now retains the stored CV reference until the library loads and a selection can be made. The dedicated regression failed before the fix and passes afterward; both APK variants were rebuilt.

The additional HTTP-client constructor in `AiTailorService` is an injection seam for provider tests. The public production constructor retains the existing real OpenAI client, timeout and model settings.

## Limits and test-data handling

The Android checks ran on an isolated API 37 emulator. They do not establish Samsung-specific hardware behaviour, every supported Android version, load capacity or a complete security/accessibility audit.

The live tests create random disposable Cognito users with invitations suppressed. They exercise only those users' partitions and S3 prefixes, then remove their records, accounts and versioned files. The native wrapper passes a test user's tokens through ADB stdin into private emulator storage; the instrumentation consumes and deletes the fixture and clears its encrypted session afterward. Keys, real tokens, passwords and signed CV URLs are excluded from reports.

OpenAI credits are required to turn the blocked real-generation test into a pass. Signup-email delivery needs a separate authorized recipient and confirmation flow. Neither limitation is hidden by controlled-response tests.

Android instrumentation uses the official [AndroidX Test libraries](https://developer.android.com/jetpack/androidx/releases/test), installed only in test APKs.
