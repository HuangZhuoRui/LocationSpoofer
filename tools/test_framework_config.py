"""Check framework configuration on an adb-connected device without publishing new settings.

Install a debug APK and its matching androidTest APK, enable the module, reboot,
then start a stationary simulation before running this script.
"""
import subprocess

result = subprocess.run(
    ["adb", "shell", "am", "instrument", "-w",
     "com.vincenthzr.locationspoofer.test/com.vincenthzr.locationspoofer.SystemFilesInstrumentation"],
    text=True, capture_output=True,
)
print(result.stdout, end="")
if result.stderr:
    print(result.stderr, end="")
if result.returncode or "result=PASS" not in result.stdout:
    raise SystemExit("Framework configuration check failed; see instrumentation output above")
