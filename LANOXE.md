# LaNoxe

This tree is the Android app. It is a fork of [monocles chat](https://codeberg.org/monocles/monocles_chat) at `63de111ebd70585763d9df63cfc01db5dd4c110e` on `master`. The license stays AGPL-3.0-only. See `LICENSE`.

The app stays this Java codebase. Codename One, Kotlin Multiplatform, and Flutter would replace the XMPP stack this fork exists to keep. Linux, Windows, macOS, and the browser are separate apps. The portable desk is the `lanoxe` command in the LaNoxe repository.

## Desk

`de.monocles.chat.desk.Desk` is the two readings that are new here.

Threads, Board, and Stream are one room. A thread is a post with at least one reply. A post with none stays off that list and in the stream, including your own. The board lists every post. The first two levels are open. A reply under those stays folded until that line is opened, and opening the thread shows the whole tree. Search on the shelf keeps a hit with the posts above and below it. Sort on Threads and Board is activity, replies, newest, or oldest. A double-tap on a stream message opens the board with the posts above it unfolded. Changing the shelf leaves an open thread. Back returns to it.

The same messages are a directory. A thread's files include the files on its replies. Name order is natural, so `shot2` comes before `shot10`. Grouping adds one level of folders: type, month, sender, extension, or thread.

The conversation screen reads the open room through `Desk`. Threads, Board, Stream, and Files are the shelf. Chat on that shelf is the transcript Monocles already drew. A long press on a row opens that message in the next reading.

## Upstream

`upstream` is `https://codeberg.org/monocles/monocles_chat.git`. The first commit here is a snapshot of `63de111ebd70585763d9df63cfc01db5dd4c110e`, not a descendant of it, so a plain merge will not apply. `deps/libsignal` is a submodule. Clone with `--recurse-submodules`.
