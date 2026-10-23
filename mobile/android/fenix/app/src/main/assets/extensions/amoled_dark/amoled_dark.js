// Dejavu True AMOLED Webpage Engine
(function () {
  'use strict';

  const mql = window.matchMedia('(prefers-color-scheme: dark)');

  function parseRgb(colorStr) {
    if (!colorStr || colorStr === 'transparent' || colorStr === 'rgba(0, 0, 0, 0)') {
      return null;
    }
    const match = colorStr.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
    if (!match) return null;
    const r = parseInt(match[1], 10);
    const g = parseInt(match[2], 10);
    const b = parseInt(match[3], 10);
    const a = match[4] !== undefined ? parseFloat(match[4]) : 1;
    return { r, g, b, a };
  }

  function getLuminance(rgb) {
    return 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
  }

  const processedElements = new WeakSet();
  const modifiedElements = new Set();
  let scheduled = false;

  function revertAll() {
    for (const el of modifiedElements) {
      if (el && el.removeAttribute) {
        el.removeAttribute('data-dejavu-amoled');
      }
    }
    modifiedElements.clear();
    if (document.documentElement) {
      document.documentElement.removeAttribute('data-dejavu-amoled');
    }
    if (document.body) {
      document.body.removeAttribute('data-dejavu-amoled');
    }
  }

  function processElement(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE || processedElements.has(el)) return;
    processedElements.add(el);

    const tag = el.tagName.toUpperCase();
    if (['IMG', 'VIDEO', 'CANVAS', 'SVG', 'PICTURE', 'SOURCE', 'SCRIPT', 'STYLE', 'LINK'].includes(tag)) {
      return;
    }

    const computed = window.getComputedStyle(el);
    const bg = parseRgb(computed.backgroundColor);
    const fg = parseRgb(computed.color);

    if (bg && bg.a > 0.1) {
      const lum = getLuminance(bg);

      // Convert dark greys (#121212, #1a1a1a, #202124) into true AMOLED #000000
      if (lum < 50 && lum > 0) {
        el.setAttribute('data-dejavu-amoled', 'bg');
        modifiedElements.add(el);
      }
      // On light websites without dark mode: convert bright backgrounds to AMOLED
      else if (lum > 175) {
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
          el.setAttribute('data-dejavu-amoled', 'input');
        } else if (el.offsetWidth > window.innerWidth * 0.6 && el.offsetHeight > window.innerHeight * 0.4) {
          el.setAttribute('data-dejavu-amoled', 'bg');
        } else {
          el.setAttribute('data-dejavu-amoled', 'surface');
        }
        modifiedElements.add(el);
      }
    }

    // Invert dark text on converted light sites
    if (fg && fg.a > 0.5) {
      const fgLum = getLuminance(fg);
      if (fgLum < 85 && el.hasAttribute('data-dejavu-amoled')) {
        el.setAttribute('data-dejavu-amoled', el.getAttribute('data-dejavu-amoled') + ' text');
      }
    }
  }

  function scanChunk(elements, index = 0, chunkSize = 150) {
    if (!mql.matches) return;
    const end = Math.min(index + chunkSize, elements.length);
    for (let i = index; i < end; i++) {
      processElement(elements[i]);
    }
    if (end < elements.length) {
      requestAnimationFrame(() => scanChunk(elements, end, chunkSize));
    }
  }

  function scheduleScan() {
    if (!mql.matches || scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      if (!mql.matches) return;
      const all = document.querySelectorAll('div, section, article, main, header, footer, aside, nav, table, form, ul, ol, li, input, textarea, select');
      scanChunk(all);
    });
  }

  function applyAmoledTheme() {
    if (!mql.matches) {
      revertAll();
      return;
    }

    // Immediate fast-path for root and body
    if (document.documentElement) {
      document.documentElement.setAttribute('data-dejavu-amoled', 'bg');
      modifiedElements.add(document.documentElement);
    }
    if (document.body) {
      document.body.setAttribute('data-dejavu-amoled', 'bg');
      modifiedElements.add(document.body);
    }

    scheduleScan();
  }

  // Listen to preference changes (User toggles between Light and Dark mode)
  mql.addEventListener('change', (e) => {
    if (e.matches) {
      applyAmoledTheme();
    } else {
      revertAll();
    }
  });

  // Watch for dynamic content additions (SPAs, infinite scroll)
  const observer = new MutationObserver((mutations) => {
    if (!mql.matches) return;
    for (const m of mutations) {
      for (const node of m.addedNodes) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          processElement(node);
          const children = node.querySelectorAll ? node.querySelectorAll('*') : [];
          for (let i = 0; i < children.length && i < 100; i++) {
            processElement(children[i]);
          }
        }
      }
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      applyAmoledTheme();
      if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
      }
    }, { once: true });
  } else {
    applyAmoledTheme();
    if (document.body) {
      observer.observe(document.body, { childList: true, subtree: true });
    }
  }
})();
