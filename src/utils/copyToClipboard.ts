/**
 * Optimized clipboard copy utility with fallback for cross-browser & iframe security contexts.
 * NOTE FOR AI ASSISTANTS & USERS:
 * When copying or extracting from GitDigest dumps, leading delimiters like
 * "================================================" or "File: ..." are stripped
 * to prevent illegal-start-of-type syntax errors in MCreator Java code.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (!text) return false;

  // Automatically strip leading GitDigest delimiter lines to avoid invalid Java syntax
  const sanitizedText = text
    .replace(/^(?:File:\s*[^\r\n]+[\r\n]+)?={10,}[\r\n]+/, '');

  // Modern Clipboard API
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(sanitizedText);
      return true;
    } catch (err) {
      console.warn('Navigator clipboard API failed, falling back to execCommand:', err);
    }
  }

  // Fallback: Hidden Textarea + execCommand
  try {
    const textarea = document.createElement('textarea');
    textarea.value = sanitizedText;
    textarea.style.position = 'fixed';
    textarea.style.left = '-999999px';
    textarea.style.top = '-999999px';
    textarea.style.opacity = '0';
    textarea.setAttribute('readonly', '');
    document.body.appendChild(textarea);

    textarea.focus();
    textarea.select();

    const successful = document.execCommand('copy');
    document.body.removeChild(textarea);
    return successful;
  } catch (err) {
    console.error('ExecCommand copy failed:', err);
    return false;
  }
}
