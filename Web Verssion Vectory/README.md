# Nexus Mass Web

Веб-версия мессенджера. Откройте `index.html` через локальный сервер или загрузите папку на хостинг.

## Запуск локально

```bash
python -m http.server 8080
```

Потом открыть: `http://localhost:8080/Web/`

> В превью Arena внешние Firebase CDN могут не загрузиться из-за sandbox без сети. В обычном браузере всё работает.

## Firebase

Используются те же коллекции, что в Android:

- `users`
- `usernames`
- `chats`
- `chats/{chatId}/messages`
- Firebase Storage: `avatars/{uid}.jpg`

Для web-push уведомлений нужен VAPID key из Firebase Console → Cloud Messaging. Вставьте его в `app.js` в переменную `WEB_PUSH_VAPID_KEY`.
