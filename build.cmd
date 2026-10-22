@echo off
rem ---------------------------------------------------------------
rem Build entry for the HowTodo project.
rem
rem Project root: E:\AI Agent\work area\howtodo  (pure ASCII path)
rem
rem Why not the old folder: the old parent directory name contained
rem Chinese characters. Gradle writes the unit-test worker classpath
rem into an @argfile as UTF-8, while the JVM launcher reads @argfiles
rem using the native codepage (GBK), so Chinese paths were mis-decoded
rem and every test class failed with ClassNotFoundException. The
rem project now lives on an ASCII-only path; no subst drive needed.
rem
rem NOTE: keep this file ASCII-only. cmd.exe parses .bat in the OEM
rem codepage, so UTF-8 Chinese comments get mangled and break parsing.
rem
rem Usage: build.cmd assembleDebug
rem        build.cmd testDebugUnitTest
rem        build.cmd assembleDebug assembleRelease
rem ---------------------------------------------------------------
setlocal

set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "GRADLE_USER_HOME=E:\gradle-home"
set "ANDROID_HOME=E:\engine\androidstudioSDK"

set "GRADLE_BIN=E:\gradle-8.9\bin\gradle.bat"
if not exist "%GRADLE_BIN%" set "GRADLE_BIN=gradle"

cd /d "%HERE%"
call "%GRADLE_BIN%" %* --console=plain --no-daemon --max-workers=1
