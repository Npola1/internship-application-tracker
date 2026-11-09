#!/usr/bin/env python3
"""Dedicated live feature tests. Uses disposable users; no email, keys or signed URLs in reports.

Requires boto3 and requests. Run with --account to prevent tests in the wrong account.
Exit 1 means test/cleanup failure. BLOCKED tests are explicitly reported, never counted as passes.
"""
import argparse
import base64
import hashlib
import io
import json
import secrets
import time
import unittest
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlparse
import boto3
import requests
from boto3.dynamodb.conditions import Key
from verify_deployment import Forms

ARGS=None
CV=b'STUDENT CV\nComputer Science Student\nSKILLS\nJava, Git, unit testing\nPROJECTS\nBuilt an internship application tracker in Java.\n'

def pdf_fixture():
    stream=b'BT /F1 12 Tf 40 720 Td (Student CV: Java, Git and unit testing.) Tj ET'
    objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
    result=b'%PDF-1.4\n';offsets=[]
    for index,obj in enumerate(objects,1):
        offsets.append(len(result));result+=str(index).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
    start=len(result);result+=b'xref\n0 6\n0000000000 65535 f \n'
    for offset in offsets:result+=f'{offset:010d} 00000 n \n'.encode()
    return result+b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+str(start).encode()+b'\n%%EOF\n'

def http(method,url,**kwargs):
    try:return requests.request(method,url,timeout=30,**kwargs)
    except requests.RequestException as error:raise RuntimeError('Network request failed: '+type(error).__name__) from None

