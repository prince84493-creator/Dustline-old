@echo off
rem Wrapper launcher: uses gradle\wrapper\gradle-wrapper.jar when present, otherwise the Gradle (8.13+) on PATH.
set APP_HOME=%~dp0
if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  exit /b %ERRORLEVEL%
)
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo No gradle-wrapper.jar and no gradle on PATH. Install Gradle 8.13+ or open the project in Android Studio.
exit /b 1
