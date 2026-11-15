import json
import pathlib
import tempfile
import threading
import unittest
from unittest.mock import patch

from collect_logcat import collect


class CaptureTest(unittest.TestCase):
    def test_failed_transport_is_retried_before_starting_logcat(self):
        stop = threading.Event()
        failed = type("Result", (), {"returncode": 1, "stdout": b"refused", "stderr": b""})()
        waits = []

        def retry(timeout):
            waits.append(timeout)
            if len(waits) == 2:
                stop.set()
            return stop.is_set()

        with tempfile.TemporaryDirectory() as temporary, patch("collect_logcat.subprocess.run", return_value=failed) as connect, \
                patch("collect_logcat.subprocess.Popen") as logcat, patch.object(stop, "wait", side_effect=retry):
            collect("adb", "127.0.0.1:5555", pathlib.Path(temporary), stop)
            self.assertEqual(2, connect.call_count)
            self.assertEqual([15, 15], waits)
            logcat.assert_not_called()

    def test_disconnect_replays_into_new_file_and_records_coverage(self):
        class TwoConnections(threading.Event):
            waits = 0

            def wait(self, timeout=None):
                self.waits += 1
                if self.waits == 2:
                    self.set()
                return self.is_set()

        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            adb = root / "adb"
            adb.write_text("#!/bin/sh\n"
                           'if [ "$1" = "connect" ]; then echo connected; '
                           'elif [ "$3" = "get-state" ]; then echo device; '
                           "else echo '11-15 14:00:00.123  123  123 I App: test'; fi\n")
            adb.chmod(0o700)
            output = root / "logs"
            collect(str(adb), "127.0.0.1:5555", output, TwoConnections())
            files = sorted(output.glob("logcat-*.txt"))
            self.assertEqual(2, len(files))
            self.assertEqual(files[0].read_bytes(), files[1].read_bytes())
            events = [json.loads(line) for line in (output / "capture-events.jsonl").read_text().splitlines()]
            self.assertEqual(2, sum(event["status"] == "disconnected" for event in events))
            receiving = [event for event in events if event["status"] == "receiving"]
            self.assertEqual("11-15 14:00:00.123", receiving[0]["lastDeviceLine"])
            reconnect = next(event for event in events if event["status"] == "awaiting-data" and event["attempt"] == 2)
            self.assertIsNone(reconnect["lastDeviceLine"])
            self.assertIsNotNone(reconnect["lastDataHostUtc"])
            self.assertEqual("11-15 14:00:00.123", reconnect["lastReceivedDeviceLine"])
            self.assertTrue(all(event["bufferReplay"] and event["possibleGap"] for event in receiving))
            self.assertNotIn("App: test", (output / "capture-events.jsonl").read_text())
            self.assertEqual("stopped", json.loads((output / "capture-status.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()
