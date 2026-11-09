#!/usr/bin/env python3
"""Optional deployed-API smoke test. Requires TRACKER_API_URL and COGNITO_ACCESS_TOKEN."""
import json
import os
import urllib.error
import urllib.request

base = os.environ['TRACKER_API_URL'].rstrip('/')
token = os.environ['COGNITO_ACCESS_TOKEN']

def request(method, path, body=None):
    payload = json.dumps(body).encode() if body is not None else None
    headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/json'}
    if payload: headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, method=method, data=payload, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)

me = request('GET', '/me')
assert me.get('userId')
dashboard = request('GET', '/dashboard')
assert dashboard['account'] == me
assert isinstance(dashboard['applications'], list)
request('GET', '/applications')
assert 'cvs' in request('GET', '/cvs')
assets = os.environ.get('TRACKER_ASSET_BASE_URL', '').rstrip('/')
if assets:
    with urllib.request.urlopen(assets + '/tips.json', timeout=30) as response:
        assert json.load(response).get('tips'), 'CloudFront must serve application tips'
else:
    print('SKIP: CloudFront check (set TRACKER_ASSET_BASE_URL to enable)')
created = request('POST', '/applications', {
    'company': 'Integration Test Corp', 'role': 'Software Intern', 'status': 'Saved',
    'jobUrl': 'https://example.org/jobs/123', 'appliedDate': '', 'notes': 'Smoke test',
    'jobDescription': '', 'cvId': ''
})
app_id = created['id']
try:
    updated = dict(created)
    updated['status'] = 'Applied'
    assert request('PUT', '/applications/' + app_id, updated)['status'] == 'Applied'
    assert request('GET', '/applications/' + app_id)['company'] == 'Integration Test Corp'
    saved = next(app for app in request('GET', '/dashboard')['applications'] if app['id'] == app_id)
    assert saved['role'] == created['role']
    assert saved['jobUrl'] == created['jobUrl']
    assert saved['cvId'] == '' and saved['cvName'] == ''
    assert saved['status'] == 'Applied'
    assert request('GET', '/me') == me
finally:
    request('DELETE', '/applications/' + app_id)
assert all(app['id'] != app_id for app in request('GET', '/dashboard')['applications'])
print('PASS: persisted dashboard account, CV list, application CRUD and dashboard refresh')
