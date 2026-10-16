import React, { useState } from 'react';
import { Copy, Check, FileCode, FileText, FileJson, Trash2, Eye, ShieldCheck, Sparkles, ExternalLink } from 'lucide-react';
import { FileItem } from '../types';
import { shortenFileName } from '../utils/shortenFileName';

interface FileRowProps {
  file: FileItem;
  maxLength: number;
  cachedContent?: string;
  onCopyContent: (filename: string) => Promise<boolean>;
  onViewContent: (file: FileItem) => void;
  onDeleteFile: (filename: string) => void;
  index: number;
}

export const FileRow: React.FC<FileRowProps> = ({
  file,
  maxLength,
  cachedContent,
  onCopyContent,
  onViewContent,
  onDeleteFile,
  index,
}) => {
  const [copying, setCopying] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copiedName, setCopiedName] = useState(false);

  // Dynamically shorten file name for UI rendering
  const displayName = shortenFileName(file.name, maxLength);
  const isShortened = displayName !== file.name;

  // Choose file icon based on extension
  const getFileIcon = (ext: string) => {
    switch (ext) {
      case 'java':
      case 'js':
      case 'ts':
      case 'tsx':
        return <FileCode className="w-4 h-4 text-amber-400" />;
      case 'json':
        return <FileJson className="w-4 h-4 text-emerald-400" />;
      case 'mcfunction':
      case 'yml':
      case 'yaml':
      case 'toml':
        return <Sparkles className="w-4 h-4 text-cyan-400" />;
      default:
        return <FileText className="w-4 h-4 text-neutral-400" />;
    }
  };

  const handleCopyClick = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (copying) return;

    setCopying(true);
    const success = await onCopyContent(file.name);
    setCopying(false);

    if (success) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleCopyName = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(file.name);
      setCopiedName(true);
      setTimeout(() => setCopiedName(false), 1500);
    } catch (err) {
      // ignore
    }
  };

  return (
    <div
      onClick={() => onViewContent(file)}
      className="group relative bg-neutral-950 hover:bg-neutral-900/90 border border-neutral-900 hover:border-neutral-800 rounded-xl p-3.5 sm:p-4 transition-all duration-150 cursor-pointer shadow-lg hover:shadow-2xl hover:shadow-black/60"
    >
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        
        {/* Left Side: File Icon, Title (Shortened), Version Badge, Source Indicator */}
        <div className="flex items-center gap-3 min-w-0 flex-1">
          
          {/* File Icon Container */}
          <div className="p-2 rounded-lg bg-black border border-neutral-850 shrink-0 group-hover:border-neutral-700 transition-colors">
            {getFileIcon(file.extension)}
          </div>

          <div className="min-w-0 flex-1">
            {/* File Title Line with OPTIMIZED COPY BUTTON BESIDE TITLE */}
            <div className="flex items-center gap-2 flex-wrap sm:flex-nowrap">
              
              {/* Dynamically Shortened Filename */}
              <span
                className="font-mono text-sm sm:text-base font-semibold text-neutral-100 group-hover:text-white transition-colors truncate max-w-full"
                title={isShortened ? `Full name: ${file.name}` : undefined}
              >
                {displayName}
              </span>

              {/* Shortened Indicator Tag */}
              {isShortened && (
                <span className="hidden md:inline-block px-1.5 py-0.2 text-[10px] font-mono text-neutral-500 bg-black border border-neutral-850 rounded">
                  shortened
                </span>
              )}

              {/* DYNAMIC VERSION BADGE (Explicitly required: "say what version the file is like 1.21.1 or 26.1.2") */}
              <div
                className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-mono font-semibold text-emerald-300 bg-emerald-950/60 border border-emerald-800/80 rounded-md shrink-0 shadow-[0_0_8px_rgba(16,185,129,0.15)]"
                title={`Version v${file.version} (Detected from ${file.versionSource})`}
              >
                <ShieldCheck className="w-3 h-3 text-emerald-400" />
                <span>v{file.version}</span>
              </div>

              {/* PRIMARY OPTIMIZED COPY BUTTON (Directly beside file title as requested!) */}
              <button
                onClick={handleCopyClick}
                disabled={copying}
                title="Copy full content of this file to clipboard"
                className={`flex items-center gap-1.5 px-2.5 py-1 text-xs font-mono font-semibold rounded-lg border transition-all active:scale-95 shrink-0 ${
                  copied
                    ? 'bg-emerald-500 text-black border-emerald-400 font-bold shadow-[0_0_15px_rgba(52,211,153,0.5)]'
                    : 'bg-neutral-900 text-emerald-400 border-emerald-900/60 hover:bg-emerald-950 hover:text-emerald-300 hover:border-emerald-700/80'
                }`}
              >
                {copied ? (
                  <>
                    <Check className="w-3.5 h-3.5 stroke-[3] animate-scale-in" />
                    <span>Copied!</span>
                  </>
                ) : copying ? (
                  <>
                    <span className="w-3 h-3 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                    <span>Copying...</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5 text-emerald-400" />
                    <span>Copy Content</span>
                  </>
                )}
              </button>

            </div>

            {/* Sub-info metadata line */}
            <div className="flex items-center gap-2 mt-1 text-xs font-mono text-neutral-500 flex-wrap">
              <span>{file.sizeFormatted}</span>
              <span>·</span>
              <span className="uppercase text-[10px] px-1 py-0.2 bg-black rounded border border-neutral-900 text-neutral-400">
                .{file.extension}
              </span>
              <span>·</span>
              <button
                onClick={handleCopyName}
                className="text-neutral-500 hover:text-neutral-300 underline transition-colors"
                title="Copy full unshortened filename"
              >
                {copiedName ? 'Name copied!' : 'Copy filename'}
              </button>
            </div>
          </div>

        </div>

        {/* Right Side: Quick Action Toolbar */}
        <div className="flex items-center gap-1.5 self-end sm:self-center shrink-0">
          
          {/* View / Edit Button */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onViewContent(file);
            }}
            title="Inspect / Edit file"
            className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-mono text-neutral-300 bg-black hover:bg-neutral-800 border border-neutral-850 hover:border-neutral-750 rounded-lg transition-all"
          >
            <Eye className="w-3.5 h-3.5 text-neutral-400" />
            <span className="hidden md:inline">Inspect</span>
          </button>

          {/* Delete Button */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onDeleteFile(file.name);
            }}
            title="Delete file from uploaded_files"
            className="p-1.5 text-neutral-500 hover:text-rose-400 bg-black hover:bg-rose-950/40 border border-neutral-850 hover:border-rose-900/80 rounded-lg transition-all"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>

        </div>

      </div>
    </div>
  );
};
