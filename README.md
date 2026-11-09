# InternTrack · Internship Application Tracker

A Java Android mobile application with a Java 21 serverless AWS backend. It implements the architecture described in the supplied CV, without replacing DynamoDB, S3, CloudFront or API Gateway with another service.

## Local setup

Copy the example Android settings before building or deploying:

```bash
cp android/gradle.properties.example android/gradle.properties
```

`android/gradle.properties` stays local and is ignored by Git. The demo needs no cloud settings; the online build needs your own AWS deployment outputs. Never commit generated connection settings or credentials.

## Features

- Cognito account sign-up/sign-in with PKCE and encrypted local token storage.
- Personal internship dashboard: company, role, job URL, status, application date, notes, and attached CV.
- Add, edit, delete and filter applications, with total/interview/offer counters.
- Select the date applied from a native calendar; it saves as `YYYY-MM-DD` automatically and can be cleared when unknown.
- A branded dashboard with fixed navigation, colour-coded status badges, grouped application forms and a document library showing CV versions, dates and file sizes.
- Upload PDF/DOCX/TXT CVs up to 10 MiB directly to **private** S3 using signed URLs.
- Tailor an existing CV to a stored job description using a backend-held OpenAI key.
- Generate an **editable DOCX**, preview the rewritten content, download it, or attach it to an application.
- Account name/email retrieval and public tips delivered through CloudFront.

The mandatory scope and live acceptance criteria are recorded in [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md). A local build does not satisfy the deployment requirement.

This source distribution omits credentials, live deployment identifiers, personal paths and raw verification artifacts. Deploy your own AWS stack and configure the Android client before using online features. The offline demo runs without cloud configuration. See [deployment instructions](docs/DEPLOYMENT.md).

## Try it on an Android phone without deploying

Use the **InternTrack Demo** build on Android 8.0 or later. It lets you create, edit, delete and filter applications, import PDF/DOCX/TXT CVs, attach them to applications, export copies through Android's file picker and read bundled application tips. Records and imported CVs survive closing and reopening the app.

```bash
(cd android && ./gradlew assembleDemo)
```

1. Transfer `android/app/build/outputs/apk/demo/app-demo.apk` to your phone, for example using USB file transfer.
2. Open the APK using the phone's Files app and install it. If Android prompts, allow that app to install this APK.
3. Open **InternTrack Demo**. Tap the example application to edit it, or **+ New application** to create your own. Open **CV library** to import a CV, then select it when editing an application. Tap a CV in the library to save a copy.

