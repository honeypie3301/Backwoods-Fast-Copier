/**
 * ============================================================================
 * CONSTANT REMINDER:
 * Always fully import so the updated frame logic is always there.
 * ============================================================================
 */

import React from 'react';

interface HeaderProps {
  fileCount: number;
  totalSizeFormatted: string;
  isRefreshing: boolean;
  editedCount: number;
  liveSyncStatus?: 'connected' | 'connecting' | 'disconnected';
  onRefresh: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  fileCount,
  totalSizeFormatted,
  isRefreshing,
  editedCount,
  liveSyncStatus = 'connected',
  onRefresh,
}) => {
  return (
    <header className="h-10 shrink-0 border-b border-[#111a13] bg-[#040605] px-4 flex items-center justify-between text-xs font-mono select-none">
      
      {/* Minimal Title & Stats */}
      <div className="flex items-center gap-2.5">
        <span className="font-bold text-[#d0e0d3] text-xs">uploaded_files/</span>
        <span className="text-[11px] text-[#5b7362]">
          · {fileCount} {fileCount === 1 ? 'file' : 'files'} ({totalSizeFormatted})
        </span>

        {editedCount > 0 && (
          <span className="px-1.5 py-0.2 rounded bg-amber-950/80 border border-amber-800/80 text-amber-300 text-[10px] font-bold shadow-[0_0_8px_rgba(245,158,11,0.2)]">
            {editedCount} updated
          </span>
        )}
      </div>

      {/* Right Controls: Live Sync Indicator & Refresh Button */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-[10px]" title={`Live Filesystem Sync: ${liveSyncStatus}`}>
          <span
            className={`w-1.5 h-1.5 rounded-full transition-colors ${
              liveSyncStatus === 'connected'
                ? 'bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.6)] animate-pulse'
                : liveSyncStatus === 'connecting'
                ? 'bg-amber-400 animate-ping'
                : 'bg-zinc-600'
            }`}
          />
          <span className="text-[#455b4b]">
            {liveSyncStatus === 'connected'
              ? 'live sync'
              : liveSyncStatus === 'connecting'
              ? 'connecting...'
              : 'offline'}
          </span>
        </div>

        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="text-[11px] font-mono text-[#6c8271] hover:text-emerald-400 transition-colors disabled:opacity-50 flex items-center gap-1"
        >
          <span>{isRefreshing ? 'syncing...' : 'refresh'}</span>
        </button>
      </div>

    </header>
  );
};
