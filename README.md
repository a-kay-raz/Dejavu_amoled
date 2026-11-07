<p align="center">
  <img src="docs/readme/dejavu-feature-graphic.png" alt="Dejavu Browser" width="640">
</p>

<p align="center">
  <strong>Your tabs, right where you left them.</strong><br>
  An independent Android browser with workspaces, containers and split view.<br>
  <a href="https://dejavubrowser.com"><strong>dejavubrowser.com</strong></a>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.byarchitect.dejavu"><img alt="Google Play" src="https://img.shields.io/badge/Google%20Play-Dejavu-F2B84B?logo=googleplay&logoColor=white"></a>
  <a href="#download"><img alt="iOS: help wanted" src="https://img.shields.io/badge/iOS-help%20wanted-5A452A?logo=apple&logoColor=white"></a>
  <a href="LICENSE"><img alt="License: MPL 2.0" src="https://img.shields.io/badge/license-MPL%202.0-5A452A"></a>
  <a href="https://matrix.to/#/#dejavu-browser:matrix.org"><img alt="Matrix: #dejavu-browser:matrix.org" src="https://img.shields.io/badge/Matrix-%23dejavu--browser-556B4F?logo=matrix&logoColor=white"></a>
  <a href="https://github.com/by-architect/Dejavu/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/by-architect/Dejavu?color=F2B84B"></a>
  <a href="https://buymeacoffee.com/ByArchitect"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-F2B84B?logo=buymeacoffee&logoColor=white"></a>
</p>

Dejavu is a browser that adapts to you. Send any tab to your own apps with one tap, edit every button in the menu,
choose where links from other apps open, and keep work, study and personal browsing apart in workspaces and
containers. Your workspaces, containers and pinned tabs sync with Zen Browser on your computer, or follow Firefox on
all your computers and phones. It runs on the Gecko engine, supports add-ons and tracking protection, and it is
private by default.

Open Dejavu and everything is where you left it, on every device. That feeling of having been here before is where
the name comes from.

<p align="center">
  <img src="docs/readme/dejavu-home.jpg" alt="The home screen with essentials and the Work workspace" width="160">
  <img src="docs/readme/dejavu-menu.jpg" alt="The menu over a page, with its buttons and extensions" width="160">
  <img src="docs/readme/dejavu-history.jpg" alt="The History page" width="160">
  <img src="docs/readme/dejavu-downloads.jpg" alt="The Downloads page" width="160">
  <img src="docs/readme/dejavu-sign-in.jpg" alt="Signing in to sync with a camera" width="160">
</p>

## An independent browser

Dejavu is an independent project. It started as a fork of Firefox for Android (Fenix), Mozilla's open source mobile
browser, but it is not a Firefox product, and it is not made, endorsed or supported by Mozilla.

**Is Dejavu Zen Browser for Android?** No. Zen Browser has no Android app, and Dejavu is not part of Zen or made by the
Zen team. It is a separate Android browser that can sync with Zen: if you use Zen on your computer, your workspaces,
containers and pinned tabs show up on your phone too. Sync is the only thing the two share.

## Download

<a href="https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9"><img src="docs/readme/fdroid-qr.svg" alt="QR code that adds the ByArchitect repository to F-Droid" width="170" align="right"></a>

<p>
  <a href="https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9"><img alt="Get it from the ByArchitect F-Droid repository" src="docs/readme/badge-fdroid-byarchitect.png" height="80"></a>
  <a href="https://play.google.com/store/apps/details?id=com.byarchitect.dejavu"><img alt="Get it on Google Play" src="docs/readme/badge-google-play.png" height="80"></a>
  <a href="https://github.com/by-architect/Dejavu/releases/latest"><img alt="Get it on GitHub" src="docs/readme/badge-github.png" height="80"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/by-architect/Dejavu"><img alt="Get it on Obtainium" src="docs/readme/badge-obtainium.png" height="80"></a>
</p>

