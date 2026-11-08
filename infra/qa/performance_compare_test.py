import copy
import unittest
from performance_compare import compare, validate_devices


def fixture(value=100, p95=8):
    return {"context": {"build": {"model": "fixture", "sdk": 35}}, "benchmarks": [
        {"name": "startup", "metrics": {"timeToInitialDisplayMs": {"median": value}}},
        {"name": "scroll", "sampledMetrics": {"frameDurationCpuMs": {"P95": p95}}}]}


class PerformanceCompareTest(unittest.TestCase):
    def test_only_repeated_regressions_fail(self):
        self.assertFalse(compare(fixture(), [fixture(106)] * 3)["accepted"])
        self.assertTrue(compare(fixture(), [fixture(106), fixture(99), fixture(104)])["accepted"])
        self.assertTrue(compare(fixture(), [fixture(105)] * 3)["accepted"])

    def test_frame_percentiles_are_compared(self):
        self.assertFalse(compare(fixture(), [fixture(p95=10)] * 3)["accepted"])

    def test_negative_overrun_increase_is_a_regression(self):
        self.assertFalse(compare(fixture(p95=-2), [fixture(p95=-1)] * 3)["accepted"])
        self.assertTrue(compare(fixture(p95=-2), [fixture(p95=-3)] * 3)["accepted"])

    def test_empty_missing_and_nonfinite_results_fail(self):
        for broken in ({}, fixture(float("nan")), fixture(float("inf"))):
            with self.assertRaises(ValueError): compare(fixture(), [broken] * 3)
        with self.assertRaises(ValueError): compare(fixture(), [fixture()] * 2)
        bad = fixture()
        del bad["benchmarks"][1]
        with self.assertRaises(ValueError): compare(fixture(), [bad] * 3)

    def test_frame_count_is_not_treated_as_a_speed_measurement(self):
        report = fixture()
        report["benchmarks"][0]["metrics"]["frameCount"] = {"median": 100}
        self.assertTrue(compare(report, [fixture()] * 3)["accepted"])

    def test_unknown_metric_requires_classification(self):
        report = fixture()
        report["benchmarks"][0]["metrics"]["throughput"] = {"median": 500}
        with self.assertRaises(ValueError): compare(report, [report] * 3)

    def test_device_identity_must_match_the_benchmark_context(self):
        bad = fixture()
        bad["context"]["build"]["sdk"] = 34
        with self.assertRaises(ValueError): compare(fixture(), [bad] * 3)

    def test_physical_display_and_compilation_settings_are_required(self):
        device = dict(deviceId="fixture", osBuild="test", refreshHz=120, fontScale=1,
                      powerSave=False, compilationMode="None", ramGb=8, isPhysical=True, sourceRevision="abc")
        validate_devices(device, device)
        for key, value in (("isPhysical", False), ("refreshHz", 60), ("fontScale", 1.3), ("compilationMode", "Full")):
            bad = copy.deepcopy(device)
            bad[key] = value
            with self.assertRaises(ValueError): validate_devices(device, bad)
        with self.assertRaises(ValueError): validate_devices({}, device)


if __name__ == "__main__": unittest.main()
