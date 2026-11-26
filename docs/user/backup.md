# Making a backup of Speke

This tutorial explains how you can back up your Speke data.

**WARNING**: Do not restore a backup (and especially its OMEMO keys) in an attempt to clone (run simultaneously) an installation. Restoring a backup is only meant for migrations or in case you’ve lost the original device.

1. Make sure that you know the password to your account(s)! The backup is encrypted with your account password and you will need it later to restore it. The password must also be saved in the app: accounts without a saved password are skipped and get no backup file. If you use a [recovery key](#recovery-key) instead, have that key ready; then every account gets a backup.
2. On the chat screen, tap on the three dot menu in the upper right corner and go to *Settings* → *Backup*.
3. Tap on *Create backup*.
4. When asked "Do you want to disable your accounts before making a backup (recommended)?", tap *Yes*.
5. Wait until the notification tells you that the backup is finished.
6. Move the backup to whatever location you feel safe with. By default you will find it in *Documents/Speke/Backup*. There is one file per account, named after the account and the date, e.g. *kim@example.org.2026-11-19-14-30.ceb*.
7. Re-enable your account(s): three dot menu → *Manage accounts*, then switch your account(s) back on.

Done!

## Further options
### Recurring backups and backup location
In *Settings* → *Backup* you can enable a *Recurring backup* so that Speke creates backups automatically. Recurring backups do not disable your accounts. With *Backup location* you can choose another folder for your backups. Only the three newest backups per account are kept; older ones are deleted automatically.

### Encrypted media on the device
Pictures, videos, voice messages and files in the internal hidden storage are stored encrypted on the device, like your messages, and so are avatars, chat wallpapers and stickers (*Settings* → *Attachments* → *Encrypt media on this device*, on by default; existing files are encrypted in the background). The key lives in the encrypted message database and never leaves the device: a backup contains your attachments decrypted, but inside the backup's own encryption, and restoring encrypts them again with the key of the new device. So a backup can be restored on any device, and its security only depends on your password or recovery key.

### Locking the app
With *Require password on startup* (*Settings* → *Security*) Speke can lock itself: *Lock automatically* after a while in the background, or *Lock now* in the menu of the chat list. Locking closes the app and wipes the keys of the database and the media from memory, so the password is needed again. While locked, no messages are received or notified; your server keeps them until you unlock.

### In-app camera
Photos taken with the in-app camera are kept in memory and stored encrypted right away, so no unencrypted copy is ever written. Videos are recorded into a private temporary file that is deleted as soon as the video is stored encrypted. You can switch to the system camera app in *Settings* → *Attachments*; it writes an unencrypted file first.

### Recovery key
By default a backup is encrypted with the password of its account. In *Settings* → *Backup* → *Recovery key* you can switch to a random recovery key instead (like Signal does): 64 characters that the app shows you once. It is much stronger than a password, also works for accounts without a saved password, and restoring starts faster. You confirm the key by typing its last 8 characters; later you can show it again (after confirming your screen lock), replace it or turn it off.

**Keep the recovery key safe.** Without it, backups made with it can't be restored, and nobody can recover it for you. When you create a new key, older backups still need the old one. Backups made with a recovery key can't be restored by older app versions or by Conversations. When restoring, Speke asks for the recovery key instead of the password.

### Export plain text
If *Export plain text* is enabled, your messages are additionally exported **unencrypted** in human readable plain text: one file per chat in a new folder per account and export inside the backup location (e.g. *kim@example.org.2026-11-20-14-30-05.plaintext/alex@example.org.txt*). Like the encrypted backups, the three newest exports per account are kept and older ones are deleted; an export that fails or is cancelled leaves the previous ones untouched. Plain-text files written by older versions into a folder named only after your account are not removed automatically. Characters in chat addresses that not every storage accepts are replaced in the file names. Only text messages and links to files are exported; disappearing messages are left out. Everyone with access to these files can read your chats, so only enable it if you really need it and keep the files safe. The app asks you to confirm before enabling it.

### Settings
Your app settings are not part of the chat backup. Use *Export settings* in the *Settings* section of the backup screen to save them to a separate file (*…_settings.json*) in your backup location, next to your backup files, and *Import settings* to load them again.

For your safety, this file only contains general settings like appearance, attachments and notifications. Security and privacy settings (e.g. encryption, DANE, TLS, Tor, DNS, typing and read notifications, link previews, crash reports), the app lock, the database password and the backup location are never included; set them again after importing. Settings files from older versions (*settings.dat*) can no longer be imported, export your settings again instead.

### Attachments
The backup contains your accounts, chats and the required encryption keys (OMEMO and OMEMO2). Your contact list is not part of it; it is loaded from your server again. By default it also contains your attachments (pictures, videos, voice messages and other files), so the backup file can get quite large.

This only applies to files stored in the app's hidden internal storage, which is the default (*Settings* → *Attachments* → *Store media in internal hidden storage*). If you turned that option off, your files are stored in the external (public) storage instead and are **not** part of the backup. They are kept in *Documents/Speke* (subfolders *pictures*, *videos*, *audios* and *documents*). Copy these folders separately or download the files again. Files of disappearing messages are never included. Avatars are not part of the backup either; they are loaded from the server again once your account is connected.

## Troubleshooting
### Unable to decrypt
The backup includes your OMEMO keys. Due to forward secrecy you will not be able to recover messages that were sent and received between creating the backup and restoring it. If your server keeps an archive (MAM), those messages will be retrieved but displayed as *unable to decrypt*. For technical reasons you might also lose the first message you send or receive in each chat after the restore. It will show up as *unable to decrypt* as well, but the encrypted session heals itself automatically afterwards. None of this happens if you just move to a new device and no messages were exchanged between backup and restore.

In the vast majority of cases you won’t have to manually delete OMEMO keys or do anything like that.
