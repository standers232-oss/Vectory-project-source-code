const admin = require('firebase-admin');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');

admin.initializeApp();

exports.sendPrivateMessagePush = onDocumentCreated('chats/{chatId}/messages/{messageId}', async (event) => {
  const snap = event.data;
  if (!snap) return;

  const message = snap.data();
  const chatId = event.params.chatId;
  const senderUid = message.senderUid;
  const text = String(message.text || 'Новое сообщение').slice(0, 180);

  const chatSnap = await admin.firestore().collection('chats').doc(chatId).get();
  if (!chatSnap.exists) return;

  const members = chatSnap.get('members') || [];
  const recipientUid = message.recipientUid || members.find((uid) => uid !== senderUid);
  if (!recipientUid || recipientUid === senderUid) return;

  const userSnap = await admin.firestore().collection('users').doc(recipientUid).get();
  if (!userSnap.exists) return;

  const user = userSnap.data();
  if (user.notificationsEnabled === false) return;
  const token = user.fcmToken;
  if (!token) return;

  const senderName = message.senderNick || message.senderUsername || 'Vectory';

  await admin.messaging().send({
    token,
    notification: {
      title: senderName,
      body: text
    },
    data: {
      chatId,
      senderUid: senderUid || '',
      title: String(senderName),
      body: text
    },
    android: {
      priority: 'high',
      notification: {
        channelId: 'nexus_messages',
        sound: 'default',
        priority: 'high',
        defaultVibrateTimings: true,
        defaultLightSettings: true
      }
    }
  });
});
