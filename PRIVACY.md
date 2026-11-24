# Dejavu Privacy Policy

Effective date: November 20, 2026

Dejavu is a web browser for Android, built from Mozilla's open source Firefox code. It is an independent project,
not made, endorsed or supported by Mozilla. This policy explains what happens to your data when you use it.

**The short version: we do not collect your data.** The app sends nothing to Dejavu's developers, and it has no
analytics or advertising services turned on. What you do in Dejavu stays on your device, except for what has to go to
other services to work. Every connection Dejavu makes is listed below, with what it sends and how to turn it off.

## What stays on your device

Dejavu stores your browsing history, bookmarks, open tabs, workspaces, pinned tabs, essentials, folders, containers,
saved logins, addresses and cards, cookies and site data, downloads and settings on your device.

You can delete this data in **Settings**, then **Delete browsing data**. Deleting your history also deletes what
Dejavu keeps of the pages you visited: the workspace each page was opened in, which History can group pages by, and
the page a closed pinned tab reopens on. Uninstalling Dejavu deletes all of it.

## What we collect

Nothing. You do not need an account with us, Dejavu sends us no usage data or crash reports, and it does not use the
advertising ID of your device. If you choose to sign in with a Mozilla account, your email address goes to Mozilla,
not to us, as described in [Mozilla account and sync](#mozilla-account-and-sync-optional).

## Every connection Dejavu makes

Like any connection on the internet, each one shows your IP address to the service it goes to. Below is what else each
one receives, and how to turn it off where you can.

### Websites you visit

When you open a website, it receives what every browser sends: your IP address, the address of the page, the cookies
it saved before and details about the browser. Each website has its own privacy policy.

Dejavu's tracking protection blocks trackers, tracking content, email trackers, cryptominers and known fingerprinters in
every tab, keeps each site's cookies apart from the others, deletes the data of trackers that send you through their
sites on the way to another one, and removes tracking parameters from the links you open. In private tabs it also makes
your phone harder for sites to recognize. Dejavu also tells websites that you do not want your data sold or shared
(Global Privacy Control). You can change each of these in **Settings**, under **Privacy and security**, then **Tracking
protection**, and if a site does not work, you can turn protection off for that site with the shield in the address bar.

Dejavu connects only to the sites you open. It does not connect ahead to the site the address bar suggests while you
type, to links from other apps before they open, or to links that pages mark to load ahead.

Calls in websites, which use WebRTC, connect only through the site's relay servers, so neither the site nor the people
you call learn your IP address or your addresses on your local network. If a call does not connect, you can turn
**Block direct call connections** off in **Settings**, under **Privacy and security**, then **Tracking protection**.
The people you call and the sites that make calls can then see your IP address, and sites you let use your camera or
microphone can also see your addresses on your local network.

### Looking up website addresses (DNS over HTTPS)

To open a website, Dejavu first looks up the address of its server. It does this over an encrypted connection to Quad9,
a non-profit DNS service, so your network and internet provider cannot see or change which sites you look up. Quad9
receives the names of the sites you look up. It describes how it handles data in the
[Quad9 Privacy Policy](https://quad9.net/privacy/policy/). Dejavu uses Max Protection: when Quad9 cannot be reached,
it warns you before it uses your network's DNS for a site. You can pick another provider, let Dejavu fall back to your
network's DNS without a warning, or turn this off in **Settings**, under **Privacy and security**, then **Tracking
protection**.

### Warnings for dangerous sites

Dejavu warns you about pages that Google lists as phishing, malware or unwanted software, using Google Safe
Browsing. It regularly downloads lists of shortened fingerprints ("hashes") of dangerous addresses from Google and
checks the pages you open against them on your device. Only when a page matches the start of a fingerprint does Dejavu
ask Google about it, and then it sends only that start, never the address of the page. Google describes how it handles
data in the [Google Privacy Policy](https://policies.google.com/privacy).

You can turn this off in **Settings**, under **Privacy and security**, then **Tracking protection**, with **Google Safe
Browsing**. Dejavu then
neither downloads Google's lists nor asks Google about pages, and no longer warns about these sites.

A build of Dejavu made without Dejavu's Safe Browsing key, like a build from the source code or one that F-Droid makes
itself, makes none of these connections and has none of these warnings. Its **Google Safe Browsing** switch says so and
stays off.

### Search

When you search from the address bar, your search goes to the search engine you chose. Search suggestions, which send
what you type while you type, are off unless you turn them on in **Settings**, then **Search**.

The list of search engines, their icons and their order come with each version of Dejavu, from the Firefox code it is
built on, so Mozilla cannot change them remotely. Dejavu does not download them, and it does not run Firefox's scripts
that measure search result pages.

### Site icons

Dejavu gets a site's icon from the site itself, when you open it. Until then, like for synced tabs you have not opened
on your phone, it shows the first letter of the site's name.

### Security updates of the browser engine

Dejavu is built on Firefox's engine, which downloads from Mozilla's Remote Settings service what it needs to stay
secure: the lists of revoked website certificates, intermediate certificates and blocked add-ons, and the lists of
trackers that Enhanced Tracking Protection blocks, of tracking parameters it removes from links, and of sites it lets
through so they keep working. These downloads are the same for everyone and carry nothing about you. They are described in the [Firefox Privacy Notice](https://www.mozilla.org/privacy/firefox/).

For websites with an extended validation certificate, the engine also asks the company that issued the certificate
whether it is still valid, which shows that company the site you open.

### Translations

When you translate a page, Dejavu downloads the language models it needs from Mozilla's Remote Settings service. The
page is translated on your device and is not sent anywhere.

### Mozilla account and sync (optional)

If you sign in with a Mozilla account, Firefox Sync keeps your data the same on your devices. To sign you in, Dejavu
sends your email address and password to Mozilla, and you can create a new account on the same page. After you sign in, Dejavu
syncs everything your account syncs, like bookmarks, history, passwords, payment cards and open tabs, unless you turn
it off under **Choose what to sync**. Dejavu also syncs your workspaces, containers, pinned tabs, essentials and
folders, so they match Zen Browser and Dejavu on your other devices.

Your other tabs, the ones that are not pinned, sync with Zen only while **Include unpinned tabs** is on. Like in Zen,
it is off at first. When another device on your account already syncs these tabs, Dejavu turns it on too, so your
devices keep matching, unless you turned it off yourself. You can turn it on or off under **Choose what to sync**.

To tell what changed between syncs, Dejavu keeps a copy of what it last synced on your device, the addresses and titles
of synced tabs included. This copy is deleted when you sign out.

Synced data is stored on Mozilla's servers, encrypted with keys that only your devices have, so Mozilla cannot read
it. Your account is handled by Mozilla, as described in the
[Mozilla Accounts Privacy Notice](https://www.mozilla.org/privacy/mozilla-accounts/). Signing out stops all of this.

### Add-ons

Finding, installing and updating add-ons uses Mozilla's add-ons site, addons.mozilla.org. Add-ons you install can
read and change web pages as their permissions allow, and they have their own privacy policies.

A fresh install of Dejavu adds uBlock Origin, an open source blocker of ads and trackers, from addons.mozilla.org. It
works on your device and downloads the lists of what it blocks from the websites of the people who make them. You can
turn it off or remove it in **Extensions**.

### Location

When you let a website know your location, Dejavu gets it from your phone's GPS only. It does not use Android's network
location, which on phones with Google Play Services sends the Wi-Fi networks and cell towers near you to Google. If GPS
is too slow or does not work indoors, you can turn **Location from GPS only** off in **Settings**, under **Privacy and
security**, then **Tracking protection**, and Android may then ask Google. A website gets your location only after you
allow it, and you can take that back in **Site settings** on the same screen.

### Telemetry, experiments and crash reports

Dejavu sends no telemetry, usage pings or crash reports, to us or to Mozilla. Firefox's data collection settings are
not shown, and when Dejavu crashes it writes no crash report on your device and sends nothing. It does not download
Mozilla's lists of crashes it would like reports of either.

Dejavu downloads no studies or experiments from Mozilla and takes part in none, also not in the few that come built
into Firefox's code.

### What Dejavu does not connect to

Dejavu does not look up your country or location to pick search engines, does not use push messaging services, and
shows no sponsored shortcuts or sponsored stories, which it does not download either. It does not test your connection
with Mozilla's servers, as Firefox does when it starts and when your network changes, and it shows emoji with a font
that comes with the app, so it does not ask Google Play Services for one.

Dejavu's developers run the website dejavubrowser.com and the ByArchitect F-Droid repository, but the app itself does
not connect to them. The website loads nothing from other sites, apart from asking GitHub's API for the newest release,
so its download button gets the newest APK. The F-Droid repository's server sees your IP address when the F-Droid app
checks it for updates.

## Deleting your account and data

Dejavu's developers keep no account or data of yours, so there is nothing to delete on our side. The only account you
can use in Dejavu is a Mozilla account, which Mozilla keeps. To delete it:

1. In Dejavu, open **Settings** and tap your account at the top.
2. Tap **Manage account**. Your Mozilla account settings open.
3. At the bottom, tap **Delete account** and follow the steps.

Mozilla then deletes your account, your email address and the synced data stored on its servers. What Mozilla keeps,
and for how long, is described in the [Mozilla Accounts Privacy Notice](https://www.mozilla.org/privacy/mozilla-accounts/).
You can also delete your account without Dejavu at <https://accounts.firefox.com/settings>.

Deleting your Mozilla account does not delete what Dejavu stores on your device. To delete that, open **Settings**,
then **Delete browsing data**, or uninstall Dejavu.

## Permissions

Dejavu asks for Android permissions only when a feature or a website you use needs them, and you can say no:

- **Location:** for websites you allow to know your location.
- **Camera and microphone:** for websites you allow, like video calls, and for scanning QR codes.
- **Notifications:** for downloads, media that is playing and websites you allow.
- **Biometrics:** to unlock saved logins and cards with your fingerprint or face.
- **Files and media:** to save downloads and to upload files to websites.
- **Apps on your device:** to open links in the apps that can handle them, and to install app packages you download.

Location, camera and microphone data goes only to the website you allow. Dejavu does not keep it.

## Children

Dejavu is a general web browser, not made for children. We do not knowingly collect data from anyone, including
children.

## Changes to this policy

When this policy changes, the new version is published in this file with a new effective date. The history of this
file shows every change.

## Contact

For questions about privacy, open an issue at <https://github.com/by-architect/Dejavu/issues>, or write to the contact
email on Dejavu's Google Play page for private questions. To report a security problem, see [SECURITY.md](SECURITY.md).
