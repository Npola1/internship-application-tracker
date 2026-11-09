import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('suite_runner',Path(__file__).resolve().parents[1]/'run_feature_tests.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

class SuiteReportTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.reports=self.root/'reports'
    def collect(self,errors=None):
        with patch.object(runner,'ROOT',self.root),patch.object(runner,'REPORTS',self.reports),contextlib.redirect_stdout(io.StringIO()):
            return runner.collect(False,errors)
    def test_missing_evidence_is_never_reported_as_all_passing(self):
        self.assertEqual('PARTIAL',self.collect())
        report=json.loads((self.reports/'summary.json').read_text())
        self.assertGreater(report['counts']['NOT_RUN'],0)
    def test_command_failure_cannot_be_hidden_by_missing_or_stale_results(self):
        self.assertEqual('FAIL',self.collect(['backend-build']))
        report=json.loads((self.reports/'summary.json').read_text())
        self.assertEqual(1,report['counts']['ERROR'])
    def test_junit_failures_errors_and_skips_keep_their_actual_status(self):
        path=self.root/'tests.xml'
        path.write_text('<testsuite><testcase classname="C" name="ok"/><testcase classname="C" name="bad"><failure/></testcase><testcase classname="C" name="error"><error/></testcase><testcase classname="C" name="skip"><skipped/></testcase></testsuite>')
        self.assertEqual(['PASS','FAIL','ERROR','NOT_RUN'],[x['status'] for x in runner.junit([path])])
    def test_python_failures_are_not_counted_as_passing_tests(self):
        path=self.root/'python.log';path.write_text('test_ok (HelperTest) ... ok\ntest_bad (HelperTest) ... FAIL\n')
        self.assertEqual(['PASS','FAIL'],[x['status'] for x in runner.python_results(path)])

if __name__=='__main__':unittest.main()
