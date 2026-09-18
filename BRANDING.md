# Branding Guide — OOM Watchdog Dashboard

The `dashboard.html` file is designed to be rebranded without touching any JavaScript or server-side code. Every visual aspect — colours, fonts, corner radius, header, and logo — is controlled by a small set of CSS custom properties and two clearly-marked HTML blocks.

---

## Quick start (3 steps)

1. Open `dashboard.html` in a text editor.
2. Edit the **BRANDING TOKENS** `<style>` block near the top of `<head>` (≈ line 16).
3. Edit the **BRANDING CONTENT** `<header>` block in `<body>` (≈ line 306).

No build step, no restart — just save and refresh the browser.

---

## CSS token reference

All tokens live inside `:root { … }` in the **BRANDING TOKENS** block.

### Mandatory tokens

These must always have a value; the dashboard will look broken if removed.

| Token | Purpose | Default |
|---|---|---|
| `--bg` | Page background | `#0f1117` |
| `--surface` | Card / header background | `#1a1d27` |
| `--surface2` | Toolbar / slightly elevated surface | `#222638` |
| `--border` | Dividing lines and card outlines | `#2a2d3a` |
| `--text` | Primary body text | `#e4e6f0` |
| `--muted` | De-emphasised labels, helper text, sub-values | `#7a7e96` |
| `--ok` | Risk **OK** indicator (green or equivalent) | `#22c55e` |
| `--warn` | Risk **WARNING** indicator (amber) | `#f59e0b` |
| `--crit` | Risk **CRITICAL** indicator (red) | `#ef4444` |
| `--oom` | Risk **OOM_FIRING** indicator (purple) | `#a855f7` |
| `--accent` | Buttons, links, active tab underline | `#3b82f6` |
| `--radius` | Global border-radius for cards and inputs | `10px` |

### Optional tokens

These are commented out by default. Uncomment any line to activate it.

| Token | Purpose | Fallback when absent |
|---|---|---|
| `--header-bg` | Header bar background | `var(--surface)` |
| `--header-border` | Header bottom border | `var(--border)` |
| `--logo-display` | Set to `inline-block` to show `#brand-logo` | `none` (hidden) |
| `--logo-height` | Height of the logo `<img>` | `28px` |
| `--font-family` | Body font stack | system-ui stack |

To activate an optional token, remove the surrounding `/* … */` comment:

```css
/* Before (commented out — not applied) */
/* --header-bg: #0a0c14; */

/* After (active) */
--header-bg: #0a0c14;
```

---

## Showing an organisation logo

The `<header>` contains a hidden `<img id="brand-logo">` element. To show it:

1. Set `src` to the logo URL or a `data:image/…` URI.
2. Add `class="visible"` **or** uncomment `--logo-display: inline-block;` in the token block.

**Option A — CSS token (no HTML change):**
```css
--logo-display: inline-block;
```
Then set `src` in the HTML:
```html
<img id="brand-logo" src="/assets/my-logo.svg" alt="My Org">
```

**Option B — HTML class only:**
```html
<img id="brand-logo" class="visible" src="/assets/my-logo.svg" alt="My Org">
```

**Using an inline SVG as a data URI** (no external request, works offline):
```html
<img id="brand-logo" class="visible"
     src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' …%3E%3C/svg%3E"
     alt="My Org">
```

Control the logo height with:
```css
--logo-height: 32px;  /* default is 28px */
```

---

## Editing BRANDING CONTENT

The `<header>` block in `<body>` is marked `BRANDING CONTENT` and contains four editable areas:

```html
<header>
  <!-- 1. Logo image — set src and optionally add class="visible" -->
  <img id="brand-logo" src="" alt="Logo">

  <div>
    <!-- 2. Dashboard title -->
    <h1>⚡ OOM Watchdog Dashboard</h1>

    <!-- 3. Subtitle beneath the title -->
    <div class="subtitle">Real-time JVM health — heap, GC, and nursery metrics per monitored target</div>
  </div>

  <!-- 4. Overall risk badge — managed by JavaScript, do not remove -->
  <div id="risk-badge" class="risk-badge risk-UNKNOWN">—</div>
</header>
```

The `<footer>` at the bottom of `<body>` can also be changed for attribution and links.

> **Do not remove** the `<div id="risk-badge">` element — the JavaScript updates it at runtime.

---

## Theme presets

Ready-to-paste `:root` blocks are provided in the `branding/` directory.
Open any HTML file in your browser to preview the colour swatches before applying.

| File | Description |
|---|---|
| [`branding/theme-dark-default.html`](branding/theme-dark-default.html) | Factory dark theme (current defaults) |
| [`branding/theme-light-corporate.html`](branding/theme-light-corporate.html) | Light page with dark navy header — intranet / corporate |
| [`branding/theme-ibm-carbon.html`](branding/theme-ibm-carbon.html) | IBM Carbon Design System inspired dark theme |

