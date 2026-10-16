/**
 * Dynamically shortens a filename while preserving its extension and structural clarity.
 * Uses smart middle truncation (e.g. "BackwoodsCore_v1...Procedure.java").
 *
 * @param filename - Full original filename
 * @param maxLength - Maximum target character count
 * @returns Shortened filename
 */
export function shortenFileName(filename: string, maxLength: number): string {
  if (!filename || filename.length <= maxLength) {
    return filename;
  }

  // Find extension
  const lastDotIndex = filename.lastIndexOf('.');
  let baseName = filename;
  let extension = '';

  if (lastDotIndex > 0 && lastDotIndex < filename.length - 1) {
    baseName = filename.substring(0, lastDotIndex);
    extension = filename.substring(lastDotIndex); // includes the dot, e.g. ".java"
  }

  const ellipsis = '…';
  const extensionLen = extension.length;
  const availableBaseLen = maxLength - extensionLen - ellipsis.length;

  if (availableBaseLen <= 4) {
    // Very tight space: just truncate front + extension
    return filename.substring(0, Math.max(3, maxLength - extensionLen - 1)) + ellipsis + extension;
  }

  // Split remaining base length between head and tail
  const headLen = Math.ceil(availableBaseLen * 0.55);
  const tailLen = Math.floor(availableBaseLen * 0.45);

  const head = baseName.substring(0, headLen);
  const tail = baseName.substring(baseName.length - tailLen);

  return `${head}${ellipsis}${tail}${extension}`;
}

/**
 * Calculates human readable preset lengths
 */
export const SHORTEN_PRESETS = {
  compact: 20,
  medium: 30,
  generous: 42,
  full: 999
} as const;
