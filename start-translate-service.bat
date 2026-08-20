@echo off
REM ============================
REM  课堂翻译 - 本地翻译服务 (Qwen3.5-2B llama.cpp)
REM  新一代 2B: 0.5s/句, 质量打平云端, 仅1.28GB
REM  依赖: bench\llama-server.exe + Qwen3.5-2B-Q4_K_M.gguf
REM ============================
cd /d C:\Users\MR\local_translate_research\bench
tasklist /fi "imagename eq llama-server.exe" 2>nul | find /i "llama-server" >nul
if %errorlevel%==0 (
  echo [已在运行] llama-server (8002)
) else (
  echo [启动] llama-server Qwen3.5-2B @8002 ...
  start "llama-translate" llama-server.exe -m Qwen3.5-2B-Q4_K_M.gguf --port 8002 --host 127.0.0.1 -t 16 -c 8192 --no-webui
  echo [完成] 已在后台启动
)
timeout /t 3 >nul
curl -s --max-time 3 http://127.0.0.1:8002/v1/models >nul 2>&1
if %errorlevel%==0 (
  echo [OK] 翻译服务就绪于 http://127.0.0.1:8002/v1
) else (
  echo [警告] 翻译服务未就绪
)
pause
