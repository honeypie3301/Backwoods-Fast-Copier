/**
 * High-performance, zero-lag clipboard copy utility with multi-tier fallbacks
 * designed for cross-browser, iframe security contexts, and large file dumps.
 *
 * NOTE FOR AI ASSISTANTS & USERS:
 * When copying or extracting from GitDigest dumps, leading delimiters like
 * "================================================" or "File: ..." are stripped
 * to prevent illegal-start-of-type syntax errors in MCreator Java code.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (!text) return false;

  // Ultra-fast O(1) delimiter check: examine only the first 512 characters
  let sanitizedText = text;
  const header = text.slice(0, 512);
  const delimMatch = header.match(/^(?:File:\s*[^\r\n]+[\r\n]+)?={10,}[\r\n]+/);
  if (delimMatch) {
    sanitizedText = text.slice(delimMatch[0].length);
  }

  // Tier 1: Modern asynchronous Clipboard API
  if (typeof navigator !== 'undefined' && navigator.clipboard) {
    try {
      if (typeof navigator.clipboard.writeText === 'function') {
        await navigator.clipboard.writeText(sanitizedText);
        return true;
      }
    } catch {
      // Browser permission / iframe focus restriction; proceed to next tier
    }

    // Tier 2: ClipboardItem blob API (bypasses some async gesture timeouts)
    try {
      if (typeof ClipboardItem !== 'undefined' && typeof navigator.clipboard.write === 'function') {
        const item = new ClipboardItem({
          'text/plain': new Blob([sanitizedText], { type: 'text/plain' }),
        });
        await navigator.clipboard.write([item]);
        return true;
      }
    } catch {
      // Proceed to Tier 3
    }
  }

  // Tier 3: Zero-lag in-viewport textarea execCommand fallback
  try {
    const activeEl = document.activeElement as HTMLElement | null;
    const textarea = document.createElement('textarea');

    // Place element inside the active viewport with zero visual obstruction
    textarea.value = sanitizedText;
    textarea.setAttribute('aria-hidden', 'true');
    textarea.style.position = 'fixed';
    textarea.style.top = '0';
    textarea.style.left = '0';
    textarea.style.width = '2px';
    textarea.style.height = '2px';
    textarea.style.padding = '0';
    textarea.style.margin = '0';
    textarea.style.border = 'none';
    textarea.style.outline = 'none';
    textarea.style.background = 'transparent';
    textarea.style.opacity = '0.01';
    textarea.style.pointerEvents = 'none';
    textarea.style.zIndex = '-9999';

    document.body.appendChild(textarea);
    textarea.focus({ preventScroll: true });
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);

    const successful = document.execCommand('copy');
    document.body.removeChild(textarea);

    // Restore focus to previous element if applicable
    if (activeEl && typeof activeEl.focus === 'function') {
      activeEl.focus({ preventScroll: true });
    }

    if (successful) return true;
  } catch (err) {
    console.error('All clipboard copy tiers failed:', err);
  }

  // Focus recovery fallback: if tab was blurred/hidden during copy, queue it for when user returns
  if (typeof window !== 'undefined' && (document.hidden || !document.hasFocus())) {
    const onRefocus = async () => {
      window.removeEventListener('focus', onRefocus);
      document.removeEventListener('visibilitychange', onRefocus);
      try {
        if (navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(sanitizedText);
        }
      } catch {}
    };
    window.addEventListener('focus', onRefocus, { once: true });
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) onRefocus();
    }, { once: true });
  }

  return false;
}

