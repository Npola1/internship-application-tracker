import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('runner',Path(__file__).resolve().parents[1]/'run_android_tests.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

class AndroidResultParserTest(unittest.TestCase):
    def status(self,code=0):
        return f'INSTRUMENTATION_STATUS: class=FeatureTest\nINSTRUMENTATION_STATUS: test=feature\nINSTRUMENTATION_STATUS_CODE: {code}\n'
    def test_pass_requires_actual_completed_tests_and_matching_count(self):
        self.assertTrue(runner.parse_results(self.status()+'OK (1 test)')['successful'])
        self.assertFalse(runner.parse_results(self.status()+'OK (2 tests)')['successful'])
    def test_failure_is_not_hidden_by_adb_success_exit(self):
        self.assertFalse(runner.parse_results(self.status(-2)+'FAILURES!!!')['successful'])
    def test_ignored_tests_are_never_counted_as_passes(self):
        self.assertFalse(runner.parse_results(self.status(-3)+'OK (1 test)')['successful'])
    def test_runner_crash_is_not_a_successful_empty_suite(self):
        self.assertFalse(runner.parse_results('INSTRUMENTATION_FAILED: process crashed')['successful'])
    def test_start_events_are_not_double_counted(self):
        result=runner.parse_results(self.status(1)+self.status(0)+'OK (1 test)')
        self.assertTrue(result['successful']);self.assertEqual(1,result['testsRun'])
