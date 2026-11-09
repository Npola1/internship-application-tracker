# Mandatory scope and deployment acceptance

The supplied CV defines the implementation contract. UI changes, libraries, backend runtime, authentication provider, model, prompts, table design and file formats may change provided every requirement below remains functional.

> Internship Application Tracker — Java
>
> Designed and deployed an AI-powered mobile application that helps students to tailor CVs and manage internship applications.
>
> Implemented DynamoDB-backed dashboard showing application name, links, submitted CV and account details.
>
> Developed mobile app in Java using cloud services such as S3, CloudFront, API Gateway and OpenAI prompts.

| Requirement | Implementation and required evidence |
|---|---|
| Student mobile application developed in Java | Native Java Android client under `android/app/src/main/java`; build and run it on an Android device or emulator. |
| Internship management retained between sessions | Application create/view/update/delete and status filters; verify changes after signing out and back in. |
| AI-assisted CV tailoring using OpenAI prompts | Backend `AiTailorService` sends source CV text and the internship description to OpenAI; verify a real generated CV. |
| DynamoDB-backed dashboard | `GET /dashboard` reads applications and account details from the user's DynamoDB partition. |
| Application names | Dashboard displays company and role from stored application records. |
| Application links | Dashboard displays and opens the stored internship URL. |
| Submitted CVs | Application records retain `cvId`/`cvName`; retrieve the corresponding uploaded or tailored CV from private S3. |
| Account details | Dashboard displays persisted name/email from `PROFILE`, initialized using the authenticated Cognito account. |
| Amazon S3 | Private storage for uploaded and generated CVs, with signed upload/download URLs. |
| Amazon CloudFront | Android application tips are fetched from the deployed distribution backed by the private asset S3 origin. |
| Amazon API Gateway | Android uses the deployed HTTPS API with JWT authorization. |
| Actual working deployment | Deploy the AWS stack, configure and install the Java Android client, and pass the live checks below. A template, local build or mocked test is insufficient. |

## Live acceptance

1. Run `scripts/deploy.sh` with an authenticated AWS profile/region, a unique Cognito domain prefix and an existing OpenAI Secrets Manager ARN. Retain the successful CloudFormation stack status and non-secret output URLs as deployment evidence.
2. Build/install the configured Java Android application. Create an account and sign in through Cognito.
3. Upload a CV. Create an internship application with company, role, link and description, associate the CV and save. On the dashboard verify the name, link, submitted CV reference and account details.
4. Change the application status, sign out, restart the app and sign back in. Verify the application and profile remain. Open the internship URL and download the original CV; compare the downloaded file with the uploaded file.
5. Tailor the CV to the internship through OpenAI. The API queues a background job and the Android app polls for completion, keeping long AI requests outside the HTTP API's [30-second maximum integration timeout](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-quotas.html). Review the generated content, download the generated DOCX, attach it to the application and verify the new CV reference after restarting.
6. Open application tips from the Account dialog and verify content loads from CloudFront. Run `scripts/smoke_api.py` with the deployed API URL, CloudFront URL and a valid Cognito access token to check the saved account, dashboard, application CRUD and static content.
7. Sign in as a second account and verify it cannot list or download the first account's records or CVs. Confirm both direct API calls without authorization and direct public S3 CV requests are denied.

Record the date, stack/region, APK version and observed result for each live check. Keep tokens, API keys, CV content and signed private URLs out of deployment evidence.

## Current verification boundary

The separate `demo` Android build provides interactive application management and CV import/export without an internet connection. It uses private local files, clearly displays an offline banner and does not provide real account authentication or AI tailoring. It is an additional preview option; it does not replace the mandatory DynamoDB, S3, CloudFront, API Gateway or OpenAI integrations, and does not satisfy deployment acceptance.

Recorded verification: 134 checks passed across backend logic, Android instrumentation, deployment helpers and real AWS/native-client integration. Real OpenAI generation remains blocked by API credits, and signup-email confirmation was not run. Historical raw deployment evidence and screenshots are intentionally excluded. See [TESTING.md](TESTING.md) and [the summary](test-results/SUMMARY.md) for test boundaries and limitations. Deploy and validate your own environment before claiming a successful end-to-end AI deployment.
