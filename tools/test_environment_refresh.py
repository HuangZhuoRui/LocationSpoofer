"""Verify live collection refresh on an ADB device using an isolated in-memory database.

Install a debug APK and its matching androidTest APK first.
Set ANDROID_SERIAL when multiple devices are connected.
"""
import subprocess

result = subprocess.run(
    ["adb", "shell", "am", "instrument", "-w", "-e", "check", "environment-refresh",
     "com.vincenthzr.locationspoofer.test/com.vincenthzr.locationspoofer.SystemFilesInstrumentation"],
    text=True, capture_output=True,
)
print(result.stdout, end="")
if result.stderr:
    print(result.stderr, end="")
if result.returncode or "result=PASS" not in result.stdout:
    raise SystemExit("Environment refresh check failed; see instrumentation output above")
