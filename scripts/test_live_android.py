#!/usr/bin/env python3
"""Test the real Android API client with a disposable cloud account; never print credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime,timezone
import boto3
from test_live_features import LiveFeatures
from run_android_tests import ROOT,parse_results,find_adb

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True);parser.add_argument('--account',required=True)
    parser.add_argument('--region',default='us-east-1');parser.add_argument('--stack-name',default='interntrack-dev')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):raise SystemExit('Use an isolated emulator only')
    session=boto3.Session(region_name=args.region)
    if session.client('sts').get_caller_identity()['Account']!=args.account:raise SystemExit('Wrong AWS account')
    outputs={x['OutputKey']:x['OutputValue'] for x in session.client('cloudformation').describe_stacks(StackName=args.stack_name)['Stacks'][0]['Outputs']}
    LiveFeatures.outputs=outputs;LiveFeatures.domain=outputs['CognitoHostedUiBase'];LiveFeatures.client=outputs['CognitoClientId']
    LiveFeatures.cognito=session.client('cognito-idp');LiveFeatures.s3=session.client('s3')
    LiveFeatures.table=session.resource('dynamodb').Table(outputs['TrackerTableName']);LiveFeatures.users=[]
    adb=find_adb()
    command=[adb,'-s',args.serial]
    report=None
    try:
        user=LiveFeatures.user()
        payload=json.dumps({'sub':user['sub'],'tokens':user['tokens']}).encode()
        subprocess.run(command+['shell','run-as','com.interntrack','mkdir','-p','files'],check=True,capture_output=True)
        # Private target-app file; the instrumentation consumes and deletes it before tests.
        subprocess.run(command+['shell','run-as','com.interntrack','tee','files/live-test-auth.json'],
            input=payload,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True)
        result=subprocess.run(command+['shell','am','instrument','-w','-r','-e','liveFixture','true','-e','class','com.interntrack.LiveClientTest',
            'com.interntrack.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=300)
        report=parse_results(result.stdout+result.stderr)
        report['successful']=report['successful'] and result.returncode==0
        report.update({'runAt':datetime.now(timezone.utc).isoformat(),'stackName':args.stack_name,'region':args.region,
            'apkSha256':hashlib.sha256((ROOT/'android/app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()})
        target=ROOT/'docs/test-results/android-live.json';target.parent.mkdir(parents=True,exist_ok=True)
        target.with_suffix('.log').write_text(result.stdout+result.stderr)
        for test in report['results']:print(test['status']+': '+test['test'])
    finally:
        subprocess.run(command+['shell','run-as','com.interntrack','rm','-f','files/live-test-auth.json'],capture_output=True)
        LiveFeatures.cleanup()
    report['cleanupSucceeded']=True
    (ROOT/'docs/test-results/android-live.json').write_text(json.dumps(report,indent=2)+'\n')
    return 0 if report['successful'] else 1

if __name__=='__main__':raise SystemExit(main())
