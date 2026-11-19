@echo off
cd /d "%~dp0\.."
if exist bijnor-upload.jks ( echo bijnor-upload.jks already exists - not overwriting it. & exit /b 1 )
where keytool >nul 2>nul
if errorlevel 1 ( echo keytool not found - install JDK 17 ^(Android Studio ships one^). & exit /b 1 )
set /p PW=Choose a keystore password (min 6 characters): 
keytool -genkeypair -v -keystore bijnor-upload.jks -alias bijnor -keyalg RSA -keysize 2048 -validity 10000 -storepass "%PW%" -keypass "%PW%" -dname "CN=Bijnor, O=Bijnor"
if errorlevel 1 exit /b 1
(
echo storeFile=bijnor-upload.jks
echo storePassword=%PW%
echo keyAlias=bijnor
echo keyPassword=%PW%
) > keystore.properties
echo.
echo Created bijnor-upload.jks and keystore.properties.
echo BACK THEM UP somewhere safe and never upload them to GitHub.