Dejavu needs Android 8.0 or newer on a 64-bit phone, which almost every phone from recent years is. Passkeys and
security keys need Android 14 or newer: Dejavu signs in with them through Android's credential manager and the
passkey app you use, like Google Password Manager, Proton Pass or Bitwarden. On older Android, Google's passkey service
only serves the browsers on Google's own list, which Dejavu is not on.

**ByArchitect F-Droid repository (recommended):** add Dejavu's own repository to F-Droid, install Dejavu from it, and
F-Droid keeps it up to date. In F-Droid, open **Repositories**, tap **+** and scan the QR code, or open
[this link](https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9)
on your phone, then search for Dejavu. It offers the same APKs as the GitHub releases, which Dejavu's developer builds
and signs, and gets each new version the day it comes out.

**F-Droid's main repository (not recommended):** Dejavu is also in
[F-Droid's main repository](https://f-droid.org/packages/com.byarchitect.dejavu/), but new versions reach it slowly, often weeks
after they come out. F-Droid builds each version itself from Dejavu's source code, and every update first waits for
F-Droid's volunteers to review it and then for F-Droid's build cycle, which takes long for a browser this large. So
you would miss fixes and new features for weeks. F-Droid signs its version with its own key, so switching between it
and the ByArchitect repository means uninstalling first. Like F-Droid does for Firefox-based browsers, it marks Dejavu
with **Tracking**, as Dejavu connects to Mozilla's services and, unless you turn it off, to Google Safe Browsing.

**APK:** download `dejavu-v<version>-arm64-v8a.apk` from the
[latest release](https://github.com/by-architect/Dejavu/releases/latest). It does not update itself, so use F-Droid
or Obtainium to get new versions.

**Obtainium:** [Obtainium](https://github.com/ImranR98/Obtainium) installs new versions straight from Dejavu's GitHub
releases. With Obtainium installed, open [this link](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/by-architect/Dejavu) on your phone, or
add `https://github.com/by-architect/Dejavu` in Obtainium yourself. If it asks which file to use, pick the one ending
in `arm64-v8a.apk`.

**Check a download:** APKs from GitHub and the ByArchitect F-Droid repository are signed with Dejavu's key, whose
certificate has this SHA-256 fingerprint. Apps like [AppVerifier](https://github.com/soupslurpr/AppVerifier) compare
it with the app on your phone, and each release lists the SHA-256 checksum of every file.

```
AD:2C:4C:E3:BD:3A:A1:30:A4:3C:6E:6F:81:4A:14:7C:91:80:EE:3F:C1:80:A1:89:54:87:06:64:F1:47:F1:4A
```

**Google Play:** get Dejavu from [Google Play](https://play.google.com/store/apps/details?id=com.byarchitect.dejavu). Google signs the Play version with its own key, so
switching between Play and F-Droid or the APK means uninstalling first.

**iOS:** There is no iOS app yet, and none is planned for now. Dejavu is a one-person project, and an iOS version
needs more hands. If you are an iOS developer who would like to help, or want to sponsor it, email
[byarchitect@disroot.org](mailto:byarchitect@disroot.org) or say hello in the [Matrix room](#community).

## Features

Dejavu is made of small things that save you time every day.

### Send any tab to your apps

<img src="docs/readme/dejavu-custom-action.jpg" alt="Ready-made custom actions for Karakeep, Linkwarden, ntfy, Home Assistant and more" width="260" align="right">

Turn any web service into a button. A custom action sends the page you are on, or every tab you selected, to an app
or server you choose: save it to your read-later app, post it in a chat, send it to your phone as a notification or
start a home automation.

- Write the request once: the method, URL, headers and body, like a `curl` command.
- Fill it in with the page's address, title and site, or its workspace, container, folder and the date, using
  variables such as `${websiteurl}`.
- Start from a ready-made example for Karakeep, Linkwarden, linkding, Readeck, Raindrop.io, Memos, ntfy, Gotify,
  Discord, Slack, Telegram, Home Assistant or any webhook.
- Put the action in the menu, on the buttons of each tab, or on the bar you see when you select several tabs.

<br clear="right">

### Make the menu yours

- **Edit every button:** Add, remove and move any button in the menu, add new rows, and put your own custom actions
  between them.
- **Choose your actions bar:** Pick the buttons of the actions bar below every page, Home and Search included, so the
  buttons you use most are always one tap away.
- **One list of actions:** Every action, your own included, is in one list. Put each one on pinned tabs, other tabs,
  folders, the bar for selected tabs, the menu or the actions bar. An action on a folder reaches every tab inside it.

### Open links from other apps where they belong

Choose the workspace and container that links from other apps open in: always the same workspace, the one you saw
last, or a private tab. Back takes you straight back to the app you came from.

### Keep everything in its place

- **Workspaces:** Keep work, study and personal browsing apart. Each workspace has its own icon, color theme and tabs,
  and you swipe between them. A private workspace keeps private tabs in one place.
- **Pinned tabs, essentials and folders:** Pin the tabs you come back to, sort them into folders, and keep your
  favorite sites as essentials at the top of the home screen.
- **Containers:** Tabs in a container keep their own cookies and site data, so you can stay signed in to two accounts
  at once. A workspace can open all its tabs in a container. Temporary containers delete their data after their last
  tab is closed.
- **Split view:** Open a link in split view to see two pages at once, or preview a page without leaving the one you
  are reading.

### And more

- **Sync with Zen and Firefox:** Your workspaces, with their colors and icons, containers, pinned tabs, essentials and
  folders stay the same in Zen on your computer and in Dejavu on your other devices. Unpinned tabs can sync too. Or
  follow Firefox: each of your computers and phones gets its own workspace, with its pinned tabs and tab groups.
- **Private by default:** Telemetry, studies, search suggestions and sponsored content are off until you turn them
  on, and there are no ads.
- **Gecko engine:** Add-ons like uBlock Origin and Dark Reader, Enhanced Tracking Protection, and sync for bookmarks,
  history and passwords through your Mozilla account.

### Set up sync with Zen

1. Sign in to the same Mozilla account in Zen on your computer and in Dejavu.
2. In Zen, open **Settings**, then **Sync**, and turn on sidebar sync.
3. In Dejavu, tap the account button at the top left, tap **Sync with** and choose **Zen**. If Dejavu already has
   workspaces or tabs, choose **Merge** to keep them, or **Start fresh** to replace them with what Zen has.
4. Under **Choose what to sync**, keep **Sync workspaces** on. To sync the tabs that are not pinned as well, turn on
   **Include unpinned tabs** in Zen. It is off in Dejavu at first, like in Zen, and Dejavu turns it on once it finds
   tabs that are not pinned in what Zen syncs, unless you turned it off yourself.

### Set up sync with Firefox

1. Sign in to the same Mozilla account in Firefox and in Dejavu.
2. In Firefox, open **Settings**, then **Sync**, and keep **Open tabs** on.
3. In Dejavu, tap the account button at the top left, tap **Sync with** and choose **Firefox**. If Dejavu already has
   workspaces or tabs, choose **Merge** to keep them, or **Start fresh** to see only what Firefox has open.
4. Each computer and phone with Firefox gets its own workspace: pinned tabs stay pinned and tab groups become folders in
   their colors. Open or close a tab there, and Firefox does the same.

## Community

Dejavu has a chat room on [Matrix](https://matrix.org), an open and free chat network. Come say hello, ask a question,
share your setup or ideas, and hear about new releases first.

<p>
  <a href="https://matrix.to/#/#dejavu-browser:matrix.org"><img alt="Join the chat on Matrix: #dejavu-browser:matrix.org" src="https://img.shields.io/badge/Join%20the%20chat-%23dejavu--browser%3Amatrix.org-556B4F?style=for-the-badge&logo=matrix&logoColor=white"></a>
</p>

Open [#dejavu-browser:matrix.org](https://matrix.to/#/#dejavu-browser:matrix.org) in Element or any other Matrix app.
For bugs and feature requests, please open an issue as explained below, so nothing gets lost in the chat.

## Report a bug or request a feature

Feedback goes through [GitHub issues](https://github.com/by-architect/Dejavu/issues). When you
[open an issue](https://github.com/by-architect/Dejavu/issues/new/choose), pick a template and it starts the title for
you:

| Title starts with   | Use it for                                       |
| ------------------- | ------------------------------------------------ |
| `[BUG]`             | Something is broken or does not work as expected |
| `[FEATURE REQUEST]` | An idea or an improvement                        |

New issues show up on the [Dejavu Roadmap](https://github.com/users/by-architect/projects/1) board, where you can
follow what happens to them.

Please search the existing issues first. If yours is already there, add a thumbs up reaction to it instead of opening
a new one. Security problems should be reported privately, as [SECURITY.md](SECURITY.md) explains.

Pull requests are welcome too. Start the title with the kind of change, `[FIX]`, `[FEATURE]`, `[DESIGN]`,
`[TRANSLATION]` or `[DOCS]`, and link the issue it solves. [CONTRIBUTING.md](CONTRIBUTING.md) has the details.

## Contact

Questions and chat go to the [Matrix room](#community).

For sponsorship, designs, icon packs, themes, or anything that is not a bug report or a feature request, email
[byarchitect@disroot.org](mailto:byarchitect@disroot.org).

## Contributing

Dejavu is open to contributions of every kind, and we would especially love help with design:

- **Design:** screens, flows and small details that make Dejavu simpler and easier to use.
- **Icon packs:** Dejavu draws its own icon set. New icons, alternative icon packs and app icons are very welcome.
- **Themes:** new color themes for workspaces.
- **Translations:** corrections for Dejavu's own texts in your language.
- **Code:** bug fixes and new features.
- **Testing:** try Dejavu on your phone or tablet and tell us what breaks.
- **iOS:** help to build an iOS version of Dejavu. It is not planned yet because it needs more hands.

Send designs, icon packs and themes to [byarchitect@disroot.org](mailto:byarchitect@disroot.org), or open a pull
request. [CONTRIBUTING.md](CONTRIBUTING.md) explains how each of these works and how to build Dejavu.

## Sponsors

Dejavu is independent, free and open source, and it has no ads. If you enjoy it, you can buy us a coffee:

<a href="https://buymeacoffee.com/ByArchitect"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-ByArchitect-F2B84B?logo=buymeacoffee&logoColor=white"></a>

We are also open to sponsors. If you or your company want to support Dejavu, email [byarchitect@disroot.org](mailto:byarchitect@disroot.org) and we will get
in touch.

### Supporters

Members of the crew on Buy Me a Coffee, by level. The list is updated once a month; members who keep their support
private are not shown.

<!-- supporters:start -->
No members yet. Be the first: [join the crew](https://buymeacoffee.com/ByArchitect/membership).
<!-- supporters:end -->

## Build from source

This repository is a fork of Mozilla's Firefox repository, and Dejavu is developed separately from Mozilla. Its engine
does not follow Firefox's releases yet: it is a Firefox 159 Nightly build from September 25, 2026, so fixes Mozilla
makes in Firefox after that do not reach Dejavu until it moves to a newer Firefox. Moving to Firefox's releases, and
then following each one, is planned. The Android app is in `mobile/android/fenix`, and Dejavu's
own code is in the `org.mozilla.fenix.dejavu` package. The steps to build it and run it on a phone are in
[CONTRIBUTING.md](CONTRIBUTING.md#build-dejavu).

## Privacy

Dejavu does not collect your data. The [privacy policy](PRIVACY.md) explains what stays on your device and what the
optional features, like sync, share with Mozilla and your search engine.

## License

Dejavu is licensed under the [Mozilla Public License 2.0](LICENSE), like Firefox. Some third-party parts of the code
have their own licenses, listed in [toolkit/content/license.html](toolkit/content/license.html).

Dejavu is an independent project, not made or endorsed by Mozilla or the Zen Browser team. Firefox is a trademark of
the Mozilla Foundation, and this repository grants no rights to Mozilla's trademarks.
