@echo off
chcp 65001 > nul
cd /d "%~dp0"

echo ========================================================
echo   Відправка EPUB Splitter на GitHub для компіляції APK
echo ========================================================
echo.
echo Репозиторій: https://github.com/KinretVanPoil/epub-splitter.git
echo.
echo Відправка файлів на GitHub...
git push -u origin main

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo   УСПІШНО ВІДПРАВЛЕНО!
    echo.
    echo   Відкрийте посилання у браузері:
    echo   https://github.com/KinretVanPoil/epub-splitter/actions
    echo.
    echo   Там вже почалася компіляція вашого APK-файлу!
    echo ========================================================
) else (
    echo.
    echo Виникла помилка або очікується авторизація в браузері.
)

echo.
pause
