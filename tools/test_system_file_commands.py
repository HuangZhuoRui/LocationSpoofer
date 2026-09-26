"""Run config publication failure tests in an isolated Android /data/local/tmp tree.
Requires Python 3 and an adb-connected device; never edits live module configuration.
"""
import json
import re
import shlex
import subprocess
import uuid
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / "core-data/src/main/java/com/vincenthzr/locationspoofer/utils/SystemFileCommands.kt").read_text(encoding="utf-8")
functions = re.search(r'private val copyFunctions = """(.*?)""".trimIndent', source, re.S).group(1)
body = re.search(r'fun writeGlobal.*?"""(.*?)""".trimIndent', source, re.S).group(1)
root = "/data/local/tmp/locationspoofer-write-test-" + uuid.uuid4().hex
script = (functions + body).replace("${'$'}", "$")
script = script.replace("${RootManager.CONFIG_SELINUX_TYPE}", "shell_data_file")
script = script.replace("$PARTIAL_FAILURE_MARKER", "PARTIAL_WRITE_FAILED")
script = script.replace("$LOCAL", root + "/data/local/tmp/locationspoofer_config.json").replace("$SYSTEM", root + "/data/system/locationspoofer_config.json").replace("$ROOT", root)

def shell(command, data=None):
    return subprocess.run(["adb", "shell", "sh -c " + shlex.quote(command)], input=data, text=True, capture_output=True)

def check(command):
    result = shell(command)
    if result.returncode: raise RuntimeError(result.stderr + result.stdout)
    return result.stdout.strip()

copies = ["data/local/tmp", "data/system", "data/data/com.vincenthzr.locationspoofer/files", "data/user_de/0/com.android.phone/files", "data/user_de/0/com.android.bluetooth/files"]
results = []
try:
    for failure in ["none", "phone", "bluetooth", "system", "fallback", "phone-missing-files"]:
        check("mkdir -p " + " ".join(shlex.quote(root + "/" + p) for p in copies))
        for p in copies: check("printf old > " + shlex.quote(root + "/" + p + "/locationspoofer_config.json"))
        if failure == "phone-missing-files":
            check("rm " + shlex.quote(root + "/" + copies[3] + "/locationspoofer_config.json") + "; rmdir " + shlex.quote(root + "/" + copies[3]))
        match = {"phone": "*/com.android.phone/*", "bluetooth": "*/com.android.bluetooth/*", "system": "*/data/system/*", "fallback": "*.input.*", "phone-missing-files": "*/com.android.phone/files"}.get(failure, "never-match")
        # Metadata shims avoid privileged operations and inject precise failures.
        shim = "chown() { :; }; chcon() { case \"$*\" in " + match + ") return 1;; esac; return 0; };\n"
        payload = json.dumps({"test": failure})
        result = shell(shim + script, payload)
        # 只有 system_server 副本（或公共副本发布）失败才是致命错误；电话 / 蓝牙副本失败只输出标记
        fatal = failure in ["system", "fallback"]
        assert (result.returncode == 0) == (not fatal), (failure, result)
        if failure in ["phone", "bluetooth", "phone-missing-files"]: assert "PARTIAL_WRITE_FAILED" in result.stderr, (failure, result)
        def read(index): return shell("cat " + shlex.quote(root + "/" + copies[index] + "/locationspoofer_config.json")).stdout.strip()
        assert read(0) == ("old" if failure == "fallback" else payload), failure
        if failure in ["phone", "phone-missing-files", "system"]: assert read(4) == payload, failure
        if failure == "bluetooth": assert read(3) == payload, failure
        if failure == "none": assert all(read(i) == payload for i in range(5))
        assert not check("find " + shlex.quote(root) + " -name '*.tmp.*' -o -name '*.input.*'"), failure
        results.append({"case": failure, "passed": True, "exit": result.returncode})
    print(json.dumps(results, indent=2))
finally:
    assert root.startswith("/data/local/tmp/locationspoofer-write-test-") and len(root.rsplit("-", 1)[1]) == 32
    check("rm -rf " + shlex.quote(root))
