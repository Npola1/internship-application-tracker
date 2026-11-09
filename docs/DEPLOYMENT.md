# Deployment and local configuration

The published source excludes the original deployment identifiers, credentials, account details, local machine paths, raw logs, screenshots and built APKs. Deploy a separate AWS environment to use online features.

## Prerequisites

Install JDK 21, Maven 3.9+, AWS CLI, AWS SAM CLI, and Android SDK 35. Authenticate AWS locally. Create an OpenAI API key and store it only in AWS Secrets Manager; never add it to source, Gradle properties or Android resources.

```bash
cp android/gradle.properties.example android/gradle.properties
aws sts get-caller-identity
aws secretsmanager create-secret --name interntrack/openai-api-key --region us-east-1
python3 scripts/configure_openai_secret.py --region us-east-1 --account YOUR_AWS_ACCOUNT_ID
```

The helper accepts the key at a hidden prompt and transfers it using a private temporary file that is removed afterward. Keep the secret ARN local.

## Deploy and configure

```bash
./scripts/deploy.sh interntrack-dev YOUR_UNIQUE_COGNITO_DOMAIN_PREFIX YOUR_OPENAI_SECRET_ARN
```

The script deploys API Gateway, Lambda, Cognito, DynamoDB, private S3 CV storage and a CloudFront distribution for public tips. It uploads the tips and writes the four non-secret deployment settings to `android/gradle.properties`. Do not commit that generated configuration. If configuring manually, use the stack Outputs for `trackerApiUrl`, `trackerCognitoDomain`, `trackerCognitoClientId` and `trackerAssetBaseUrl`.

## Build and install

From `android/`:

```bash
./gradlew assembleDebug
./gradlew assembleDemo
```

The online APK is `app/build/outputs/apk/debug/app-debug.apk`. The offline APK is `app/build/outputs/apk/demo/app-demo.apk`. Transfer your chosen APK to an Android 8.0+ phone and install it. The online build uses Cognito signup/sign-in; the demo uses local data and has no real AI generation.

## Verification boundary

The recorded feature suite passed 134 checks. Real AI generation was blocked by insufficient OpenAI API credits, and signup-email confirmation was not exercised. Controlled provider responses test processing and UI behaviour, not successful real generation. Physical Samsung-specific behaviour remains unverified. See [TESTING.md](TESTING.md) and [the test summary](test-results/SUMMARY.md).

Run the live tests only against an account you own, with an explicit account ID and an isolated emulator. Disposable test users and their files are removed afterward. Do not publish newly generated logs, signed CV links, authentication fixtures, screenshots, CV files or build outputs.
