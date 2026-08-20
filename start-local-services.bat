@echo off
REM =============================================================
REM  Classroom Translator - Local services launcher
REM  [1] VibeVoice HTTP (whole-session transcribe)  -> port 8001
REM  [2] optional vLLM (real-time streaming ASR)    -> port 8000 (pass "vllm" arg)
REM =============================================================
echo [1/3] Starting VibeVoice transcribe service (8001)...
wsl.exe -d Ubuntu -u root -e bash /opt/start_vibe_http.sh
echo [2/3] Checking 8001...
curl -s --max-time 8 http://127.0.0.1:8001/health
echo.
if "%1"=="vllm" (
    echo [3/3] Starting vLLM streaming ASR (8000)...
    wsl.exe -d Ubuntu -u root -e bash /opt/start-asr.sh
    echo.
    echo Waiting for vLLM to be ready...
    timeout /t 30
    curl -s --max-time 8 -H "Authorization: Bearer sk-asr" http://127.0.0.1:8000/v1/models
    echo.
) else (
    echo.
    echo Tip: run "start-local-services.bat vllm" to also start streaming ASR.
)
echo.
echo Done! Connect phone via ADB reverse:
echo   adb reverse tcp:8001 tcp:8001
echo   adb reverse tcp:8000 tcp:8000
pause