class LiveFeatures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.users=[];cls.outcome=None
        cls.session=boto3.Session(region_name=ARGS.region)
        if cls.session.client('sts').get_caller_identity()['Account']!=ARGS.account:raise RuntimeError('Wrong AWS account; no test data created')
        stack=cls.session.client('cloudformation').describe_stacks(StackName=ARGS.stack_name)['Stacks'][0]
        if stack['StackStatus'] not in ('CREATE_COMPLETE','UPDATE_COMPLETE'):raise RuntimeError('Deployment is not ready')
        cls.outputs={x['OutputKey']:x['OutputValue'] for x in stack['Outputs']}
        cls.api=cls.outputs['ApiBaseUrl'];cls.domain=cls.outputs['CognitoHostedUiBase'];cls.client=cls.outputs['CognitoClientId']
        cls.cognito=cls.session.client('cognito-idp');cls.s3=cls.session.client('s3')
        cls.table=cls.session.resource('dynamodb').Table(cls.outputs['TrackerTableName'])
        cls.addClassCleanup(cls.cleanup)
        cls.alice=cls.user();cls.bob=cls.user()

    @classmethod
    def login(cls,email,password):
        verifier=secrets.token_urlsafe(48);state=secrets.token_urlsafe(20)
        challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        browser=requests.Session()
        parameters={'client_id':cls.client,'redirect_uri':'interntrack://oauth2redirect','response_type':'code',
            'scope':'openid email profile aws.cognito.signin.user.admin','state':state,'code_challenge':challenge,'code_challenge_method':'S256'}
        try:
            page=browser.get(cls.domain+'/oauth2/authorize',params=parameters,timeout=30)
            if page.status_code!=200:raise RuntimeError('Cognito authorization page unavailable')
            forms=Forms();forms.feed(page.text)
            form=next(f for f in forms.forms if 'username' in f['fields'])
            fields=dict(form['fields']);fields.update(username=email,password=password)
            response=browser.post(urljoin(page.url,form['action'] or page.url),data=fields,allow_redirects=False,timeout=30)
            location=''
            for _ in range(8):
                location=response.headers.get('Location','')
                if location.startswith('interntrack://oauth2redirect'):break
                destination=urljoin(cls.domain,location)
                if not location or urlparse(destination).netloc!=urlparse(cls.domain).netloc:raise RuntimeError('Cognito login did not return the callback')
                response=browser.get(destination,allow_redirects=False,timeout=30)
            query=parse_qs(urlparse(location).query)
            if query.get('state')!=[state] or not query.get('code'):raise RuntimeError('OAuth callback state or code missing')
            response=browser.post(cls.domain+'/oauth2/token',data={'grant_type':'authorization_code','client_id':cls.client,
                'redirect_uri':'interntrack://oauth2redirect','code':query['code'][0],'code_verifier':verifier},timeout=30)
            if response.status_code!=200:raise RuntimeError('Cognito token exchange failed')
            return response.json()
        except requests.RequestException as error:raise RuntimeError('Cognito network failure: '+type(error).__name__) from None

    @classmethod
    def user(cls):
        email='feature-test-'+secrets.token_hex(8)+'@example.invalid';password='Aa1_'+secrets.token_hex(20)
        result=cls.cognito.admin_create_user(UserPoolId=cls.outputs['UserPoolId'],Username=email,MessageAction='SUPPRESS',
            UserAttributes=[{'Name':'email','Value':email},{'Name':'email_verified','Value':'true'},{'Name':'name','Value':'Feature test student'}])
        user={'email':email,'username':result['User']['Username'],
            'sub':next(x['Value'] for x in result['User']['Attributes'] if x['Name']=='sub')}
        cls.users.append(user)
        cls.cognito.admin_set_user_password(UserPoolId=cls.outputs['UserPoolId'],Username=user['username'],Password=password,Permanent=True)
        user['tokens']=cls.login(email,password)
        return user

    @classmethod
    def cleanup(cls):
        for user in cls.users:
            partition='USER#'+user['sub'];cursor=None
            while True:
                query={'KeyConditionExpression':Key('pk').eq(partition),'ConsistentRead':True}
                if cursor:query['ExclusiveStartKey']=cursor
                page=cls.table.query(**query)
                with cls.table.batch_writer() as writer:
                    for item in page.get('Items',[]):writer.delete_item(Key={'pk':item['pk'],'sk':item['sk']})
                cursor=page.get('LastEvaluatedKey')
                if not cursor:break
            for page in cls.s3.get_paginator('list_object_versions').paginate(Bucket=cls.outputs['CvBucketName'],Prefix='users/'+user['sub']+'/'):
                objects=[{'Key':x['Key'],'VersionId':x['VersionId']} for x in page.get('Versions',[])+page.get('DeleteMarkers',[])]
                if objects:
                    response=cls.s3.delete_objects(Bucket=cls.outputs['CvBucketName'],Delete={'Objects':objects,'Quiet':True})
                    if response.get('Errors'):raise RuntimeError('Disposable CV cleanup failed')
            cls.cognito.admin_delete_user(UserPoolId=cls.outputs['UserPoolId'],Username=user['username'])

    @classmethod
    def call(cls,method,path,body=None,user=None,expected=200):
        user=cls.alice if user is None else user
        response=http(method,cls.api+path,headers={'Authorization':'Bearer '+user['tokens']['access_token']},json=body)
        if response.status_code!=expected:raise AssertionError(f'{method} route returned HTTP {response.status_code}; expected {expected}')
        return response.json()

    @classmethod
    def application(cls,cv_id=''):
        return cls.call('POST','/applications',{'company':'Feature test employer','role':'Java Engineering Intern',
            'jobUrl':'https://example.org/internships/java','status':'Saved','appliedDate':'2026-08-12','notes':'Disposable test record',
            'jobDescription':'Java engineering internship. Build Java applications, use Git and write unit tests.','cvId':cv_id},expected=201)

    @classmethod
    def upload(cls,name='Student-CV.txt',data=CV):
        prepared=cls.call('POST','/cvs/upload-url',{'name':name,'size':len(data)})
        response=http('PUT',prepared['uploadUrl'],data=data,headers={'Content-Type':prepared['contentType']})
        if response.status_code!=200:raise AssertionError('Private S3 upload failed')
        return cls.call('POST','/cvs/'+prepared['id']+'/complete',{},expected=201)

    @classmethod
    def ai(cls):
        if cls.outcome is not None:return cls.outcome
        cv=cls.upload();app=cls.application(cv['id']);started=time.monotonic()
        job=cls.call('POST','/applications/'+app['id']+'/tailor',{'cvId':cv['id']},expected=202)
        if time.monotonic()-started>=25:raise AssertionError('Tailoring request blocked on AI processing')
        cls.call('GET','/tailoring-jobs/'+job['jobId'],user=cls.bob,expected=404)
        while time.monotonic()-started<240:
            result=cls.call('GET','/tailoring-jobs/'+job['jobId'])
            if result['status'] in ('FAILED','COMPLETED'):
                cls.outcome=(result,app,cv);return cls.outcome
            time.sleep(2)
        raise AssertionError('Tailoring job did not finish')

    def test_api_gateway_rejects_unauthenticated_and_invalid_tokens(self):
        for path in ('/me','/dashboard','/applications','/cvs','/tailoring-jobs/'+str(uuid.uuid4())):
            self.assertEqual(401,http('GET',self.api+path).status_code)
        self.assertEqual(401,http('GET',self.api+'/dashboard',headers={'Authorization':'Bearer invalid-test-token'}).status_code)

    def test_cognito_pkce_profile_and_refresh_session(self):
        profile=self.call('GET','/me');self.assertEqual(self.alice['email'],profile['email'])
        self.assertEqual('Feature test student',profile['name'])
        response=http('POST',self.domain+'/oauth2/token',data={'grant_type':'refresh_token','client_id':self.client,
            'refresh_token':self.alice['tokens']['refresh_token']})
        self.assertEqual(200,response.status_code)
        self.alice['tokens'].update(response.json())
        self.assertEqual(profile,self.call('GET','/dashboard')['account'])

    def test_signup_form_and_email_verification_configuration(self):
        pool=self.cognito.describe_user_pool(UserPoolId=self.outputs['UserPoolId'])['UserPool']
        self.assertFalse(pool['AdminCreateUserConfig'].get('AllowAdminCreateUserOnly',False))
        self.assertIn('email',pool['AutoVerifiedAttributes'])
        response=http('GET',self.domain+'/signup',params={'client_id':self.client,'response_type':'code','redirect_uri':'interntrack://oauth2redirect'})
        self.assertEqual(200,response.status_code)
        forms=Forms();forms.feed(response.text)
        self.assertTrue(any('password' in f['fields'] for f in forms.forms))

    def test_signup_email_delivery_and_user_confirmation(self):
        self.skipTest('MANUAL: Requires an authorized recipient inbox and its verification code; no test email is sent')

    def test_application_crud_dashboard_and_dynamodb_persistence(self):
        cv=self.upload();app=self.application(cv['id']);path='/applications/'+app['id']
        self.assertEqual(app,self.call('GET',path));app['notes']='Updated notes';app['status']='Interview'
        updated=self.call('PUT',path,app)
        dashboard=self.call('GET','/dashboard');saved=next(x for x in dashboard['applications'] if x['id']==app['id'])
        for key in ('company','role','jobUrl','cvId','cvName','appliedDate','notes'):self.assertEqual(updated[key],saved[key])
        record=self.table.get_item(Key={'pk':'USER#'+self.alice['sub'],'sk':'APP#'+app['id']},ConsistentRead=True)['Item']
        self.assertEqual('Updated notes',record['notes'])
        self.call('DELETE',path);self.call('GET',path,expected=404)
        self.assertTrue(self.call('GET','/cvs/'+cv['id']+'/download-url')['url'])

    def test_every_application_status_and_optional_date(self):
        app=self.application();path='/applications/'+app['id']
        for status in ('Saved','Applied','Assessment','Interview','Offer','Rejected','Withdrawn'):
            app['status']=status;self.assertEqual(status,self.call('PUT',path,app)['status'])
        app['appliedDate']='';self.assertEqual('',self.call('PUT',path,app)['appliedDate'])

    def test_invalid_application_input_is_rejected(self):
        app=self.application()
        for field,value in (('company',''),('jobUrl','javascript:alert(1)'),('appliedDate','20260812'),('status','unknown')):
            candidate=dict(app);candidate[field]=value;self.call('POST','/applications',candidate,expected=400)
        response=http('POST',self.api+'/applications',headers={'Authorization':'Bearer '+self.alice['tokens']['access_token'],'Content-Type':'application/json'},data='{')
        self.assertEqual(400,response.status_code)

    def test_cross_account_application_and_cv_access_is_denied(self):
        cv=self.upload();app=self.application(cv['id']);path='/applications/'+app['id']
        for method in ('GET','PUT','DELETE'):self.call(method,path,app,user=self.bob,expected=404)
        self.call('POST','/applications',app,user=self.bob,expected=404)
        self.call('GET','/cvs/'+cv['id']+'/download-url',user=self.bob,expected=404)
        self.assertEqual([],self.call('GET','/applications',user=self.bob)['applications'])

    def test_private_cv_upload_download_and_library_for_all_formats(self):
        docx=io.BytesIO()
        with zipfile.ZipFile(docx,'w') as archive:
            archive.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
            archive.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
            archive.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Student CV with Java experience and unit testing.</w:t></w:r></w:p></w:body></w:document>')
        for name,data in [('Student.txt',CV),('Student.pdf',pdf_fixture()),('Student.docx',docx.getvalue())]:
            cv=self.upload(name,data);link=self.call('GET','/cvs/'+cv['id']+'/download-url')
            response=http('GET',link['url']);self.assertEqual(200,response.status_code);self.assertEqual(data,response.content)
            self.assertIn(cv['id'],[x['id'] for x in self.call('GET','/cvs')['cvs']])
            public=f"https://{self.outputs['CvBucketName']}.s3.{ARGS.region}.amazonaws.com/{cv['objectKey']}"
            self.assertEqual(403,http('GET',public).status_code)

    def test_cv_validation_and_upload_completion_are_enforced(self):
        for payload in ({'name':'CV.exe','size':1},{'name':'CV.txt','size':0},{'name':'CV.pdf','size':10*1024*1024+1}):
            self.call('POST','/cvs/upload-url',payload,expected=400)
        pending=self.call('POST','/cvs/upload-url',{'name':'CV.txt','size':3})
        self.call('POST','/cvs/'+pending['id']+'/complete',{},user=self.bob,expected=404)
        self.call('POST','/cvs/'+pending['id']+'/complete',{},expected=400)
        response=http('PUT',pending['uploadUrl'],data=b'ab',headers={'Content-Type':pending['contentType']})
        self.assertEqual(200,response.status_code)
        self.call('POST','/cvs/'+pending['id']+'/complete',{},expected=400)

    def test_cloudfront_serves_tips_from_the_private_asset_origin(self):
        response=http('GET',self.outputs['CloudFrontAssetsUrl']+'/tips.json');self.assertEqual(200,response.status_code)
        self.assertTrue(response.json()['tips'])
        distribution=self.session.client('cloudfront').get_distribution(Id=self.outputs['CloudFrontDistributionId'])['Distribution']['DistributionConfig']
        self.assertEqual('redirect-to-https',distribution['DefaultCacheBehavior']['ViewerProtocolPolicy'])
        origin=distribution['Origins']['Items'][0];self.assertTrue(origin['OriginAccessControlId'])
        self.assertIn(self.outputs['AssetBucketName'],origin['DomainName']);self.assertNotIn(self.outputs['CvBucketName'],origin['DomainName'])
        self.assertEqual(403,http('GET',f"https://{self.outputs['AssetBucketName']}.s3.{ARGS.region}.amazonaws.com/tips.json").status_code)

    def test_ai_job_is_async_isolated_and_failure_preserves_the_source(self):
        result,app,cv=self.ai()
        self.assertNotIn('jobDescription',result);self.assertNotIn('sourceCvId',result)
        if result['status']=='FAILED':
            self.assertTrue(result.get('message'))
            self.assertNotIn('tailoredCv',result)
            self.assertEqual(cv['id'],self.call('GET','/applications/'+app['id'])['cvId'])
        else:self.assertTrue(result['reviewRequired'])

    def test_real_ai_docx_generation_download_and_attachment(self):
        result,app,cv=self.ai()
        if result['status']=='FAILED':
            message=result.get('message','')
            if any(x in message.lower() for x in ('credits','billing','usage limit','too many ai requests')):
                self.skipTest('BLOCKED: '+message)
            self.fail('Unexpected AI worker failure')
        tailored=result['tailoredCv'];link=self.call('GET','/cvs/'+tailored['id']+'/download-url')
        response=http('GET',link['url']);self.assertEqual(200,response.status_code)
        with zipfile.ZipFile(io.BytesIO(response.content)) as document:self.assertIn(b'Java',document.read('word/document.xml'))
        app['cvId']=tailored['id'];self.call('PUT','/applications/'+app['id'],app)
        self.assertEqual(tailored['id'],self.call('GET','/applications/'+app['id'])['cvId'])

