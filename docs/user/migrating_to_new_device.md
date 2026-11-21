# Migrating to a new device

This tutorial explains how you can transfer your monocles chat data from an old to a new device. It assumes that you do not have monocles chat installed on your new device yet. It basically consists of three steps:

1. Make a backup (old device)
2. Move that backup to your new device
3. Restore the backup (new device)

**WARNING**: Do not restore a backup (and especially its OMEMO keys) in an attempt to clone (run simultaneously) an installation. Restoring a backup is only meant for migrations or in case you’ve lost the original device.

## 1. Make a backup (old device)
1. Make sure that you know the password to your account(s)! The backup is encrypted with your account password and you will need it later to restore it. The password must also be saved in the app: accounts without a saved password are skipped and get no backup file. If you set up a recovery key (*Settings* → *Backup* → *Recovery key*), you need that key instead of the password, and every account gets a backup.
2. On the chat screen, tap on the three dot menu in the upper right corner and go to *Settings* → *Backup*.
3. Tap on *Create backup*.
4. When asked "Do you want to disable your accounts before making a backup (recommended)?", tap *Yes*.
5. Wait until the notification tells you that the backup is finished.

Optionally, tap *Export settings* on the same screen if you want to take your app settings with you as well. The *…_settings.json* file is saved next to your backup files; copy it along with them. It only contains general settings, so you will need to set your security and privacy settings again on the new device.

## 2. Move that backup to your new device
1. Locate the backup. By default you will find it in your Files in *Documents/monocles chat/Backup* (or in the folder you chose as *Backup location*). The file is named after your account and the date (*e.g. kim@example.org.2026-11-19-14-30.ceb*). If you have multiple accounts, you will find one file for each.
2. Use your USB cable or bluetooth, your Nextcloud or other cloud storage or pretty much anything you want to copy the backup from the old device to the new device.
3. Remember the location you saved your backup to. For instance, you might want to save it to the *Download* folder.

## 3. Restore the backup (new device)
1. Install monocles chat on your new device.
2. Open monocles chat for the first time.
3. On the welcome screen, tap on *Restore backup*.
4. If your backup files are not listed, tap on the cloud symbol (*Open backup*) in the upper right corner to choose the file from where you saved it.
5. Enter your account password (or your recovery key, if the backup was made with one), leave *Restore OMEMO keys* switched on and tap *Restore*.
6. Activate your account: three dot menu → *Manage accounts*, then switch your account back on.
7. Check if chats work.

If you have more than one account, restore the remaining backups via *Manage accounts* → three dot menu → *Restore backup*. If you exported your settings, load them via *Settings* → *Backup* → *Import settings*, then check your security and privacy settings, as these are not part of the file.

Once you have confirmed that the new device is running fine, you can uninstall the app from the old device.

Note: The backup contains your accounts, chats, the required encryption keys and, by default, your attachments (pictures, videos, voice messages and other files). This only applies to files stored in the app's hidden internal storage, which is the default (*Settings* → *Attachments* → *Store media in internal hidden storage*). If you turned that option off, your files are stored in the external (public) storage and are not part of the backup. They are kept in *Documents/monocles chat* (subfolders *pictures*, *videos*, *audios* and *documents*). Copy these folders to the same location on the new device, or download the files again. Files of disappearing messages are never included. Your contact list and avatars are not part of the backup; they are loaded from your server again once your account is connected.

Tip: Backups created by Conversations can be restored in monocles chat the same way. Enter the account password as used there. Such backups do not contain attachments, so those need to be downloaded again.

Done!

## Troubleshooting
### Unable to decrypt
The backup includes your OMEMO keys. Due to forward secrecy you will not be able to recover messages that were sent and received between creating the backup and restoring it. If your server keeps an archive (MAM), those messages will be retrieved but displayed as *unable to decrypt*. For technical reasons you might also lose the first message you send or receive in each chat after the restore. It will show up as *unable to decrypt* as well, but the encrypted session heals itself automatically afterwards. None of this happens if you just move to a new device and no messages were exchanged between backup and restore.

In the vast majority of cases you won’t have to manually delete OMEMO keys or do anything like that.

### The old device is still in use
If you want to keep using the old installation, switch *Restore OMEMO keys* off when restoring. The new device then creates its own OMEMO keys instead of copying the old ones, so both installations can be used at the same time.
