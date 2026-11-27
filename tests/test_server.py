"""Servidor HTTP (ServerSocket, hilo por conexión): API, estáticos, errores y concurrencia."""

import json
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SERVER_SRC = ROOT / "src" / "com" / "hpc" / "lsdfms" / "server"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        sources = list((ROOT / "src").rglob("*.java"))
        sources += list((ROOT / "tests" / "java" / "com").rglob("*.java"))
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", cls.classes.name, *map(str, sources)],
                       check=True, capture_output=True, text=True)

    # ------------------------------------------------------------ utilidades

    def start_server(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        data = Path(temp.name) / "data"
        data.mkdir()
        subprocess.run(["python3", str(ROOT / "scripts" / "generate_cdr.py"), "--output", str(data),
                        "--files", "2", "--records-per-file", "100", "--seed", "7"],
                       check=True, capture_output=True, text=True)
        proc = subprocess.Popen(
            ["java", "-cp", self.classes.name, "com.hpc.lsdfms.Main", "serve", str(data),
             "--port", "0", "--web", str(ROOT / "web"), "--debounce-ms", "600000"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            # Un padre en segundo plano puede heredar SIGINT ignorado; se restablece para el hook.
            preexec_fn=lambda: signal.signal(signal.SIGINT, signal.SIG_DFL))
        self.addCleanup(self.stop, proc)
        found = {}
        ready = threading.Event()

        def pump():
            for line in proc.stdout:
                if line.startswith("SERVIDOR\t"):
                    found["address"] = line.strip().split("\t")[1]
                    ready.set()
        threading.Thread(target=pump, daemon=True).start()
        self.assertTrue(ready.wait(60), "El servidor no imprimió SERVIDOR")
        host, port = found["address"].rsplit(":", 1)
        self.assertEqual(host, "127.0.0.1")
        self.port = int(port)
        self.base = f"http://127.0.0.1:{self.port}"
        return proc

    @staticmethod
    def stop(proc):
        if proc.poll() is None:
            proc.send_signal(signal.SIGINT)
            try:
                proc.wait(15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()

    def call(self, method, path, body=None, raw=None, headers=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(self.base + path, data=data, method=method,
                                         headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def api(self, method, path, body=None, raw=None, expect=200):
        status, headers, payload = self.call(method, path, body, raw)
        self.assertEqual(status, expect, payload)
        self.assertEqual(headers["Content-Type"], "application/json; charset=utf-8")
        self.assertEqual(headers["Connection"], "close")
        return json.loads(payload.decode("utf-8"))

    def error(self, method, path, expect, body=None, raw=None):
        result = self.api(method, path, body, raw, expect)
        self.assertEqual(list(result), ["error"])
        self.assertIsInstance(result["error"], str)
        return result["error"]

    @staticmethod
    def record(phone, **extra):
        body = {"phone": phone, "timestamp": 1700000000, "service": "VOZ", "cell": 12345,
                "duration": 60, "roaming": "PE"}
        body.update(extra)
        return body

    def find(self, phone):
        return self.api("GET", f"/api/search?col=phone&lo={phone}&hi={phone}")["results"]

    def wait_fresh(self):
        deadline = time.time() + 60
        while time.time() < deadline:
            status = self.api("GET", "/api/status")
            if not status["stale"] and not status["maintenanceRunning"]:
                return status
            time.sleep(0.1)
        self.fail("El mantenimiento no terminó")

    # ------------------------------------------------------------ pruebas

    def test_unit_json_and_parser(self):
        result = subprocess.run(["java", "-cp", self.classes.name,
                                 "com.hpc.lsdfms.server.ServerUnitTest"],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_server_code_avoids_forbidden_apis(self):
        for path in SERVER_SRC.glob("*.java"):
            text = path.read_text()
            self.assertNotIn("com.sun.net.httpserver", text, path.name)
            self.assertNotIn("Executor", text, path.name)
            self.assertNotIn("java.util.concurrent", text, path.name)

    def test_status_shape(self):
        self.start_server()
        status = self.api("GET", "/api/status")
        self.assertEqual(set(status), {"dataVersion", "indexVersion", "stale", "files", "totalRecords",
                                       "activeRecords", "deletedRecords", "queueDepth",
                                       "maintenanceRunning", "needsRecover", "lastMaintenance"})
        self.assertEqual(status["files"], 2)
        self.assertEqual(status["totalRecords"], 200)
        self.assertEqual(status["activeRecords"] + status["deletedRecords"], 200)
        self.assertFalse(status["stale"])
        self.assertFalse(status["needsRecover"])
        self.assertEqual(status["queueDepth"], 0)
        self.assertIsNone(status["lastMaintenance"])

    def test_insert_search_maintenance_flow(self):
        self.start_server()
        before = self.api("GET", "/api/status")
        phone = 59900000001
        created = self.api("POST", "/api/records", self.record(phone), expect=201)
        record = created["record"]
        self.assertEqual(set(record), {"fileId", "offset", "phone", "timestamp", "payloadLen", "service",
                                       "cell", "duration", "roaming", "source"})
        self.assertEqual((record["phone"], record["source"], record["service"], record["cell"]),
                         (phone, "delta", "VOZ", 12345))
        self.assertEqual(created["dataVersion"], before["dataVersion"] + 1)
        status = self.api("GET", "/api/status")
        self.assertTrue(status["stale"])
        self.assertEqual(status["totalRecords"], before["totalRecords"] + 1)
        found = self.api("GET", f"/api/search?col=phone&lo={phone}&hi={phone}")
        self.assertEqual([r["source"] for r in found["results"]], ["delta"])
        self.assertFalse(found["truncated"])
        by_time = self.api("GET", "/api/search?col=timestamp&lo=1700000000&hi=1700000000&limit=500")
        self.assertIn(phone, [r["phone"] for r in by_time["results"]])
        by_size = self.api("GET", f"/api/search?col=size&lo={record['payloadLen']}&hi={record['payloadLen']}")
        self.assertIn(phone, [r["phone"] for r in by_size["results"]])

        self.assertEqual(self.api("POST", "/api/maintenance", expect=202), {"scheduled": True})
        fresh = self.wait_fresh()
        self.assertEqual(fresh["indexVersion"], fresh["dataVersion"])
        self.assertEqual(fresh["totalRecords"], fresh["activeRecords"])
        self.assertEqual(fresh["deletedRecords"], 0)
        last = fresh["lastMaintenance"]
        self.assertEqual(last["indexes"], ["phone", "timestamp", "size"])
        self.assertGreater(last["removedRecords"], 0)
        found = self.api("GET", f"/api/search?col=phone&lo={phone}&hi={phone}")
        self.assertEqual([r["source"] for r in found["results"]], ["index"])
        self.assertEqual(found["indexVersion"], fresh["indexVersion"])

    def test_update_and_delete_flow(self):
        self.start_server()
        phone = 59900000010
        record = self.api("POST", "/api/records", self.record(phone), expect=201)["record"]
        updated = self.api("PUT", "/api/records", self.record(
            phone + 1, fileId=record["fileId"], offset=record["offset"], service="SMS"))
        self.assertEqual(updated["old"], {"fileId": record["fileId"], "offset": record["offset"]})
        self.assertEqual((updated["record"]["phone"], updated["record"]["service"]), (phone + 1, "SMS"))
        self.assertEqual(self.find(phone), [])
        self.assertEqual(len(self.find(phone + 1)), 1)
        new = updated["record"]
        # La referencia anterior ya no es válida.
        self.error("PUT", "/api/records", 409, body=self.record(
            phone, fileId=record["fileId"], offset=record["offset"]))
        path = f"/api/records?fileId={new['fileId']}&offset={new['offset']}"
        deleted = self.api("DELETE", path)
        self.assertTrue(deleted["deleted"])
        self.assertGreater(deleted["dataVersion"], updated["dataVersion"])
        self.assertEqual(self.find(phone + 1), [])
        self.error("DELETE", path, 409)
        self.error("DELETE", "/api/records?fileId=99&offset=0", 400)

    def test_validation_errors(self):
        self.start_server()
        good = self.record(59900000020)
        self.error("POST", "/api/records", 400, raw=b"{not json")
        self.error("POST", "/api/records", 400, raw=b"")
        self.error("POST", "/api/records", 400, raw=b"[1,2]")
        self.error("POST", "/api/records", 400, raw=b"\xff\xfe{}")
        self.error("POST", "/api/records", 400, raw=b'{"phone":1.5}')
        for key in good:
            body = dict(good)
            del body[key]
            self.error("POST", "/api/records", 400, body=body)
        for patch in ({"service": "FAX"}, {"cell": 5}, {"duration": 99999}, {"roaming": "XX"},
                      {"phone": "12"}, {"phone": 0}, {"timestamp": -1}, {"cell": "x"}, {"phone": None}):
            self.error("POST", "/api/records", 400, body={**good, **patch})
        self.error("PUT", "/api/records", 400, body=good)  # faltan fileId y offset
        self.error("PUT", "/api/records", 400, body={**good, "fileId": 1, "offset": -5})
        self.error("DELETE", "/api/records", 400)
        self.error("DELETE", "/api/records?fileId=a&offset=1", 400)
        self.error("GET", "/api/search", 400)
        self.error("GET", "/api/search?col=bogus&lo=1&hi=2", 400)
        self.error("GET", "/api/search?col=phone&lo=1", 400)
        self.error("GET", "/api/search?col=phone&lo=x&hi=2", 400)
        self.error("GET", "/api/search?col=phone&lo=5&hi=2", 400)
        for limit in ("0", "501", "-1", "abc"):
            self.error("GET", f"/api/search?col=phone&lo=1&hi=2&limit={limit}", 400)
        limited = self.api("GET", "/api/search?col=size&lo=0&hi=1000000")
        self.assertEqual((len(limited["results"]), limited["truncated"]), (100, True))
        everything = self.api("GET", "/api/search?col=size&lo=0&hi=1000000&limit=500")
        self.assertEqual((len(everything["results"]), everything["truncated"]),
                         (self.api("GET", "/api/status")["activeRecords"], False))
        self.assertEqual(self.api("GET", "/api/status")["dataVersion"], 0)

    def test_routing_and_protocol_limits(self):
        self.start_server()
        self.error("GET", "/api/nope", 404)
        self.error("GET", "/nope", 404)
        status, headers, _ = self.call("POST", "/api/status", raw=b"{}")
        self.assertEqual((status, headers["Allow"]), (405, "GET"))
        self.error("GET", "/api/maintenance", 405)
        self.error("PATCH", "/api/records", 405)
        self.error("POST", "/static/app.js", 405)
        self.error("POST", "/api/records", 413, raw=b"x" * (1024 * 1024 + 1))
        ok = self.call("POST", "/api/records", raw=b" " * (1024 * 1024 - 20) + b'{"phone":"x"}')
        self.assertEqual(ok[0], 400)  # límite exacto aceptado: falla por validación, no por tamaño
        status, _, payload = self.call("GET", "/api/status", headers={"X-Big": "a" * 20000})
        self.assertEqual(status, 431, payload)
        self.assertIn(b"HTTP/1.1 400", self.raw(b"GARBAGE\r\n\r\n"))
        self.assertIn(b"HTTP/1.1 400", self.raw(b"GET /api/status HTTP/1.1\r\nbad header\r\n\r\n"))
        self.assertIn(b"HTTP/1.1 400", self.raw(b"POST /api/records HTTP/1.1\r\nContent-Length: x\r\n\r\n"))
        self.assertIn(b"HTTP/1.1 200", self.raw(b"GET /api/status HTTP/1.0\r\n\r\n"))
        self.assertIn(b"HTTP/1.1 400", self.raw(b"GET /api/status HTTP/1.1\r\nHost: a"))  # cierre a medias

    def raw(self, data):
        with socket.create_connection(("127.0.0.1", self.port), timeout=20) as sock:
            sock.sendall(data)
            sock.shutdown(socket.SHUT_WR)
            chunks = []
            while chunk := sock.recv(65536):
                chunks.append(chunk)
        return b"".join(chunks)

    def test_static_files(self):
        self.start_server()
        for path, expected in (("/", "text/html"), ("/static/app.js", "text/javascript"),
                               ("/static/app.css", "text/css")):
            status, headers, body = self.call("GET", path)
            self.assertEqual(status, 200, path)
            self.assertTrue(headers["Content-Type"].startswith(expected), headers["Content-Type"])
            self.assertIn("utf-8", headers["Content-Type"])
            name = "index.html" if path == "/" else path.split("/")[-1]
            self.assertEqual(body, (ROOT / "web" / name).read_bytes())
        for path in ("/static/missing.js", "/static/", "/static/../Makefile", "/static/%2e%2e/Makefile",
                     "/static/..%2fMakefile", "/static/%2e%2e%2f%2e%2e%2fMakefile", "/static/.hidden",
                     "/static//etc/passwd", "/static/..\\Makefile"):
            self.assertEqual(self.call("GET", path)[0], 404, path)
        self.assertEqual(self.raw(b"GET /static/../Makefile HTTP/1.1\r\n\r\n")[:12], b"HTTP/1.1 404")

    def test_concurrent_clients_keep_counts_consistent(self):
        self.start_server()
        initial = self.api("GET", "/api/status")
        clients, rounds = 16, 6
        errors, tally = [], {"inserts": 0, "deletes": 0}
        lock = threading.Lock()

        def client(n):
            try:
                inserts = deletes = 0
                for i in range(rounds):
                    phone = 59910000000 + n * 100 + i
                    rec = self.api("POST", "/api/records", self.record(phone), expect=201)["record"]
                    inserts += 1
                    found = self.find(phone)
                    assert [(r["fileId"], r["offset"]) for r in found] == [(rec["fileId"], rec["offset"])], found
                    self.api("GET", "/api/status")
                    if i % 2 == 0:
                        path = f"/api/records?fileId={rec['fileId']}&offset={rec['offset']}"
                        self.api("DELETE", path)
                        deletes += 1
                        assert self.find(phone) == []
                        if i == 0:
                            assert self.call("DELETE", path)[0] == 409
                with lock:
                    tally["inserts"] += inserts
                    tally["deletes"] += deletes
            except BaseException as exc:  # noqa: BLE001 - se informa en el hilo principal
                with lock:
                    errors.append(repr(exc))

        threads = [threading.Thread(target=client, args=(n,)) for n in range(clients)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(120)
        self.assertEqual(errors, [])
        self.assertEqual(tally, {"inserts": clients * rounds, "deletes": clients * rounds // 2})
        final = self.api("GET", "/api/status")
        self.assertEqual(final["totalRecords"], initial["totalRecords"] + tally["inserts"])
        self.assertEqual(final["deletedRecords"], initial["deletedRecords"] + tally["deletes"])
        self.assertEqual(final["activeRecords"],
                         initial["activeRecords"] + tally["inserts"] - tally["deletes"])
        self.assertEqual(final["dataVersion"], initial["dataVersion"] + tally["inserts"] + tally["deletes"])
        self.assertEqual(final["queueDepth"], 0)
        self.assertTrue(final["stale"])

    def test_shutdown_on_sigint(self):
        proc = self.start_server()
        self.api("GET", "/api/status")
        proc.send_signal(signal.SIGINT)
        proc.wait(20)
        self.assertIsNotNone(proc.returncode)
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", self.port), timeout=2).close()


if __name__ == "__main__":
    unittest.main()