To apply a preset:

1. Open the preset file in your text editor.
2. Copy everything between `/* PASTE THIS :root BLOCK … */` and `/* ── END OF THEME BLOCK ── */`.
3. In `dashboard.html`, select the entire `:root { … }` block inside the BRANDING TOKENS section and paste.

---

## Light theme considerations

When switching to a light background, the hardcoded colours on these elements will also need updating in `dashboard.html`:

| Selector | Current hardcoded value | What to change it to |
|---|---|---|
| `.risk-OK` | `background: #14532d` | Light green tint, e.g. `#dcfce7` |
| `.risk-WARNING` | `background: #78350f` | Light amber tint, e.g. `#fef3c7` |
| `.risk-CRITICAL` | `background: #7f1d1d` | Light red tint, e.g. `#fee2e2` |
| `.risk-OOM_FIRING` | `background: #581c87` | Light purple tint, e.g. `#f3e8ff` |
| `.alert-log li.lvl-WARNING` | `background: #1c1507` | Light amber, e.g. `#fffbeb` |
| `.alert-log li.lvl-CRITICAL` | `background: #1c0505` | Light red, e.g. `#fff1f2` |
| `.alert-log li.lvl-OOM_FIRING` | `background: #160b21` | Light purple, e.g. `#faf5ff` |
| `.alert-log li.lvl-OK` | `background: #051409` | Light green, e.g. `#f0fdf4` |
| `.dump-path` | `background: #0c1929`, `color: #93c5fd` | Light blue, e.g. `#eff6ff` / `#1d4ed8` |
| `td` | `border-bottom: rgba(255,255,255,0.04)` | `rgba(0,0,0,0.05)` |

The `branding/theme-light-corporate.html` preset includes a note about this.

---

## Copy-paste presets

### Minimal brand colour swap (keep dark, change accent)

Replace only `--accent` and keep everything else:

```css
:root {
  --bg:       #0f1117;
  --surface:  #1a1d27;
  --surface2: #222638;
  --border:   #2a2d3a;
  --text:     #e4e6f0;
  --muted:    #7a7e96;
  --ok:       #22c55e;
  --warn:     #f59e0b;
  --crit:     #ef4444;
  --oom:      #a855f7;
  --accent:   #e6007e;   /* ← your brand colour here */
  --radius:   10px;
}
```

### Navy / dark blue enterprise

```css
:root {
  --bg:        #0a0e1a;
  --surface:   #111827;
  --surface2:  #1c2333;
  --border:    #1e2a40;
  --text:      #e2e8f0;
  --muted:     #64748b;
  --ok:        #10b981;
  --warn:      #f59e0b;
  --crit:      #f43f5e;
  --oom:       #a78bfa;
  --accent:    #0ea5e9;
  --radius:    6px;
  --header-bg: #0d1117;
  --header-border: #1e2a40;
}
```

### High-contrast accessible dark

```css
:root {
  --bg:       #000000;
  --surface:  #1a1a1a;
  --surface2: #2a2a2a;
  --border:   #444444;
  --text:     #ffffff;
  --muted:    #aaaaaa;
  --ok:       #00e676;
  --warn:     #ffd600;
  --crit:     #ff1744;
  --oom:      #e040fb;
  --accent:   #40c4ff;
  --radius:   4px;
}
```

---

## Frequently asked questions

**Q: Do I need to restart the OOM Watchdog agent after changing the theme?**  
A: No. `dashboard.html` is a static file served by the metrics HTTP server. The browser fetches it fresh each time the page is opened. Edit, save, and refresh.

**Q: Can I use a custom Google Font?**  
A: Yes. Add a `<link>` tag inside `<head>` pointing to the Google Fonts CSS, then set `--font-family` to the font name:
```html
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap">
```
```css
--font-family: "Inter", system-ui, sans-serif;
```

**Q: Will my changes be overwritten when I update OOM Watchdog?**  
A: Only if you overwrite `dashboard.html` with the new release version. To preserve your theme across updates, keep your `:root` block in a separate file (e.g. `my-theme.css`) and copy it in after each upgrade, or maintain a fork of `dashboard.html`.

**Q: The logo looks blurry on high-DPI screens.**  
A: Use an SVG logo (`src="logo.svg"`) for perfect sharpness at any resolution. If you must use a PNG, provide a 2× or 3× raster and set `--logo-height` accordingly; the `width: auto` on `#brand-logo` will scale it proportionally.

**Q: Can I use a local font file (no external request)?**  
A: Yes. Place your WOFF2 font file alongside `dashboard.html`, add a `@font-face` block inside the `<style>` section, then set `--font-family`.
