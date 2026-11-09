#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/deploy.sh my-tracker my-unique-cognito-domain arn:aws:secretsmanager:...
if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <stack-name> <globally-unique-cognito-domain-prefix> <openai-secret-arn>" >&2
  exit 1
fi
STACK="$1"; DOMAIN="$2"; OPENAI_SECRET_ARN="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for command in aws sam python3; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing tool: $command" >&2
    exit 1
  fi
done
aws sts get-caller-identity --output json >/dev/null
aws secretsmanager describe-secret --secret-id "$OPENAI_SECRET_ARN" --output json >/dev/null
sam build --template-file infra/template.yaml
sam deploy --stack-name "$STACK" --resolve-s3 --capabilities CAPABILITY_IAM \
  --parameter-overrides "AuthDomainPrefix=$DOMAIN" "OpenAISecretArn=$OPENAI_SECRET_ARN" \
  --no-confirm-changeset

OUTPUTS="$(aws cloudformation describe-stacks --stack-name "$STACK" --query 'Stacks[0].Outputs' --output json)"
export TRACKER_OUTPUTS="$OUTPUTS"
python3 scripts/write_android_config.py
ASSET_BUCKET="$(python3 - <<'PY'
import json, os
items = {x['OutputKey']: x['OutputValue'] for x in json.loads(os.environ['TRACKER_OUTPUTS'])}
print(items['AssetBucketName'])
PY
)"
aws s3 cp assets/tips.json "s3://$ASSET_BUCKET/tips.json" --content-type application/json --cache-control max-age=300
printf '\nDeployment complete. Android configuration written to android/gradle.properties.\n'