You can also select the **demo** build variant in Android Studio and run it on your [connected Android phone](https://developer.android.com/studio/run/device).

The demo displays an offline banner and uses the separate package `com.interntrack.demo`, so it can be installed alongside the cloud app. It does not sign in, sync account data or call OpenAI. The AI button explains that tailoring requires the connected service. Uninstalling the demo removes its private records and imported copies. The normal `assembleDebug` build continues to use Cognito, API Gateway, DynamoDB, S3, CloudFront and OpenAI; demo records are not automatically migrated.

## Directory map

```
android/      Native Java Android app (Android Studio project)
backend/      AWS Lambda Java 21 Maven application and unit tests
infra/        AWS SAM/CloudFormation template (including Cognito)
assets/       Public JSON fetched over CloudFront
scripts/      Deployment, Android configuration and smoke tests
docs/        Architecture and requirement mapping
```

## Requirements

- AWS account and CLI credentials (`aws sts get-caller-identity` should succeed).
- AWS SAM CLI, Maven 3.9+, JDK 21 for the Lambda backend.
- Android Studio with Android SDK 35 and an installed JDK 21 for the Java Android app. Gradle's daemon JVM criteria pin the Android build to Java 21.
- An OpenAI API key stored in **AWS Secrets Manager**, with enough API credits.

## 1 · Create the OpenAI secret

Create the secret without a value, then enter the key through the hidden terminal prompt:

```bash
aws secretsmanager create-secret \
  --name interntrack/openai-api-key --region us-east-1
python3 scripts/configure_openai_secret.py --region us-east-1 --account YOUR_AWS_ACCOUNT_ID
```

Copy the `ARN` from the create command's output. If the secret already exists, run only the Python helper. The helper checks the account and passes the key through an owner-only temporary file, deleted afterward; it does not put the key in shell history, command arguments or project files. The backend also accepts a JSON secret with an `OPENAI_API_KEY` field (as created in the AWS console). For AWS SSO profiles, configure the AWS CLI with `aws configure sso` and then `aws sso login --profile <profile>`.

## 2 · Deploy the exact AWS service architecture

From the project root:

```bash
chmod +x scripts/deploy.sh
./scripts/deploy.sh interntrack-dev unique-tracker-login-123 <secret-arn>
```

The second argument must be a **globally unique**, lowercase Cognito domain prefix (6-62 characters). The script runs `sam build`, deploys API Gateway, Lambda, Cognito, DynamoDB, S3, CloudFront, uploads `tips.json`, and writes the public deployment URLs/client ID into `android/gradle.properties`.

Alternative manual process:

```bash
sam build --template-file infra/template.yaml
sam deploy --guided --capabilities CAPABILITY_IAM
```

Then read CloudFormation Outputs, set `trackerApiUrl`, `trackerCognitoDomain`, `trackerCognitoClientId`, `trackerAssetBaseUrl` in `android/gradle.properties`, and upload `assets/tips.json` to the output `AssetBucketName` under key `tips.json`.

**Important:** Never put OpenAI credentials, IAM keys or Cognito client secrets in Gradle configuration. The mobile Cognito client must be public, as configured by SAM.

## 3 · Run the Android app

1. Open the `android/` folder as a project in Android Studio.
2. Install Android SDK platform 35 and JDK 21. The checked-in `android/gradle/gradle-daemon-jvm.properties` selects an installed Java 21 JDK for Gradle, including when the terminal defaults to another Java version.
3. Let Gradle sync (`com.android.application` 8.9.2). Run `./gradlew assembleDebug` from `android/` to build the cloud client; the included Gradle wrapper downloads its configured distribution (currently 9.6.0) if needed.
4. Select an Android 8.0+ emulator/device and press **Run**.
5. Tap **Sign in**, create an account, and verify the email address through Cognito Hosted UI.
6. Upload an original CV, add an application with job description, then tap **Tailor CV with AI**.

The project includes the standard Gradle wrapper scripts and wrapper JAR. A separate system Gradle installation is not required.

### Build error mentioning Java 26 and `jlink`

This project's Android SDK image transformation failed when Gradle ran with Java 26. Java 21 builds passed. A one-command `JAVA_HOME=...` prefix changes only that command; the next plain `./gradlew` invocation otherwise returns to the terminal's default Java. The project now uses [Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria) to select Java 21 automatically.

Install JDK 21 on your machine. Run `./gradlew --version` from `android/` to inspect the launcher and daemon JVM selection. Then use `./gradlew assembleDebug` for the cloud client or `./gradlew assembleDemo` for the offline demo.

## API

### Online build shows a connection notice

The checked-in settings use `REPLACE` placeholders. Deploy the backend, configure the four connection properties locally, then build and install your own `app-debug.apk`. No APK is included in this repository.

Until those settings are configured, the dashboard shows **Online service not connected**. New application, CV library, Sign in and Refresh explain the missing setup and offer **Open offline demo**. If InternTrack Demo is installed, that action opens its separate application; otherwise the app explains that the demo must be installed. It does not bypass cloud authentication or store online records in the demo.

All routes are protected by API Gateway's Cognito JWT authorizer and require `Authorization: Bearer <access_token>`.

| Method | Route | Purpose |
|---|---|---|
| GET | `/dashboard` | DynamoDB application records and persisted account name/email |
| GET | `/me` | Persisted account profile, initialized from Cognito on first access |
| GET, POST | `/applications` | List/create applications |
| GET, PUT, DELETE | `/applications/{id}` | Read/update/delete a saved application |
| GET | `/cvs` | List uploaded and generated CVs |
| POST | `/cvs/upload-url` | Request time-limited signed S3 upload URL |
| POST | `/cvs/{id}/complete` | Verify upload and create CV metadata |
| GET | `/cvs/{id}/download-url` | Get time-limited signed download URL |
| POST | `/applications/{id}/tailor` | Queue CV tailoring and return HTTP 202 with `jobId` |
| GET | `/tailoring-jobs/{id}` | Read progress, errors or the generated DOCX CV reference |

CV extraction and OpenAI requests run in a separate Lambda worker rather than holding an API Gateway connection open. The Android client polls for completion, retains the pending job between visits and lets users review the output before attaching it. Duplicate worker delivery does not repeat the AI call. Job records expire after one day; generated CVs remain in the library.

## Testing

```bash
python3 scripts/run_feature_tests.py --serial emulator-5580
# Include deployed services (requires AWS login and scripts/requirements-test.txt):
python3 scripts/run_feature_tests.py --serial emulator-5580 --live --account YOUR_AWS_ACCOUNT_ID
```

Dedicated tests now cover the application API, CV processing/storage, Android security and screens, asynchronous AI workflows, deployment helpers and actual AWS/native-client integration. The latest full run has **134 passing checks**, **one real-AI check blocked by credits**, and **one email-confirmation check not run**. See [the feature matrix and repeatable commands](docs/TESTING.md) and [the generated results](docs/test-results/SUMMARY.md). Controlled responses are identified separately from live results; they do not establish successful OpenAI generation. Test users and their data are removed afterward. The included summary records a previous run; raw logs and deployment screenshots are excluded from this source distribution.

## Design scope

The application has been exercised against deployed AWS services; those private deployment details are omitted here. The recorded real-AI acceptance check remains blocked by API credits. Deploy and validate your own environment using [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md); see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for CV traceability, and [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) for acceptance criteria.