class Results(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.records=[]
    def addSuccess(self,test):super().addSuccess(test);self.records.append({'test':test.id(),'status':'PASS'})
    def addSkip(self,test,reason):super().addSkip(test,reason);self.records.append({'test':test.id(),'status':'BLOCKED' if reason.startswith('BLOCKED:') else 'NOT_RUN','reason':reason})
    def addFailure(self,test,error):super().addFailure(test,error);self.records.append({'test':test.id(),'status':'FAIL'})
    def addError(self,test,error):super().addError(test,error);self.records.append({'test':test.id(),'status':'ERROR'})

def main():
    global ARGS
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--account',required=True);parser.add_argument('--stack-name',default='interntrack-dev')
    parser.add_argument('--region',default='us-east-1');parser.add_argument('--report',default='docs/test-results/live-features.json')
    ARGS=parser.parse_args()
    result=unittest.TextTestRunner(verbosity=2,resultclass=Results).run(unittest.defaultTestLoader.loadTestsFromTestCase(LiveFeatures))
    target=Path(ARGS.report);target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps({'runAt':datetime.now(timezone.utc).isoformat(),'account':ARGS.account,'region':ARGS.region,
        'stackName':ARGS.stack_name,'testsRun':result.testsRun,'successful':result.wasSuccessful(),
        'fullyVerified':result.wasSuccessful() and not result.skipped,
        'results':result.records,'cleanupSucceeded':not any('tearDownClass' in str(x[0]) or 'setUpClass' in str(x[0]) for x in result.errors)},indent=2)+'\n')
    return 0 if result.wasSuccessful() else 1

if __name__=='__main__':raise SystemExit(main())
