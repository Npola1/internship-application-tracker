#!/usr/bin/env python3
"""Run the feature suites or collect their existing evidence into one report.

Requires Maven/JDK 21 and Android SDK. --live also needs scripts/requirements-test.txt,
AWS login and an explicit account. Instrumentation targets an isolated emulator only.
"""
import argparse
from collections import Counter
from datetime import datetime,timezone
import glob
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
REPORTS=ROOT/'docs/test-results'

def junit(paths):
    results=[]
    for path in sorted(paths):
        root=ET.parse(path).getroot()
        for case in root.iter('testcase'):
            status='ERROR' if case.find('error') is not None else 'FAIL' if case.find('failure') is not None else 'NOT_RUN' if case.find('skipped') is not None else 'PASS'
            results.append({'test':case.get('classname','')+'.'+case.get('name',''),'status':status})
    return results

def python_results(path):
    if not path.exists():return []
    output=path.read_text();results=[]
    for line in output.splitlines():
        match=re.match(r'(test_\S+) \(([^)]+)\) \.\.\. (ok|FAIL|ERROR|skipped.*)',line)
        if match:results.append({'test':match[2]+'.'+match[1],'status':{'ok':'PASS','FAIL':'FAIL','ERROR':'ERROR'}.get(match[3],'NOT_RUN')})
    return results

def collect(include_live, step_errors=None, unrun_layers=()):
    layers={
        'backend':junit(glob.glob(str(ROOT/'backend/target/surefire-reports/TEST-*.xml'))),
        'android_unit':junit(glob.glob(str(ROOT/'android/app/build/test-results/testDemoUnitTest/TEST-*.xml'))),
        'python_helpers':python_results(REPORTS/'python.log'),
    }
    for name,file in [('android_online','android-debug.json'),('android_demo','android-demo.json'),('android_async_controlled','android-debug-async.json')]+(
        [('live_aws','live-features.json'),('android_live','android-live.json')] if include_live else []):
        path=REPORTS/file
        layers[name]=json.loads(path.read_text()).get('results',[]) if path.exists() else []
    counts=Counter();normalized=[]
    for layer in unrun_layers:
        if layer in layers:layers[layer]=[]
    for layer,tests in layers.items():
        if not tests:tests=[{'test':'suite','status':'NOT_RUN','reason':'No test evidence was found'}]
        for test in tests:
            item=dict(test,layer=layer);normalized.append(item);counts[item['status']]+=1
    for step in step_errors or []:
        normalized.append({'layer':'runner','test':step,'status':'ERROR','reason':'The suite command failed; inspect its log.'});counts['ERROR']+=1
    outcome='FAIL' if counts['FAIL'] or counts['ERROR'] else 'PARTIAL' if counts['BLOCKED'] or counts['NOT_RUN'] else 'PASS'
    report={'collectedAt':datetime.now(timezone.utc).isoformat(),'overall':outcome,'counts':dict(counts),'results':normalized}
    REPORTS.mkdir(parents=True,exist_ok=True)
    (REPORTS/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    rows=['# Feature test results','',f'Overall: **{outcome}**. Counts: '+', '.join(f'{n} {status}' for status,n in sorted(counts.items()))+'.','',
        'PASS means the named check passed at its stated test boundary. Controlled-response tests do not establish real AI generation.','',
        '| Layer | Passed | Failed/errors | Blocked/not run |','|---|---:|---:|---:|']
    for layer in layers:
        subset=[x for x in normalized if x['layer']==layer];group=Counter(x['status'] for x in subset)
        rows.append(f"| {layer} | {group['PASS']} | {group['FAIL']+group['ERROR']} | {group['BLOCKED']+group['NOT_RUN']} |")
    rows+=['','## Checks needing external verification','']
    for item in normalized:
        if item['status'] in ('BLOCKED','NOT_RUN'):rows.append('- '+item['test']+': '+item.get('reason','No current result.'))
    rows+=['','See [TESTING.md](../TESTING.md) for the feature matrix, test boundaries and repeatable commands.','']
    (REPORTS/'SUMMARY.md').write_text('\n'.join(rows))
    print('Overall:',outcome,'—',dict(counts));print('Report:',REPORTS/'SUMMARY.md')
    return outcome

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial');parser.add_argument('--live',action='store_true');parser.add_argument('--account')
    parser.add_argument('--collect-only',action='store_true')
    args=parser.parse_args()
    if args.live and not args.account and not args.collect_only:parser.error('--live requires --account')
    if args.serial and not args.serial.startswith('emulator-'):parser.error('Use an isolated emulator serial')
    REPORTS.mkdir(parents=True,exist_ok=True);step_errors=[]
    def run(name,command,cwd=ROOT):
        with (REPORTS/(name+'.log')).open('w') as output:
            try:result=subprocess.run(command,cwd=cwd,stdout=output,stderr=subprocess.STDOUT)
            except OSError as error:output.write(type(error).__name__+': required test tool unavailable\n');step_errors.append(name);return False
        if result.returncode:step_errors.append(name)
        print(('PASS' if result.returncode==0 else 'FAIL')+': '+name,flush=True)
        return result.returncode==0
    if not args.collect_only:
        run('backend',[os.environ.get('MAVEN','mvn'),'-f',str(ROOT/'backend/pom.xml'),'clean','test'])
        run('python',[sys.executable,'-m','unittest','discover','-s','scripts/tests','-v'])
        run('android-build',[str(ROOT/'android/gradlew'),'assembleDebug','assembleDemo','testDemoUnitTest'],ROOT/'android')
        run('structure',[sys.executable,'scripts/check_structure.py'])
        if args.serial:
            for variant in ('debug','demo'):
                run('android-'+variant,[sys.executable,'scripts/run_android_tests.py','--serial',args.serial,'--variant',variant])
            run('android-async',[sys.executable,'scripts/run_android_tests.py','--serial',args.serial,'--suite','async'])
        if args.live:
            run('live',[sys.executable,'scripts/test_live_features.py','--account',args.account])
            if args.serial:run('android-live',[sys.executable,'scripts/test_live_android.py','--serial',args.serial,'--account',args.account])
    unrun=[] if args.collect_only or args.serial else ['android_online','android_demo','android_async_controlled','android_live']
    outcome=collect(args.live,step_errors,unrun)
    return 1 if outcome=='FAIL' else 2 if outcome=='PARTIAL' else 0

if __name__=='__main__':raise SystemExit(main())
