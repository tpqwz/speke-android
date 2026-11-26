# Speke

This tree is the Android app. It is a fork of [monocles chat](https://codeberg.org/monocles/monocles_chat) at `63de111ebd70585763d9df63cfc01db5dd4c110e` on `master`. The license stays AGPL-3.0-only. See `LICENSE`.

The app stays this Java codebase. Codename One, Kotlin Multiplatform, and Flutter would replace the XMPP stack this fork exists to keep. Linux, Windows, macOS, and the browser are separate apps. The portable desk is a separate project and is not this app.

## Desk

`de.monocles.chat.desk.Desk` is the reading that is new here.

Threads and Chat are one room. A thread is a post with at least one reply. A post with none stays off that list and opens in Chat, including your own. Opening a thread shows the whole tree. Search on the shelf keeps a hit with the posts above and below it. Sort on the thread list is activity, replies, newest, or oldest. That list is compact or card. Compact is one line. Card shows the post, and an https link in it is drawn as a card with the site and the address. A link in Chat is drawn as a site card on this device. The page title stays off an encrypted stanza. The toolbar back from an open thread, or from Chat opened on a post with no replies, returns to the thread list. A second back leaves the conversation. Tapping Threads while a thread is open closes it without stacking that return.

The same messages are a directory. A thread's files include the files on its replies. Name order is natural, so `shot2` comes before `shot10`. Grouping adds one level of folders: type, month, sender, extension, or thread. Opening a file opens that message in Chat, and back returns to the directory. Back from a folder returns to the folder list.

The conversation screen reads the open room through `Desk`. Opening a conversation shows Chat, the transcript. Threads, Files, Chat, Night, Original, and Plain are on a floating button. Night is the dark page. Original keeps the floating bar, the wallpaper, and the app's own colors. Plain is that same bar on a solid page.

The bottom bar is Chats, Calls, RSS, and Podcasts. RSS and Podcasts share one list of feed addresses kept on the phone. RSS shows each item's link. Podcasts shows items that include audio. The same feed can appear in both. Sharing sends the link, or the episode's audio address, into a chat you pick. An episode plays in the in-app player under the toolbar. That player can pause and skip 15 seconds, and the episode name is its title.

## Upstream

`upstream` is `https://codeberg.org/monocles/monocles_chat.git`. The first commit here is a snapshot of `63de111ebd70585763d9df63cfc01db5dd4c110e`, not a descendant of it, so a plain merge will not apply. `deps/libsignal` is a submodule. Clone with `--recurse-submodules`.
