@echo off
rem Starts the Civ V Living World server. The first start fetches Fabric's server launcher
rem (which in turn fetches Minecraft's own server) into this folder. Needs Java 25.
cd /d "%~dp0"
set MINECRAFT=26.3
set LOADER=0.19.5
set INSTALLER=1.1.2
if "%MEMORY%"=="" set MEMORY=4G
java -version 2>&1 | findstr /c:"version \"25" >nul
if errorlevel 1 (
  echo This server needs Java 25. Install a JDK 25 from https://adoptium.net and try again.
  pause
  exit /b 1
)
findstr /b /c:"eula=true" eula.txt >nul 2>&1
if errorlevel 1 (
  echo Read eula.txt first: set eula=true there to accept Minecraft's EULA ^(https://aka.ms/MinecraftEULA^).
  pause
  exit /b 1
)
if not exist fabric-server-launch.jar (
  echo Fetching Fabric's server launcher for Minecraft %MINECRAFT% ...
  curl -fsSL -o fabric-server-launch.jar "https://meta.fabricmc.net/v2/versions/loader/%MINECRAFT%/%LOADER%/%INSTALLER%/server/jar"
  if errorlevel 1 (
    echo The download failed. Check the connection and try again.
    del fabric-server-launch.jar 2>nul
    pause
    exit /b 1
  )
)
java -Xms1G -Xmx%MEMORY% -jar fabric-server-launch.jar nogui
pause
