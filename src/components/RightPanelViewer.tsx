/**
 * ============================================================================
 * STRICT RULE / CONSTANT REMINDERS:
 * 1. ALWAYS FULLY IMPORT SO THE UPDATED FRAME LOGIC IS ALWAYS THERE.
 * 2. The updated indicator will ONLY appear if the file is updated, and will ONLY
 *    disappear through the "Mark as Read" button or when the file content is copied.
 *    Selecting or viewing a file MUST NOT clear the updated indicator.
 * ============================================================================
 */

import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { Copy, Check, CheckCheck, Zap } from 'lucide-react';
import { FileItem } from '../types';

interface RightPanelViewerProps {
  file: FileItem | null;
  cachedContent?: string;
  onClose?: () => void;
  onSaveContent?: (filename: string, content: string) => Promise<boolean>;
  onDeleteFile?: (filename: string) => void;
  onCopyContent?: (filename: string) => Promise<boolean>;
  onMarkAsRead?: (filename: string) => void;
}

const LINE_HEIGHT = 20; // Exact pixel height per code row for 60fps virtualized rendering

export const RightPanelViewer: React.FC<RightPanelViewerProps> = ({
  file,
  cachedContent,
  onCopyContent,
  onMarkAsRead,
}) => {
  const [content, setContent] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<boolean>(false);
  const [copying, setCopying] = useState<boolean>(false);
  const [fetchTrigger, setFetchTrigger] = useState<number>(0);

  // Virtual scrolling state
  const scrollRef = useRef<HTMLDivElement>(null);
  const outerContainerRef = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState<number>(0);
  const [containerHeight, setContainerHeight] = useState<number>(600);

  const fileName = file?.name;

  // Stream raw content directly for ultra-fast transfer of large files
  useEffect(() => {
    if (!fileName) {
      setContent('');
      setError(null);
      setLoading(false);
      return;
    }

    if (cachedContent !== undefined) {
      setContent(cachedContent);
      setError(null);
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    let isCancelled = false;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 20000); // 20s safety timeout

    // Use ?raw=1 with text/plain to stream directly without JSON serialization overhead
    fetch(`/api/files/${encodeURIComponent(fileName)}/content?raw=1`, {
      cache: 'no-store',
      headers: { Accept: 'text/plain' },
      signal: controller.signal,
    })
      .then(async (res) => {
        if (!isCancelled) {
          if (res.ok) {
            const rawText = await res.text();
            setContent(rawText);
          } else {
            setError('Failed to load file content.');
            setContent('// Failed to load file content.');
          }
        }
      })
      .catch((err) => {
        if (!isCancelled) {
          const msg = err.name === 'AbortError' ? 'Streaming timed out (20s)' : err.message;
          setError(msg);
          setContent(`// Error loading file: ${msg}`);
        }
      })
      .finally(() => {
        clearTimeout(timeoutId);
        if (!isCancelled) {
          setLoading(false);
        }
      });

    return () => {
      isCancelled = true;
      clearTimeout(timeoutId);
      controller.abort();
    };
  }, [fileName, cachedContent, file?.updatedAt, fetchTrigger]);

  // Track viewport container height on persistent outer container
  useEffect(() => {
    const el = outerContainerRef.current;
    if (!el) return;
    const updateSize = () => {
      if (el) setContainerHeight(el.clientHeight || 600);
    };
    updateSize();

    const ro = new ResizeObserver(() => updateSize());
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const handleCopy = async () => {
    if (!file || copying) return;
    setCopying(true);
    let ok = false;
    if (onCopyContent) {
      ok = await onCopyContent(file.name);
    }
    setCopying(false);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    setScrollTop(e.currentTarget.scrollTop);
  }, []);

  // Split lines once memoized
  const lines = useMemo(() => {
    if (!content) return [];
    return content.split('\n');
  }, [content]);

  // Virtual windowing calculations
  const totalLines = lines.length;
  const totalHeight = totalLines * LINE_HEIGHT;
  const visibleCount = Math.ceil(containerHeight / LINE_HEIGHT);
  const startIndex = Math.max(0, Math.floor(scrollTop / LINE_HEIGHT) - 20);
  const endIndex = Math.min(totalLines, startIndex + visibleCount + 40);
  const visibleLines = useMemo(() => lines.slice(startIndex, endIndex), [lines, startIndex, endIndex]);
  const topOffset = startIndex * LINE_HEIGHT;

  if (!file) {
    return (
      <div className="flex-1 h-full bg-[#030504] flex flex-col items-center justify-center p-8 text-center select-none font-mono">
        <p className="text-xs text-[#4e6153] max-w-sm leading-relaxed">
          Select a file from the sidebar to view its contents, or click the enlarged <span className="text-emerald-400 font-semibold">COPY</span> button beside any file to copy directly.
        </p>
      </div>
    );
  }

  return (
    <div className="flex-1 h-full flex flex-col bg-[#030504] overflow-hidden font-mono text-xs">
      
      {/* Right Panel Header */}
      <div className="h-10 shrink-0 border-b border-[#111a13] bg-[#040705] px-4 flex items-center justify-between gap-3 select-none">
        
        {/* File Info */}
        <div className="flex items-center gap-2 truncate min-w-0 flex-1">
          <span className={`font-bold text-xs truncate ${file.isEdited ? 'text-amber-200' : 'text-[#d0e0d3]'}`} title={file.name}>
            {file.name}
          </span>
          <span className={`px-1.5 py-0.2 text-[10px] font-bold rounded shrink-0 ${
            file.isEdited
              ? 'text-amber-300 bg-[#211506] border border-amber-600/80 shadow-[0_0_8px_rgba(245,158,11,0.4)]'
              : 'text-emerald-400 bg-[#07130a] border border-[#112416]'
          }`}>
            v{file.version}
          </span>
          <span className="text-[#4e6153] text-[10px] shrink-0 hidden sm:inline">
            {loading ? `(streaming · ${file.sizeFormatted})` : `(${totalLines.toLocaleString()} lines · ${file.sizeFormatted})`}
          </span>
          {totalLines > 2000 && (
            <span className="hidden lg:inline-flex items-center gap-1 text-[10px] text-emerald-400/80 bg-[#07170c] px-1.5 py-0.2 rounded border border-emerald-900/60" title="60fps Virtual Scrolling Active">
              <Zap className="w-2.5 h-2.5 text-emerald-400" />
              <span>Virtual Engine</span>
            </span>
          )}
        </div>

        {/* Action Buttons: Mark as Read & Copy */}
        <div className="flex items-center gap-2 shrink-0">
          {/* Mark as Read button - strictly clears the updated indicator */}
          {file.isEdited && onMarkAsRead && (
            <button
              onClick={() => onMarkAsRead(file.name)}
              title="Mark file as read (clears updated indicator)"
              className="flex items-center gap-1 px-2 py-1 text-xs font-semibold rounded bg-[#1e1507] hover:bg-[#2b1f0c] text-amber-300 border border-amber-500/80 hover:border-amber-400 transition-all active:scale-95 shadow-[0_0_6px_rgba(245,158,11,0.25)]"
            >
              <CheckCheck className="w-3.5 h-3.5 text-amber-400" />
              <span>Mark as Read</span>
            </button>
          )}

          {/* Copy Button - also clears updated indicator on copy */}
          <button
            onClick={handleCopy}
            disabled={copying}
            title={`Copy content of ${file.name}`}
            className={`flex items-center gap-1.5 px-3 py-1 text-xs font-bold rounded transition-all active:scale-95 shrink-0 ${
              copied
                ? 'bg-[#152e1d] text-emerald-300 border border-[#224b2f] shadow-[0_0_8px_rgba(16,185,129,0.3)]'
                : 'bg-emerald-500 hover:bg-emerald-400 text-black shadow-md'
            }`}
          >
            {copied ? (
              <>
                <Check className="w-3.5 h-3.5 stroke-[2.5]" />
                <span>Copied!</span>
              </>
            ) : (
              <>
                <Copy className="w-3.5 h-3.5" />
                <span>Copy</span>
              </>
            )}
          </button>
        </div>

      </div>

      {/* Code Editor / High-Performance Virtualized Content Display Area */}
      <div ref={outerContainerRef} className="flex-1 overflow-hidden relative bg-[#030504]">
        {loading ? (
          <div className="h-full flex items-center justify-center text-[#4e6153] gap-2 font-mono text-xs">
            <div className="w-3.5 h-3.5 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
            <span>Streaming content...</span>
          </div>
        ) : error ? (
          <div className="h-full flex flex-col items-center justify-center text-[#4e6153] gap-3 font-mono text-xs p-6 text-center">
            <span className="text-amber-400 font-semibold">{error}</span>
            <button
              onClick={() => setFetchTrigger((prev) => prev + 1)}
              className="px-3 py-1 bg-emerald-500 hover:bg-emerald-400 text-black font-bold rounded transition-all active:scale-95 shadow-md"
            >
              Retry Loading
            </button>
          </div>
        ) : totalLines === 0 ? (
          <div className="p-4 text-[#4e6153] font-mono text-xs">
            // File is empty.
          </div>
        ) : (
          <div
            ref={scrollRef}
            onScroll={handleScroll}
            className="h-full overflow-auto select-text bg-[#030504] relative font-mono text-xs"
          >
            {/* Total height spacer so scrollbar accurately reflects full document size */}
            <div style={{ height: `${totalHeight}px`, position: 'relative', width: '100%', minWidth: 'max-content' }}>
              <div
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  right: 0,
                  transform: `translateY(${topOffset}px)`,
                  willChange: 'transform',
                }}
                className="flex flex-col"
              >
                {visibleLines.map((line, idx) => {
                  const lineNum = startIndex + idx + 1;
                  return (
                    <div
                      key={lineNum}
                      className="flex items-center h-[20px] leading-[20px] hover:bg-[#070e0a]/60 px-2"
                    >
                      <span className="w-14 pr-3 text-right text-[#334237] select-none shrink-0 font-mono text-[11px] border-r border-[#0d1610] mr-3">
                        {lineNum}
                      </span>
                      <span className="text-[#c2d4c5] whitespace-pre font-mono font-normal">
                        {line || ' '}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        )}
      </div>

    </div>
  );
};
