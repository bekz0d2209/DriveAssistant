# Drive Assistant

ИИ-помощник по документам на Google Диске: голос и текст (Gemini, бесплатно), папки, загрузка файлов, Google Таблицы и Документы, поиск, перемещение, удаление, отправка в Telegram/почту. Кнопка 📁 открывает обычный файловый менеджер.

APK собирается автоматически: **Releases** → последняя сборка → `DriveAssistant.apk`.

## Настройка (один раз)

**Google Cloud** (console.cloud.google.com):
1. Создать проект.
2. APIs & Services → Library → включить **Google Drive API** и **Google Sheets API**.
3. OAuth consent screen → External → название + e-mail → в **Test users** добавить свой Gmail.
4. Credentials → Create credentials → OAuth client ID → тип **Android**:
   - Package name: `uz.bekzod.drive`
   - SHA-1: `82:D7:94:80:DF:68:60:86:02:9A:44:08:85:D8:CD:AA:37:67:CB:E6`

**Gemini**: aistudio.google.com/apikey → Create API key → вставить в приложении (⚙).
