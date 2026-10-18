/**
 * ============================================================================
 * STRICT RULE / CONSTANT REMINDER:
 * The updated indicator will ONLY appear if the file is updated, and will ONLY
 * disappear through the "Mark as Read" button or when the file content is copied.
 * Selecting or viewing a file MUST NOT clear the updated indicator.
 * ============================================================================
 */

import React, { useState } from 'react';
import { FileItem } from '../types';
import { shortenFileName } from '../utils/shortenFileName';
import { CheckSquare, Square, CheckCheck } from 'lucide-react';

interface SidebarFileListProps {
  files: FileItem[];
  selectedFileName: string | null;
  maxLength: number;
  readFiles: Set<string>;
  selectedFileNames: Set<string>;
  onToggleSelectFile: (filename: string, e: React.MouseEvent) => void;
  onSelectFile: (file: FileItem) => void;
  onCopyContent: (filename: string) => Promise<boolean>;
  onMarkAsRead?: (filename: string) => void;
}

export const SidebarFileList: React.FC<SidebarFileListProps> = ({
  files,
  selectedFileName,
  maxLength,
  readFiles,
  selectedFileNames,
  onToggleSelectFile,
  onSelectFile,
  onCopyContent,
  onMarkAsRead,
}) => {
  const [copyingFile, setCopyingFile] = useState<string | null>(null);
  const [copiedFile, setCopiedFile] = useState<string | null>(null);

  const handleCopyClick = async (e: React.MouseEvent, filename: string) => {
    e.stopPropagation();
    if (copyingFile) return;

    setCopyingFile(filename);
    const success = await onCopyContent(filename);
    setCopyingFile(null);

    if (success) {
      setCopiedFile(filename);
      setTimeout(() => setCopiedFile(null), 1500);
    }
  };

  if (files.length === 0) {
    return (
      <div className="p-4 text-center text-xs font-mono text-[#4a5c4f]">
        No files match filter.
      </div>
    );
  }

  return (
    <div className="p-1 font-mono text-xs select-none space-y-0.5">
      {files.map((file) => {
        const isSelected = selectedFileName === file.name;
        const isChecked = selectedFileNames.has(file.name);
        const isUnread = !readFiles.has(file.name);
        const displayName = shortenFileName(file.name, maxLength);
        const isCopied = copiedFile === file.name;
        const isCopying = copyingFile === file.name;

        return (
          <div
            key={file.name}
            onClick={() => onSelectFile(file)}
            className={`group flex items-center justify-between gap-1.5 px-2 py-1.5 rounded cursor-pointer transition-all ${
              isSelected && file.isEdited
                ? 'bg-[#181105] border border-amber-400 text-amber-200 font-bold shadow-[0_0_14px_rgba(245,158,11,0.5)]'
                : isSelected
                ? 'bg-[#09140c] border-l-2 border-emerald-500 text-white font-bold'
                : isChecked
                ? 'bg-[#0d1f14] border border-emerald-600/60 text-emerald-100'
                : file.isEdited
                ? 'bg-[#140e04] hover:bg-[#1a1306] border border-amber-500/80 text-amber-200 font-semibold shadow-[0_0_12px_rgba(245,158,11,0.35)]'
                : isUnread
                ? 'bg-[#050906] hover:bg-[#080f0a] text-[#c2d4c5]'
                : 'hover:bg-[#060b08] text-[#a0b2a3]'
            }`}
          >
            {/* Checkbox for ZIP Selection */}
            <button
              type="button"
              onClick={(e) => onToggleSelectFile(file.name, e)}
              className="p-0.5 text-[#3b4e41] hover:text-emerald-400 transition-colors shrink-0"
              title={isChecked ? 'Unselect for ZIP download' : 'Select for ZIP download'}
            >
              {isChecked ? (
                <CheckSquare className="w-3.5 h-3.5 text-emerald-400 fill-emerald-950" />
              ) : (
                <Square className="w-3.5 h-3.5 opacity-60 group-hover:opacity-100" />
              )}
            </button>

            {/* Left: Shortened Filename */}
            <div className="flex items-center gap-1.5 min-w-0 flex-1">
              <span
                className={`truncate text-xs ${
                  isSelected
                    ? 'text-emerald-300 font-bold'
                    : isChecked
                    ? 'text-emerald-200 font-semibold'
                    : file.isEdited
                    ? 'text-amber-200 font-semibold group-hover:text-amber-100'
                    : 'group-hover:text-white'
                }`}
                title={file.name}
              >
                {displayName}
              </span>
            </div>

            {/* Right: Version Badge + Copy Button */}
            <div className="flex items-center gap-1 shrink-0">
              
              {/* Updated Status Tag & Mark Read button */}
              {file.isEdited && (
                <div className="flex items-center gap-1">
                  <span className="text-[9px] font-black px-1.5 py-0.5 rounded text-amber-300 bg-[#2b1704] border border-amber-500/80 shadow-[0_0_8px_rgba(245,158,11,0.4)] animate-pulse">
                    UPDATED
                  </span>
                  {onMarkAsRead && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onMarkAsRead(file.name);
                      }}
                      title="Mark as read (clears updated indicator)"
                      className="p-1 rounded bg-[#211506] hover:bg-[#342109] text-amber-400 border border-amber-600/80 hover:border-amber-400 transition-colors"
                    >
                      <CheckCheck className="w-3 h-3" />
                    </button>
                  )}
                </div>
              )}

              {/* Version Tag */}
              <span className={`text-[10px] font-bold px-1 py-0.2 rounded ${
                file.isEdited
                  ? 'text-amber-300 bg-[#211506] border border-amber-800/80'
                  : 'text-emerald-400 bg-[#07130a] border border-[#112416]'
              }`}>
                v{file.version}
              </span>

              {/* ENLARGED COPY BUTTON */}
              <button
                onClick={(e) => handleCopyClick(e, file.name)}
                disabled={isCopying}
                title={`Copy contents of ${file.name}`}
                className={`px-2.5 py-1 text-xs font-black tracking-wide rounded transition-all active:scale-95 shrink-0 ${
                  isCopied
                    ? 'bg-[#152e1d] text-emerald-300 border border-[#224b2f] shadow-[0_0_8px_rgba(16,185,129,0.3)]'
                    : 'bg-emerald-500 hover:bg-emerald-400 text-black shadow-md'
                }`}
              >
                {isCopied ? 'COPIED!' : isCopying ? '...' : 'COPY'}
              </button>

            </div>

          </div>
        );
      })}
    </div>
  );
};
