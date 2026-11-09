#!/usr/bin/env python3
"""Zero-dependency, offline integrity checks; not a substitute for Gradle/Maven integration builds."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[1]
errors = []
xml_files = list(ROOT.rglob('*.xml'))
for item in xml_files:
    try:
        ElementTree.parse(item)
    except Exception as exc:
        errors.append(f'Bad XML: {item}: {exc}')

layout_ids = set()
for path in (ROOT / 'android/app/src/main/res/layout').glob('*.xml'):
    layout_ids.update(re.findall(r'android:id="@\+id/([a-zA-Z0-9_]+)"', path.read_text()))
for source in (ROOT / 'android/app/src/main/java').rglob('*.java'):
    for match in re.finditer(r'R\.id\.([a-zA-Z0-9_]+)', source.read_text()):
        if match.group(1) not in layout_ids:
            errors.append(f'Missing view id in layout: {source.name}: {match.group(1)}')

backend = (ROOT / 'backend/src/main/java/app/tracker/Handler.java').read_text()
for route in ('/dashboard', '/applications', '/cvs', '/me', 'upload-url', 'download-url', 'tailor', 'tailoring-jobs', 'complete'):
    if route not in backend: errors.append(f'Missing API route {route}')

sam = (ROOT / 'infra/template.yaml').read_text()
for required in ('AWS::DynamoDB::Table', 'AWS::S3::Bucket', 'AWS::CloudFront::Distribution',
                 'AWS::Cognito::UserPool', 'AWS::Serverless::HttpApi', 'AWS::Serverless::Function'):
    if required not in sam: errors.append(f'Missing AWS resource {required}')

for required in ('CvService', 'TailoringJobs', 'TrackerRepository'):
    if required not in backend: errors.append(f'Missing backend dependency {required}')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
print(f'PASS: {len(xml_files)} XML files, {len(layout_ids)} Android view IDs, AWS architecture and API route references')
