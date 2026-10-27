"""Checks for analyze.py on small synthetic k6 runs. Run: python3 -I scripts/loadtest/test_analyze.py"""
import datetime as dt
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ANALYZE = os.path.join(HERE, "analyze.py")
START = dt.datetime(2026, 10, 27, 11, 0, 0, tzinfo=dt.timezone(dt.timedelta(hours=3)))  # the Mac writes +03:00


def point(metric, t, value, tags=None, day=0):
    when = (START + dt.timedelta(days=day, seconds=t)).isoformat()
    return json.dumps({"metric": metric, "type": "Point", "data": {"time": when, "value": value, "tags": tags or {}}},
                      separators=(",", ":"))  # compact, as k6 writes it


def write_run(path, step_vus=(2, 4), latency=100, stall_at=None, stall_names=("/api/session", "/api/meetings"), day=0):
    """T1 with STEP_MINUTES=1: ramp 0-60 s, hold 60-120 s, ramp 120-180 s, hold 180-240 s, then down."""
    lines = []
    for second in range(0, 250):
        vus = step_vus[0] if second < 120 else step_vus[1]
        lines.append(point("vus", second, vus, day=day))
        for i, name in enumerate(("/api/session", "/api/meetings", "/api/programs", "/api/mentor-requests")):
            t = second + i / 10
            stalled = stall_at is not None and stall_at <= second < stall_at + 10 and name in stall_names
            wait = 5000 if stalled else latency
            tags = {"name": name, "scenario": "T1", "status": "200"}
            lines += [point("http_req_duration", t, wait + 5, tags, day), point("http_req_waiting", t, wait, tags, day),
                      point("http_req_failed", t, 0, tags, day)]
        if second % 5 == 0:
            lines += [point("iterations", second, 1, {"scenario": "T1"}, day),
                      point("iteration_duration", second, 20000, {"scenario": "T1"}, day)]
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


def run(path, *args, env=None):
    full_env = {**os.environ, "TEST": "T1", "STEPS": "2,4", "STEP_MINUTES": "1", **(env or {})}
    return subprocess.run([sys.executable, "-I", ANALYZE, path, *args], capture_output=True, text=True, env=full_env)


class AnalyzeTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.run_json = os.path.join(self.dir, "T1-test.json")

    def test_cuts_ramps_and_reports_each_hold(self):
        write_run(self.run_json)
        out = run(self.run_json, "--no-history")
        self.assertEqual(out.returncode, 0, out.stderr)
        # 60 s holds, 4 requests a second, times converted from +03:00 to UTC
        self.assertIn("| 2 | 08:01–08:02 | 240 |", out.stdout)
        self.assertIn("| 4 | 08:03–08:04 | 240 |", out.stdout)
        self.assertIn("None: no 10 s window", out.stdout)

    def test_a_stall_on_next_only_requests_is_the_network_and_makes_the_step_invalid(self):
        write_run(self.run_json, stall_at=190)
        out = run(self.run_json, "--no-history")
        self.assertEqual(out.returncode, 2, out.stdout)
        self.assertIn("no (stall)", out.stdout)
        self.assertIn("**network path**", out.stdout)

    def test_a_stall_on_backend_endpoints_only_points_at_the_app(self):
        write_run(self.run_json, stall_at=190, stall_names=("/api/meetings", "/api/programs"))
        out = run(self.run_json, "--no-history")
        self.assertIn("**app (likely)**", out.stdout)

    def test_flags_a_regression_against_the_last_accepted_run(self):
        history = os.path.join(self.dir, "history")
        write_run(self.run_json)
        first = run(self.run_json, "--history", history, "--backend-version", "v1")
        self.assertEqual(first.returncode, 0, first.stderr)
        slower = os.path.join(self.dir, "T1-slower.json")
        write_run(slower, latency=400, day=1)
        second = run(slower, "--history", history, "--backend-version", "v2")
        self.assertEqual(second.returncode, 1, second.stdout)
        self.assertIn("2 VUs: p95 105 → 405 ms", second.stdout)

    def test_aws_mode_asks_the_slow_request_log_and_cloudwatch(self):
        write_run(self.run_json, stall_at=190)
        bin_dir = os.path.join(self.dir, "bin")
        os.mkdir(bin_dir)
        fake = os.path.join(bin_dir, "aws")
        with open(fake, "w") as f:  # stands in for the AWS CLI: read-only calls only, canned answers
            f.write("#!/bin/sh\ncase \"$1 $2\" in\n"
                    "  'ec2 describe-instances') echo '[[\"i-app\",\"teknokent-app\"],[\"i-v\",\"vexa\"]]';;\n"
                    "  'cloudwatch get-metric-statistics') echo '{\"Datapoints\":[{\"Maximum\":40,\"Minimum\":"
                    "62914560}]}';;\n"
                    "  'logs filter-log-events') echo '{\"events\":[{\"message\":\"Slow request\"}]}';;\n"
                    "  *) exit 1;;\nesac\n")
        os.chmod(fake, os.stat(fake).st_mode | stat.S_IEXEC)
        out = run(self.run_json, "--no-history", "--aws", env={"PATH": f"{bin_dir}:{os.environ['PATH']}"})
        self.assertIn("**app** (1 slow-request line(s)", out.stdout)
        self.assertIn("| EC2 CPU % | 40.0 (Maximum) |", out.stdout)
        self.assertIn("| RDS free memory MB | 60.0 (Minimum) |", out.stdout)


if __name__ == "__main__":
    unittest.main()
