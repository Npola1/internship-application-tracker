# Feature test results

Overall: **PARTIAL**. Counts: 1 BLOCKED, 1 NOT_RUN, 134 PASS.

PASS means the named check passed at its stated test boundary. Controlled-response tests do not establish real AI generation.

| Layer | Passed | Failed/errors | Blocked/not run |
|---|---:|---:|---:|
| backend | 53 | 0 | 0 |
| android_unit | 7 | 0 | 0 |
| python_helpers | 18 | 0 | 0 |
| android_online | 15 | 0 | 0 |
| android_demo | 18 | 0 | 0 |
| android_async_controlled | 7 | 0 | 0 |
| live_aws | 11 | 0 | 2 |
| android_live | 5 | 0 | 0 |

## Checks needing external verification

- __main__.LiveFeatures.test_real_ai_docx_generation_download_and_attachment: BLOCKED: AI tailoring is unavailable because the app has no OpenAI API credits. The app owner needs to add credits.
- __main__.LiveFeatures.test_signup_email_delivery_and_user_confirmation: MANUAL: Requires an authorized recipient inbox and its verification code; no test email is sent

See [TESTING.md](../TESTING.md) for the feature matrix, test boundaries and repeatable commands.
