#!/usr/bin/env python3
"""Live acceptance with disposable Cognito users; does not print passwords, tokens or signed URLs.

Requires boto3 and requests. Creates users with invitations suppressed, exercises the
same OAuth client as Android, then removes only its own test records/files/accounts.
Use --tailor to include a real OpenAI generation.
"""
import argparse
import base64
import hashlib
import io
import json
import secrets
import time
import zipfile
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

import boto3
import requests
from boto3.dynamodb.conditions import Key


class Forms(HTMLParser):
    def __init__(self):
        super().__init__()
        self.forms = []
        self.current = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'form':
            self.current = {'action': attrs.get('action', ''), 'fields': {}}
            self.forms.append(self.current)
        elif tag == 'input' and self.current is not None and attrs.get('name'):
            self.current['fields'][attrs['name']] = attrs.get('value', '')

    def handle_endtag(self, tag):
        if tag == 'form':
            self.current = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stack-name', default='interntrack-dev')
    parser.add_argument('--region', default='us-east-1')
    parser.add_argument('--tailor', action='store_true')
    parser.add_argument('--report', default='docs/deployment-verification.json')
    args = parser.parse_args()
    session = boto3.Session(region_name=args.region)
    cloudformation = session.client('cloudformation')
    stack = cloudformation.describe_stacks(StackName=args.stack_name)['Stacks'][0]
    outputs = {item['OutputKey']: item['OutputValue'] for item in stack['Outputs']}
    api, domain, client_id = outputs['ApiBaseUrl'], outputs['CognitoHostedUiBase'], outputs['CognitoClientId']
    cognito = session.client('cognito-idp')
    s3 = session.client('s3')
    table = session.resource('dynamodb').Table(outputs['TrackerTableName'])
    pool = outputs['UserPoolId']
    users, checks = [], []

    def passed(name):
        checks.append(name)
        print('PASS:', name, flush=True)

    def request(method, path, token=None, body=None):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        response = requests.request(method, api + path, headers=headers, json=body, timeout=30)
        if response.status_code >= 400:
            raise RuntimeError(f'{method} {path} returned HTTP {response.status_code}: ' + response.json().get('message', ''))
        return response.json()

    def login(email, password):
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        state = secrets.token_urlsafe(20)
        callback = 'interntrack://oauth2redirect'
        parameters = {'client_id': client_id, 'response_type': 'code', 'redirect_uri': callback,
                      'scope': 'openid email profile aws.cognito.signin.user.admin', 'state': state,
                      'code_challenge_method': 'S256', 'code_challenge': challenge}
        browser = requests.Session()
        browser.headers['User-Agent'] = 'Mozilla/5.0 InternshipApplicationTrackerDeploymentVerification'
        page = browser.get(domain + '/oauth2/authorize', params=parameters, timeout=30)
        page.raise_for_status()
        forms = Forms()
        forms.feed(page.text)
        form = next((form for form in forms.forms if 'username' in form['fields']), None)
        if form is None:
            raise RuntimeError('Cognito did not provide the expected sign-in form')
        fields = dict(form['fields'])
        fields.update(username=email, password=password)
        response = browser.post(urljoin(page.url, form['action'] or page.url), data=fields,
                                allow_redirects=False, timeout=30)
        location = ''
        for _ in range(8):
            location = response.headers.get('Location', '')
            if location.startswith(callback):
                break
            if not location:
                raise RuntimeError(f'Cognito sign-in did not redirect successfully (HTTP {response.status_code})')
            destination = urljoin(domain, location)
            if urlparse(destination).netloc != urlparse(domain).netloc:
                raise RuntimeError('Unexpected OAuth redirect destination')
            response = browser.get(destination, allow_redirects=False, timeout=30)
        query = parse_qs(urlparse(location).query)
        if query.get('state') != [state] or not query.get('code'):
            raise RuntimeError('OAuth response did not contain a matching state and authorization code')
        response = browser.post(domain + '/oauth2/token', data={
            'grant_type': 'authorization_code', 'client_id': client_id, 'redirect_uri': callback,
            'code': query['code'][0], 'code_verifier': verifier}, timeout=30)
        response.raise_for_status()
        return response.json()

    def create_user():
        email = 'deployment-' + secrets.token_hex(8) + '@example.invalid'
        password = 'Aa1!' + secrets.token_urlsafe(24)
        result = cognito.admin_create_user(UserPoolId=pool, Username=email, MessageAction='SUPPRESS',
            UserAttributes=[{'Name': 'email', 'Value': email}, {'Name': 'email_verified', 'Value': 'true'},
                            {'Name': 'name', 'Value': 'Deployment test student'}])
        user = {'email': email, 'username': result['User']['Username'], 'sub': next(
            item['Value'] for item in result['User']['Attributes'] if item['Name'] == 'sub')}
        users.append(user)
        cognito.admin_set_user_password(UserPoolId=pool, Username=user['username'], Password=password, Permanent=True)
        user['tokens'] = login(email, password)
        return user

    def clean_user(user):
        partition = 'USER#' + user['sub']
        cursor = None
        while True:
            query = {'KeyConditionExpression': Key('pk').eq(partition), 'ConsistentRead': True}
            if cursor:
                query['ExclusiveStartKey'] = cursor
            result = table.query(**query)
            with table.batch_writer() as writer:
                for item in result.get('Items', []):
                    writer.delete_item(Key={'pk': item['pk'], 'sk': item['sk']})
            cursor = result.get('LastEvaluatedKey')
            if not cursor:
                break
        prefix = 'users/' + user['sub'] + '/'
        for page in s3.get_paginator('list_object_versions').paginate(Bucket=outputs['CvBucketName'], Prefix=prefix):
            objects = [{'Key': item['Key'], 'VersionId': item['VersionId']}
                       for item in page.get('Versions', []) + page.get('DeleteMarkers', [])]
            if objects:
                response = s3.delete_objects(Bucket=outputs['CvBucketName'], Delete={'Objects': objects, 'Quiet': True})
                if response.get('Errors'):
                    raise RuntimeError('Could not remove all verification CV versions')
        cognito.admin_delete_user(UserPoolId=pool, Username=user['username'])

    try:
        assert requests.get(api + '/dashboard', timeout=30).status_code == 401
        passed('API Gateway denies unauthenticated access')
        for attempt in range(12):
            response = requests.get(outputs['CloudFrontAssetsUrl'] + '/tips.json', timeout=20)
            if response.status_code == 200 and response.json().get('tips'):
                break
            if attempt == 11:
                raise RuntimeError('CloudFront did not serve application tips')
            time.sleep(5)
        passed('CloudFront delivers tips from the private S3 origin')
        alice = create_user()
        token = alice['tokens']['access_token']
        passed('Cognito authorization-code login with PKCE on the Android client')
        me = request('GET', '/me', token)
        assert me['userId'] == alice['sub'] and me['email'] == alice['email']
        assert request('GET', '/dashboard', token)['account'] == me
        passed('Account profile is initialized and read through the DynamoDB dashboard')
        original = (b'DEMO STUDENT\nComputer Science Student\nSKILLS\nJava, Git, unit testing\n'
                    b'PROJECTS\nBuilt an internship application tracker in Java.\n'
                    b'EDUCATION\nStudying Computer Science.\n')
        upload = request('POST', '/cvs/upload-url', token, {'name': 'Deployment-CV.txt', 'size': len(original)})
        response = requests.put(upload['uploadUrl'], data=original, headers={'Content-Type': upload['contentType']}, timeout=30)
        response.raise_for_status()
        cv = request('POST', '/cvs/' + upload['id'] + '/complete', token, {})
        download = request('GET', '/cvs/' + cv['id'] + '/download-url', token)
        assert requests.get(download['url'], timeout=30).content == original
        public_url = f"https://{outputs['CvBucketName']}.s3.{args.region}.amazonaws.com/{cv['objectKey']}"
        assert requests.get(public_url, timeout=30).status_code == 403
        passed('Private S3 CV upload, metadata persistence and byte-identical download')
        app = request('POST', '/applications', token, {
            'company': 'Deployment Test Employer', 'role': 'Java Software Engineering Intern',
            'jobUrl': 'https://example.org/internships/java', 'status': 'Saved', 'appliedDate': '2026-11-08',
            'notes': 'Disposable deployment verification record', 'cvId': cv['id'],
            'jobDescription': 'Java software engineering internship. Build Java applications, use Git and write unit tests.'})
        app['status'] = 'Interview'
        app = request('PUT', '/applications/' + app['id'], token, app)
        assert app['status'] == 'Interview' and app['cvName'] == 'Deployment-CV.txt'
        refresh = requests.post(domain + '/oauth2/token', data={'grant_type': 'refresh_token',
            'client_id': client_id, 'refresh_token': alice['tokens']['refresh_token']}, timeout=30)
        refresh.raise_for_status()
        token = refresh.json()['access_token']
        saved = next(item for item in request('GET', '/dashboard', token)['applications'] if item['id'] == app['id'])
        assert saved['cvId'] == cv['id'] and saved['jobUrl'] == app['jobUrl'] and saved['company'] == app['company']
        passed('Application CRUD and all dashboard categories persist across token refresh')
        bob = create_user()
        for path in ('/applications/' + app['id'], '/cvs/' + cv['id'] + '/download-url'):
            response = requests.get(api + path, headers={'Authorization': 'Bearer ' + bob['tokens']['access_token']}, timeout=30)
            assert response.status_code == 404
        passed('Separate Cognito users cannot retrieve each others applications or CVs')
        if args.tailor:
            started = time.monotonic()
            job = request('POST', '/applications/' + app['id'] + '/tailor', token, {'cvId': cv['id']})
            assert job.get('jobId') and time.monotonic() - started < 25
            passed('Tailoring is queued without waiting for OpenAI inside API Gateway')
            while time.monotonic() - started < 240:
                result = request('GET', '/tailoring-jobs/' + job['jobId'], token)
                if result['status'] == 'FAILED':
                    raise RuntimeError('AI tailoring failed: ' + result.get('message', 'unknown error'))
                if result['status'] == 'COMPLETED':
                    break
                time.sleep(3)
            else:
                raise RuntimeError('AI tailoring did not finish in the verification window')
            tailored = result['tailoredCv']
            link = request('GET', '/cvs/' + tailored['id'] + '/download-url', token)
            document = requests.get(link['url'], timeout=30)
            document.raise_for_status()
            with zipfile.ZipFile(io.BytesIO(document.content)) as archive:
                assert b'Java' in archive.read('word/document.xml')
            app['cvId'] = tailored['id']
            request('PUT', '/applications/' + app['id'], token, app)
            saved = request('GET', '/applications/' + app['id'], token)
            assert saved['cvId'] == tailored['id'] and saved['cvName'] == tailored['name']
            passed('Real OpenAI tailoring produces a downloadable DOCX that can be attached and retrieved')
        request('DELETE', '/applications/' + app['id'], token)
        assert all(item['id'] != app['id'] for item in request('GET', '/dashboard', token)['applications'])
        passed('Application deletion updates the persistent dashboard')
    finally:
        errors = []
        for user in users:
            try:
                clean_user(user)
            except Exception as error:
                errors.append(type(error).__name__)
        if errors:
            raise RuntimeError('Verification cleanup needs attention: ' + ', '.join(errors))
    passed('Disposable accounts, records and versioned CV files removed')
    report = {'verifiedAt': datetime.now(timezone.utc).isoformat(), 'stackName': args.stack_name,
              'region': args.region, 'apiUrl': api, 'assetUrl': outputs['CloudFrontAssetsUrl'],
              'realAiChecked': args.tailor, 'checks': checks}
    target = Path(args.report)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + '\n')
    print('Verification report written to', target, flush=True)


if __name__ == '__main__':
    main()
