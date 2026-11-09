# Architecture and CV traceability

The reference CV states: Java mobile application, AI-powered CV tailoring, DynamoDB-backed dashboard containing application name, links, submitted CV and account details, and S3, CloudFront, API Gateway, OpenAI prompts.

An additional `demo` Android build (`com.interntrack.demo`) supports on-device preview independently of deployment. Its application records and imported CVs are saved in private local storage, and its tips are bundled assets. The demo is labelled on every screen and does not simulate sign-in or OpenAI tailoring. It does not satisfy the CV's AWS integration/deployment claims. The normal cloud build retains the architecture below.

```text
 Android (Java)
   | OAuth 2.0 authorization-code + PKCE
   v
 Amazon Cognito User Pool
   | verified Cognito access token
   v
 Amazon API Gateway HTTP API (JWT authorizer)
   |
   v
 AWS Lambda API (Java 21)
   |------> DynamoDB (applications, CV metadata, account profiles, user-partitioned)
   |------> S3 private CV bucket (signed upload/download URLs)
   |------> asynchronous Lambda worker (Java 21)
   |             |----> DynamoDB job state and CV metadata
   |             |----> S3 original and tailored DOCX files
   |             |----> Secrets Manager (plain-text or JSON OpenAI key)
   |             +----> OpenAI Responses API (tailoring prompt)
   |
   +------> Cognito GetUser (initial account email/name, then persisted in DynamoDB)

 Android (Java) ----> S3 (time-limited signed PUT/GET URLs only)
 Android (Java) ----> CloudFront HTTPS ----> private S3 static asset bucket
                                        (public application tips only)
```

## CV requirement mapping

| CV claim | Working implementation |
|---|---|
| Mobile app built in Java | Native Android Java activities and XML layouts (`android/app/src/main`) |
| AI helps tailor CVs | `POST /applications/{id}/tailor` returns a job ID; `GET /tailoring-jobs/{id}` reports progress and output; a Java worker extracts PDF/DOCX/TXT text, calls OpenAI and stores an editable DOCX |
| Manage internship applications | Java Android dashboard with CRUD, status filters and note fields |
| DynamoDB-backed dashboard | `GET /dashboard`; single-table partition key `USER#{CognitoSub}` with `APP#`, `CV#` and `PROFILE` sort keys |
| Application name and links | `company`, `role`, `jobUrl` fields |
| Submitted CV | `cvId` and `cvName` on application; S3 private CV object |
| Account details | Name/email displayed on the dashboard from a DynamoDB `PROFILE` record, initialized through Cognito GetUser on first `/me` or `/dashboard` access |
| Amazon S3 | Private documents and a separate static asset bucket |
| Amazon CloudFront | Caches public `tips.json`; never exposes private CVs |
| Amazon API Gateway | HTTPS API with Cognito JWT authorizer |
| OpenAI prompts | Backend-only Responses API with verified user-provided CV text and job description |
| Designed and deployed | AWS stack `interntrack-dev` deployed in `us-east-1`; connected APK rebuilt and live tracking/storage checks passed. OpenAI secret is configured; real AI acceptance awaits API credits and a successful generation; see [deployment evidence](DEPLOYMENT.md). |

`GET /dashboard` returns `account` and `applications` from persistent records. Profile and application reads are strongly consistent so a refresh includes completed writes. Cognito remains the authentication provider; the stored profile is a snapshot of name/email at first access. Later Cognito attribute changes are not automatically synchronized. The bootstrap uses the [GetUser access-token flow](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetUser.html); DynamoDB reads use [strong consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html).

Tailoring uses `JOB#` records under the verified user's partition. The API invokes a separate worker asynchronously and returns HTTP 202. A conditional state transition prevents duplicate event delivery from repeating the AI request. The Android app polls, keeps the pending job ID between visits and shows the preview when complete. Job metadata has a one-day TTL; generated CV files and their references persist independently. Long model calls therefore do not hold the API Gateway connection open.

## Security constraints

- Only the backend holds IAM permissions to DynamoDB, S3, and Secrets Manager.
- API Gateway validates the JWT, Lambda derives the user partition from verified `sub` claim, and all records are partitioned by that identifier.
- OpenAI key is a Secrets Manager value (never embedded in Android).
- S3 CV bucket denies public access; individual signed URLs expire after 10-15 minutes.
- CloudFront serves only public, non-user-specific static content from an independent bucket via origin access control.
- Uploads are limited to 10 MiB, formats are allowlisted, and upload completion checks actual S3 object metadata.
- The OpenAI request uses `store: false` and prompt constraints against inventing experience. Users still review output.
- No OCR is provided; a scanned PDF without selectable text is rejected by the extractor.

## Scaling and operational notes

- DynamoDB on-demand billing and provisioned-free Lambda scaling support many users without a database server.
- Cost applies to DynamoDB, S3, Lambda/API Gateway, CloudFront, Cognito and OpenAI usage.
- Application and CV records persist. There is no automatic CV deletion UI or scheduled cleanup of incomplete upload objects.
- Account deletion and data export workflows, antivirus scanning, rate limits, observability and data retention policies should be added before public release.
- Deployment identifiers and account-specific evidence are excluded from this source distribution. The recorded real-generation check was blocked by API credits; successful generation still needs verification. Continuous CI is not configured.
