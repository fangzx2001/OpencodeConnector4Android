@echo off
:: ================================================
::  OConnector Server Startup Script (Windows)
::  Starts OpenCode in server mode for remote access
:: ================================================
set HOST=0.0.0.0
set PORT=4096

:: Note: APP now auto-discovers all projects, no need to start from a specific directory

echo.
echo   ╔═══════════════════════════════════════════╗
echo   ║     OConnector Server Launcher            ║
echo   ╚═══════════════════════════════════════════╝
echo.
echo   [*] Starting OpenCode server...
echo   [*] Host: %HOST%
echo   [*] Port: %PORT%
echo   [*] Working directory: current directory
echo.
echo   [*] Connect from your phone using:
echo       http://YOUR_PC_IP:%PORT%
echo.
echo   [!] To find your PC's local IP:
echo       Open CMD and run: ipconfig
echo       Look for "IPv4 Address" under your active network adapter
echo.

opencode serve --hostname=%HOST% --port=%PORT%
pause
