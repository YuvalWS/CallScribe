"""Fake RunPod serverless endpoint for testing the app's pipeline without a key or GPU cost.

    python tools/fake_runpod.py app/src/test/resources/runpod_completed.json
    adb reverse tcp:8099 tcp:8099
    adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_FAKE_RUNPOD

Accepts /v2/<endpoint>/run, answers /status with IN_QUEUE, IN_PROGRESS, then the fixture.
Logs what the app sent (payload size, decoded audio size, input fields) to verify the request.
"""
import base64, json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

FIXTURE = json.load(open(sys.argv[1], encoding="utf-8"))
polls = {}


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        inp = json.loads(raw)["input"]
        ta = inp["transcribe_args"]
        audio = base64.b64decode(ta["blob"])
        job = f"job{len(polls) + 1}"
        polls[job] = 0
        print(f"RUN {self.path} payload={n} audio={len(audio)} head={audio[4:8]!r} model={inp['model']} "
              f"engine={inp['engine']} lang={ta.get('language')} diarize={ta.get('diarize')} auth={self.headers.get('Authorization', '')[:12]}…",
              flush=True)
        self._send(200, {"id": job, "status": "IN_QUEUE"})

    def do_GET(self):
        job = self.path.rsplit("/", 1)[-1]
        if job not in polls:
            return self._send(404, {"error": "not found"})
        polls[job] += 1
        if polls[job] == 1:
            return self._send(200, {"id": job, "status": "IN_QUEUE"})
        if polls[job] == 2:
            return self._send(200, {"id": job, "status": "IN_PROGRESS"})
        print(f"COMPLETE {job}", flush=True)
        self._send(200, dict(FIXTURE, id=job))

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", 8099), H).serve_forever()
