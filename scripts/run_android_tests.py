#!/usr/bin/env python3
"""Build and run dedicated Android tests on an explicitly selected emulator only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[1]

def find_adb():
    sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(Path.home()/'Library/Android/sdk'))
    return shutil.which('adb') or str(sdk/'platform-tools/adb')

def parse_results(output):
    tests=[];current={}
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            field,_,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if field in ('class','test'):current[field]=value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.rsplit(':',1)[1])
            if code!=1 and current.get('test'):
                tests.append({'class':current.get('class',''),'test':current['test'],
                              'status':{0:'PASS',-1:'ERROR',-2:'FAIL',-3:'NOT_RUN',-4:'NOT_RUN'}.get(code,'ERROR')})
            current={}
    summary=re.search(r'OK \((\d+) tests?\)',output)
    success=summary is not None and len(tests)==int(summary.group(1)) and all(t['status']=='PASS' for t in tests)
    return {'successful':success,'testsRun':len(tests),'results':tests}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True);parser.add_argument('--variant',choices=('debug','demo'),default='debug')
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--report')
    parser.add_argument('--suite',choices=('core','async'),default='core')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):raise SystemExit('Use an isolated emulator: these tests reset test-app data. Physical phones are not selected by this runner.')
    if args.suite=='async' and args.variant!='debug':raise SystemExit('The controlled async suite uses the online build')
    adb=find_adb()
    title=args.variant.capitalize()
    if not args.skip_build:
        subprocess.run([str(ROOT/'android/gradlew'),'-PtrackerTestBuildType='+args.variant,'assemble'+title,'assemble'+title+'AndroidTest'],cwd=ROOT/'android',check=True)
    apk=ROOT/f'android/app/build/outputs/apk/{args.variant}/app-{args.variant}.apk'
    test_apk=ROOT/f'android/app/build/outputs/apk/androidTest/{args.variant}/app-{args.variant}-androidTest.apk'
    for path in (apk,test_apk):subprocess.run([adb,'-s',args.serial,'install','-r',str(path)],check=True)
    classes='com.interntrack.DemoUiTest' if args.variant=='demo' else ','.join('com.interntrack.'+name for name in ('SessionSecurityTest','PresentationTest','OnlineGatingTest'))
    if args.suite=='async':classes='com.interntrack.AsyncTailoringUiTest'
    package='com.interntrack'+('.demo' if args.variant=='demo' else '')+'.test'
    controlled=['-e','controlledNetwork','true'] if args.suite=='async' else []
    completed=subprocess.run([adb,'-s',args.serial,'shell','am','instrument','-w','-r','-e','class',classes,*controlled,
        package+'/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=600)
    output=completed.stdout+completed.stderr
    suffix='-async' if args.suite=='async' else ''
    target=Path(args.report) if args.report else ROOT/f'docs/test-results/android-{args.variant}{suffix}.json'
    target.parent.mkdir(parents=True,exist_ok=True);target.with_suffix('.log').write_text(output)
    result=parse_results(output)
    result.update({'runAt':datetime.now(timezone.utc).isoformat(),'variant':args.variant,'serial':args.serial,
                   'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest()})
    result['successful']=result['successful'] and completed.returncode==0
    target.write_text(json.dumps(result,indent=2)+'\n')
    for test in result['results']:print(test['status']+': '+test['class']+'.'+test['test'])
    print('Report:',target)
    return 0 if result['successful'] else 1

if __name__=='__main__':raise SystemExit(main())
