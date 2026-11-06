const XLINK = "http://www.w3.org/1999/xlink";
// The address a link element points to, or "" when it is no web link or only jumps within this page.
const hrefOf = (a) => {
  const raw = typeof a.href === "string" ? a.getAttribute("href") : (a.href && a.href.baseVal) || a.getAttributeNS(XLINK, "href");
  if (!raw || raw.trim().startsWith("#")) return "";
  let url;
  try {
    url = new URL(raw, document.baseURI);
  } catch (_) {
    return "";
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return "";
  if (url.hash && url.href.split("#")[0] === location.href.split("#")[0]) return "";
  return url.href;
};
const isLink = (node) =>
  node instanceof Element &&
  (((node.localName === "a" || node.localName === "area") && node.hasAttribute("href")) ||
    (node instanceof SVGAElement && (node.hasAttribute("href") || node.hasAttributeNS(XLINK, "href"))));
// The address of the first link among nodes, which go from the innermost one outwards.
const linkIn = (nodes) => {
  for (const node of nodes) {
    if (isLink(node)) return hrefOf(node);
  }
  return "";
};
// A node and its ancestors, through the hosts of shadow roots.
function* ancestors(node) {
  for (let n = node; n; n = n.parentNode || n.host) yield n;
}
// composedPath() also reaches into shadow roots, where target.closest() stops at the host.
const linkOf = (e) => linkIn(e.composedPath());
const send = (message) => browser.runtime.sendNativeMessage("dejavuMouse", message).catch(() => {});
let hovered = "";
const hover = (url) => {
  if (url !== hovered) {
    hovered = url;
    send({ type: "hover", url });
  }
};
// Only events the user made count: a page can make up a middle-click, or a mouse moving over a link, to open tabs or to
// show an address of its choice, but it cannot mark them as trusted.
const listen = (type, listener) => document.addEventListener(type, (e) => e.isTrusted && listener(e), true);
listen("pointerover", (e) => e.pointerType === "mouse" && hover(linkOf(e)));
// Leaving a link for anything outside a link, or for outside the page, hides the address.
listen("pointerout", (e) => e.pointerType === "mouse" && hover(e.relatedTarget ? linkIn(ancestors(e.relatedTarget)) : ""));
listen("mousedown", (e) => e.button === 1 && linkOf(e) && e.preventDefault());
listen("auxclick", (e) => {
  const url = e.button === 1 ? linkOf(e) : "";
  if (url) {
    e.preventDefault();
    send({ type: "open", url });
  }
});
// A page that goes away under a mouse that stays put gets no pointerout, so its address would stay.
window.addEventListener("pagehide", () => hover(""));
document.addEventListener("visibilitychange", () => document.hidden && hover(""));
