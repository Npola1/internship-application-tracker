#!/usr/bin/env python3
"""Store the OpenAI key securely in the deployment account without command-line/history exposure."""
import argparse
import getpass
import json
import re
import subprocess
import sys
import tempfile


def store_key(command, secret_id, key):
    # AWS CLI on this Mac cannot read --cli-input-json from /dev/stdin.
    # NamedTemporaryFile is owner-only (0600) and deleted on success or failure.
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', prefix='interntrack-secret-', suffix='.json') as config:
        json.dump({'SecretId': secret_id, 'SecretString': key}, config)
        config.flush()
        result = subprocess.run(command + ['secretsmanager', 'put-secret-value', '--cli-input-json', 'file://' + config.name],
                                stdin=subprocess.DEVNULL, text=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if result.returncode:
            # CLI parsing errors can include input values; never echo raw stderr.
            code = re.search(r'An error occurred \(([A-Za-z0-9_.-]+)\)', result.stderr or '')
            reason = code.group(1) if code else 'AWS CLI exit ' + str(result.returncode)
            raise SystemExit('Could not confirm that the key was stored (' + reason + '). Check your AWS login and permissions, then retry.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region', default='us-east-1')
    parser.add_argument('--account', required=True, help='Expected AWS account ID')
    parser.add_argument('--secret-id', default='interntrack/openai-api-key')
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise SystemExit('Run this script in an interactive terminal so the API key can be entered without echo.')
    command = ['aws', '--no-cli-pager', '--no-cli-auto-prompt', '--region', args.region]
    identity = subprocess.run(command + ['sts', 'get-caller-identity', '--query', 'Account', '--output', 'text'],
                              capture_output=True, text=True, check=True).stdout.strip()
    if identity != args.account:
        raise SystemExit('AWS is signed into account ' + identity + ', not the expected account ' + args.account)
    key = getpass.getpass('OpenAI API key (input hidden): ').strip()
    if not key or '\n' in key or '\r' in key:
        raise SystemExit('Enter a non-empty API key on a single line.')
    store_key(command, args.secret_id, key)
    print('OpenAI key stored in account ' + identity + ', secret ' + args.secret_id + '. No key was written to the project.')


if __name__ == '__main__':
    main()
