@rem Gradlew wrapper for XAPK Installer
@echo off
set DIRNAME=%~dp0
if "%OS%"=="Windows_NT" setlocal
set GRADLE_USER_HOME=%USERPROFILE%\.gradle
"%JAVA_HOME%/bin/java" -jar "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" %*
