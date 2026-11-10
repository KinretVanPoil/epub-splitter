@echo off
chcp 65001 > nul
echo ========================================================
echo   Відправка EPUB Splitter на GitHub для компіляції APK
echo ========================================================
echo.
set /p REPO_URL="Вставте посилання на ваш GitHub репозиторій (HTTPS): "

if "%REPO_URL%"=="" (
    echo Помилка: посилання не введено.
    pause
    exit /b
)

git remote remove origin 2>nul
git remote add origin %REPO_URL%
git branch -M main
echo.
echo Відправка коду на GitHub...
git push -u origin main

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo   Успішно відправлено!
    echo   Тепер відкрийте вкладку 'Actions' у вашому репозиторії:
    echo   там вже почалась компіляція вашого APK файлу!
    echo ========================================================
) else (
    echo.
    echo Виникла помилка під час відправки. Перевірте посилання або авторизацію.
)

pause
