import importlib.util
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/f'{name}.py')
    loaded=importlib.util.module_from_spec(spec);spec.loader.exec_module(loaded);return loaded
secret=module('configure_openai_secret')
config=module('write_android_config')

class SecretConfigurationTest(unittest.TestCase):
    def test_private_json_transport_and_cleanup_on_success(self):
        files=[]
        def run(command,**kwargs):
            self.assertNotIn('dummy-private-marker',repr(command))
            path=Path(command[-1].removeprefix('file://'));files.append(path)
            self.assertEqual(0o600,stat.S_IMODE(path.stat().st_mode))
            self.assertEqual({'SecretId':'test-secret','SecretString':'dummy-private-marker'},json.loads(path.read_text()))
            self.assertEqual(subprocess.DEVNULL,kwargs['stdin'])
            return subprocess.CompletedProcess(command,0,stderr='')
        with patch.object(secret.subprocess,'run',side_effect=run): secret.store_key(['aws'],'test-secret','dummy-private-marker')
        self.assertFalse(files[0].exists())
    def test_failure_hides_provider_input_and_deletes_the_file(self):
        files=[]
        def run(command,**kwargs):
            files.append(Path(command[-1].removeprefix('file://')))
            return subprocess.CompletedProcess(command,252,stderr='private dummy-private-marker')
        with patch.object(secret.subprocess,'run',side_effect=run):
            with self.assertRaises(SystemExit) as caught: secret.store_key(['aws'],'test-secret','dummy-private-marker')
        self.assertNotIn('dummy-private-marker',str(caught.exception));self.assertFalse(files[0].exists())
    def test_transport_exception_also_removes_the_temporary_file(self):
        files=[]
        def run(command,**kwargs):
            files.append(Path(command[-1].removeprefix('file://')));raise OSError('Synthetic failure')
        with patch.object(secret.subprocess,'run',side_effect=run),self.assertRaises(OSError): secret.store_key(['aws'],'test-secret','dummy')
        self.assertFalse(files[0].exists())
    def test_wrong_account_is_rejected_before_prompting_for_the_key(self):
        with patch('sys.argv',['helper','--account','expected']),patch.object(secret.sys.stdin,'isatty',return_value=True),\
             patch.object(secret.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout='other\n')),\
             patch.object(secret.getpass,'getpass') as prompt,self.assertRaises(SystemExit): secret.main()
        prompt.assert_not_called()
    def test_noninteractive_key_entry_is_rejected(self):
        with patch('sys.argv',['helper','--account','expected']),patch.object(secret.sys.stdin,'isatty',return_value=False),\
             patch.object(secret.subprocess,'run') as run,self.assertRaises(SystemExit): secret.main()
        run.assert_not_called()
    def test_empty_key_is_not_sent_to_aws(self):
        with patch('sys.argv',['helper','--account','expected']),patch.object(secret.sys.stdin,'isatty',return_value=True),\
             patch.object(secret.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout='expected\n')),\
             patch.object(secret.getpass,'getpass',return_value=' '),patch.object(secret,'store_key') as store,\
             self.assertRaises(SystemExit): secret.main()
        store.assert_not_called()

class AndroidConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'gradle.properties'
        self.path.write_text('# Keep this comment\nandroid.useAndroidX=true\ntrackerApiUrl=REPLACE\n')
        self.outputs={'ApiBaseUrl':'https://api.example.org','CognitoHostedUiBase':'https://login.example.org',
                      'CognitoClientId':'public-client','CloudFrontAssetsUrl':'https://assets.example.org'}
    def test_updates_existing_values_and_adds_missing_settings(self):
        config.write_config(self.outputs,self.path);result=self.path.read_text()
        self.assertIn('# Keep this comment',result);self.assertIn('android.useAndroidX=true',result)
        for prop,output in config.PROPERTIES:self.assertIn(prop+'='+self.outputs[output],result)
        self.assertNotIn('REPLACE',result)
    def test_missing_outputs_leave_the_original_file_untouched(self):
        before=self.path.read_bytes();del self.outputs['CognitoClientId']
        with self.assertRaises(ValueError):config.write_config(self.outputs,self.path)
        self.assertEqual(before,self.path.read_bytes())
    def test_repeated_configuration_does_not_duplicate_settings(self):
        config.write_config(self.outputs,self.path);once=self.path.read_bytes()
        config.write_config(self.outputs,self.path);self.assertEqual(once,self.path.read_bytes())

if __name__=='__main__':unittest.main()
