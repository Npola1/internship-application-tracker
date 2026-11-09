# Internship Application Tracker

A Java Android app that helps students manage internship applications and tailor CVs to job opportunities using OpenAI. The dashboard brings together application details, links, statuses, submitted CVs and account information.

## Architecture

The mobile client uses Amazon Cognito for authentication and calls Amazon API Gateway, which routes requests to a Java Lambda backend.

| Component | Responsibility |
|---|---|
| DynamoDB | Application records, account profiles, CV metadata and job state |
| Private S3 | Original and tailored CV files, accessed through signed URLs |
| Lambda worker + OpenAI | Asynchronous CV tailoring; the app polls for progress and presents results for review |
| Secrets Manager | Backend-only storage of the OpenAI API key |
| CloudFront + separate S3 origin | Delivery of public application tips |

AWS SAM defines the cloud infrastructure. A separate offline demo stores records locally without authentication or AI calls.

## System design goals

The design aims for a secure, maintainable mobile application with clear separation between the interface, authentication, persistent data and AI processing. User-partitioned records and private document storage protect account boundaries. Background jobs keep long AI requests outside the API request lifecycle, while managed AWS services support scaling without maintaining servers. Students review generated CVs before attaching them to applications.

## Getting started

Build the offline demo:

```bash
cp android/gradle.properties.example android/gradle.properties
cd android
./gradlew assembleDemo
```

For the connected app, follow the [deployment guide](docs/DEPLOYMENT.md). Live settings and credentials are excluded from this repository.

See the [architecture details](docs/ARCHITECTURE.md) and [test coverage](docs/TESTING.md). The recorded suite has 134 passing checks; real AI generation remains blocked by API credits, and signup-email confirmation remains unverified.
