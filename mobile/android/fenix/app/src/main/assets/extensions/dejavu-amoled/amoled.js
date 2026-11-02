/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Dejavu: AMOLED pages, dark mode only. The script does nothing unless the
 * page reports prefers-color-scheme dark, so light mode stays exactly as it
 * was. It never uses a filter or inverts anything: light backgrounds become
 * black, dark text becomes light with its hue kept, and pictures, video,
 * canvas, SVG and elements with a background picture are left untouched.
 */

const PORT_NAME = "dejavuAmoled";
const STYLE_ID = "dejavu-amoled";
const SKIPPED_TAGS = new Set([
  "IMG", "VIDEO", "CANVAS", "SVG", "IFRAME", "OBJECT", "EMBED",
  "SCRIPT", "STYLE", "LINK", "META", "HEAD", "NOSCRIPT",
]);

let port = null;
let applied = false;
let disabled = false;
let observer = null;
let fullRecolorTimer = null;
const changed = [];

function scheduleFullRecolor() {
  if (fullRecolorTimer !== null || disabled || !document.body) return;
  fullRecolorTimer = setTimeout(() => {
    fullRecolorTimer = null;
    if (!disabled && document.body) recolorRoot(document.body);
  }, 150);
}

function parseRgb(value) {
  const m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)$/.exec(value);
  if (!m) return null;
  return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] };
}

function luminance(c) {
  return (0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b) / 255;
}

function rgbToHsl(c) {
  const r = c.r / 255, g = c.g / 255, b = c.b / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return { h: 0, s: 0, l };
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h = 0;
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return { h: h / 6, s, l };
}

function hslToRgb(h, s, l) {
  if (s === 0) {
    const v = Math.round(l * 255);
    return { r: v, g: v, b: v };
  }
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const hue = t => {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };
  return {
    r: Math.round(hue(h + 1 / 3) * 255),
    g: Math.round(hue(h) * 255),
    b: Math.round(hue(h - 1 / 3) * 255),
  };
}

function setImportant(el, property, value) {
  if (
    el.style.getPropertyValue(property) === value &&
    el.style.getPropertyPriority(property) === "important"
  ) {
    return;
  }
  el.style.setProperty(property, value, "important");
  changed.push([el, property]);
}

function recolorElement(el) {
  let computed;
  try {
    computed = getComputedStyle(el);
  } catch (e) {
    return;
  }
  if (computed.backgroundImage !== "none") return;
  const bg = parseRgb(computed.backgroundColor);
  if (bg && bg.a > 0 && luminance(bg) > 0.5) {
    setImportant(el, "background-color", "#000000");
  }
  const fg = parseRgb(computed.color);
  if (!fg || fg.a === 0) return;
  if (luminance(fg) >= 0.4) return;
  const hsl = rgbToHsl(fg);
  const light = hslToRgb(hsl.h, hsl.s, Math.max(hsl.l, 0.78));
  setImportant(el, "color", `rgb(${light.r}, ${light.g}, ${light.b})`);
}

function recolorRoot(root) {
  if (!root || root.nodeType !== 1) return;
  if (SKIPPED_TAGS.has(root.tagName)) return;
  recolorElement(root);
  const walker = document.createTreeWalker(
    root,
    NodeFilter.SHOW_ELEMENT,
    {
      acceptNode(node) {
        return SKIPPED_TAGS.has(node.tagName)
          ? NodeFilter.FILTER_REJECT
          : NodeFilter.FILTER_ACCEPT;
      },
    },
  );
  let node = walker.nextNode();
  while (node) {
    recolorElement(node);
    node = walker.nextNode();
  }
}

function apply() {
  if (applied || disabled) return;
  if (!window.matchMedia("(prefers-color-scheme: dark)").matches) return;
  applied = true;
  const style = document.createElement("style");
  style.id = STYLE_ID;
  style.textContent =
    ":root{color-scheme:dark !important}" +
    "html,body{background-color:#000000 !important}";
  (document.head || document.documentElement).appendChild(style);
  const setupObserver = () => {
    if (observer || !document.documentElement || disabled) return;
    observer = new MutationObserver(mutations => {
      if (disabled) return;
      for (const mutation of mutations) {
        if (mutation.type === "attributes") {
          recolorElement(mutation.target);
        } else {
          mutation.addedNodes.forEach(node => {
            if (node.nodeType !== 1) return;
            if (node.tagName === "LINK") {
              node.addEventListener("load", scheduleFullRecolor, { once: true });
            } else if (node.tagName === "STYLE") {
              scheduleFullRecolor();
            } else {
              recolorRoot(node);
            }
          });
        }
      }
    });
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["class", "style"],
    });
  };
  let started = false;
  const run = () => {
    if (started || !document.body || disabled) return;
    started = true;
    recolorRoot(document.body);
    setupObserver();
    // Stylesheets already in the page may still be loading; recolour once
    // a burst of them lands, instead of scanning the whole page per sheet.
    document.querySelectorAll("link").forEach(link => {
      link.addEventListener("load", scheduleFullRecolor, { once: true });
    });
  };
  if (document.body) {
    run();
  } else if (document.readyState === "loading") {
    // Body does not exist yet at document_start. Watch for it instead of
    // waiting for DOMContentLoaded, so new elements are black before the
    // first paint rather than flashing white and then being recoloured.
    const bodyObserver = new MutationObserver(() => {
      if (document.body) {
        bodyObserver.disconnect();
        run();
      }
    });
    bodyObserver.observe(document.documentElement, { childList: true });
    document.addEventListener("DOMContentLoaded", run, { once: true });
  } else {
    run();
  }
}

function remove() {
  applied = false;
  if (fullRecolorTimer !== null) {
    clearTimeout(fullRecolorTimer);
    fullRecolorTimer = null;
  }
  document.getElementById(STYLE_ID)?.remove();
  for (const [el, property] of changed) {
    el.style.removeProperty(property);
  }
  changed.length = 0;
  observer?.disconnect();
  observer = null;
}

function connect() {
  try {
    port = browser.runtime.connectNative(PORT_NAME);
  } catch (e) {
    port = null;
    apply();
    return;
  }
  port.onMessage.addListener(message => {
    if (message.action === "config" || message.action === "apply") {
      const wasDisabled = disabled;
      disabled = !!message.disabled;
      if (disabled) {
        remove();
      } else if (wasDisabled) {
        apply();
      }
    }
  });
  port.onDisconnect.addListener(() => {
    port = null;
  });
  try {
    port.postMessage({ action: "config", host: location.hostname });
  } catch (e) {
    // Fall through to the dark-gated default below.
  }
  apply();
}

if (window.matchMedia("(prefers-color-scheme: dark)").matches) {
  connect();
}

window.addEventListener("pageshow", () => {
  if (!port && window.matchMedia("(prefers-color-scheme: dark)").matches) {
    connect();
  } else if (!disabled) {
    apply();
  }
});
