@echo off
REM Collects an airline from this machine into the same cloud database everything else uses.
REM
REM Wraps the PowerShell script because Windows refuses to run .ps1 files by default -
REM "running scripts is disabled on this system" - and -ExecutionPolicy Bypass applies to
REM this one invocation only, changing nothing about the machine.
REM
REM   collect-locally.cmd                 collects Transavia
REM   collect-locally.cmd wizzair         collects some other airline
REM   collect-locally.cmd transavia -Rediscover    re-scans the network as well
REM
setlocal
set AIRLINE=%1
if "%AIRLINE%"=="" set AIRLINE=transavia
shift
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect-locally.ps1" -Airline %AIRLINE% %1 %2 %3
endlocal
