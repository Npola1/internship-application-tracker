# Keeping deployment data private

This repository contains source and example configuration. The original deployment details, account identifiers, personal paths, raw logs, screenshots, CVs, APKs and local credential/configuration files are excluded.

The tracked `android/gradle.properties.example` contains placeholders. Copy it to the ignored `android/gradle.properties` before building. The deployment helper writes your own public endpoint/client settings to that local file. Do not force-add generated settings. No OpenAI key belongs in the mobile client: use Secrets Manager and backend IAM access.

Review staged changes before every push. Do not commit CV documents, signed URLs, access/refresh tokens, AWS credentials, private signing keys, email addresses or generated live-test artifacts. Keep secret scans enabled when available. If an actual credential is exposed, revoke or rotate it; editing a later commit does not remove it from history.
