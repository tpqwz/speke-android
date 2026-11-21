# LaNoxe

This tree is the Android app. It is a fork of [monocles chat](https://codeberg.org/monocles/monocles_chat) at `63de111ebd70585763d9df63cfc01db5dd4c110e` on `master`. The license stays AGPL-3.0-only. See `LICENSE`.

The app stays this Java codebase. Codename One, Kotlin Multiplatform, and Flutter would replace the XMPP stack this fork exists to keep. Linux, Windows, macOS, and the browser are separate apps. The portable desk is the `lanoxe` command in the LaNoxe repository.

## Desk

`de.monocles.chat.desk.Desk` is the two readings that are new here.

Threads, Board, and Stream are one room. A post is a thread once it has a reply. Your own new post stays on the thread list before anyone replies. A lone message from someone else opens in the stream. Changing the shelf leaves an open thread. Back returns to it. Search that hits a reply opens that thread. Search that hits a lone message opens the stream.

The same messages are a directory. A thread's files include the files on its replies. Name order is natural, so `shot2` comes before `shot10`. Grouping adds one level of folders: type, month, sender, extension, or thread.

The conversation screen still draws the list Monocles already had. The next change is to drive that screen from `Desk`.

## Upstream

`upstream` is `https://codeberg.org/monocles/monocles_chat.git`. The first commit here is a snapshot of `63de111ebd70585763d9df63cfc01db5dd4c110e`, not a descendant of it, so a plain merge will not apply. `deps/libsignal` is a submodule. Clone with `--recurse-submodules`.
