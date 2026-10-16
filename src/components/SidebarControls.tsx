import React from 'react';
import { Search, X, Archive } from 'lucide-react';
import { ShortenPreset } from '../types';
import { SHORTEN_PRESETS } from '../utils/shortenFileName';

interface SidebarControlsProps {
  searchQuery: string;
  onSearchChange: (q: string) => void;
  availableVersions: string[];
  selectedVersion: string | null;
  onVersionSelect: (v: string | null) => void;
  availableEntities: { id: string; label: string; count: number }[];
  selectedEntity: string | null;
  onEntitySelect: (e: string | null) => void;
  maxLength: number;
  onMaxLengthChange: (len: number) => void;
  activePreset: ShortenPreset;
  onPresetSelect: (preset: ShortenPreset) => void;
  totalFiltered: number;
  totalCount: number;
  unreadCount: number;
  editedCount: number;
  showEditedOnly: boolean;
  onToggleEditedOnly: () => void;
  onMarkAllRead: () => void;
  selectedCount: number;
  onSelectAllFiltered: () => void;
  onDeselectAll: () => void;
  onDownloadZip: () => void;
  isZipping: boolean;
}

export const SidebarControls: React.FC<SidebarControlsProps> = ({
  searchQuery,
  onSearchChange,
  availableVersions,
  selectedVersion,
  onVersionSelect,
  availableEntities,
  selectedEntity,
  onEntitySelect,
  maxLength,
  onMaxLengthChange,
  activePreset,
  onPresetSelect,
  totalFiltered,
  totalCount,
  unreadCount,
  editedCount,
  showEditedOnly,
  onToggleEditedOnly,
  onMarkAllRead,
  selectedCount,
  onSelectAllFiltered,
  onDeselectAll,
  onDownloadZip,
  isZipping,
}) => {
  return (
    <div className="p-2 border-b border-[#111a13] bg-[#040705] space-y-2 font-mono text-xs select-none">
      
      {/* Search Input */}
      <div className="relative">
        <Search className="w-3.5 h-3.5 absolute left-2 top-1/2 -translate-y-1/2 text-[#425447]" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Filter files, entities, versions..."
          className="w-full pl-7 pr-6 py-1 bg-[#070c09] border border-[#142016] rounded text-xs text-[#d0e0d3] placeholder-[#3f5244] focus:outline-none focus:border-emerald-600/80 font-mono"
        />
        {searchQuery && (
          <button
            onClick={() => onSearchChange('')}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-[#425447] hover:text-[#d0e0d3]"
          >
            <X className="w-3 h-3" />
          </button>
        )}
      </div>

      {/* Entity Filter Badges */}
      <div className="flex items-center gap-1 overflow-x-auto pb-0.5 no-scrollbar">
        <span className="text-[#3f5244] text-[10px] uppercase font-bold shrink-0 mr-0.5">
          Entity:
        </span>

        <button
          onClick={() => onEntitySelect(null)}
          className={`px-2 py-0.5 text-[10px] font-bold rounded shrink-0 ${
            selectedEntity === null
              ? 'bg-emerald-500 text-black'
              : 'bg-[#070c09] text-[#718776] border border-[#132015] hover:text-[#c2d4c5]'
          }`}
        >
          All Entities
        </button>

        {availableEntities.map((ent) => (
          <button
            key={ent.id}
            onClick={() => onEntitySelect(selectedEntity === ent.id ? null : ent.id)}
            className={`px-2 py-0.5 text-[10px] font-bold rounded shrink-0 flex items-center gap-0.5 ${
              selectedEntity === ent.id
                ? 'bg-emerald-500 text-black'
                : 'bg-[#070c09] text-[#718776] border border-[#132015] hover:text-[#c2d4c5]'
            }`}
          >
            <span>{ent.label} ({ent.count})</span>
            {selectedEntity === ent.id && <X className="w-2.5 h-2.5 text-black" />}
          </button>
        ))}
      </div>

      {/* Version Filter Badges */}
      <div className="flex items-center gap-1 overflow-x-auto pb-0.5 no-scrollbar">
        <span className="text-[#3f5244] text-[10px] uppercase font-bold shrink-0 mr-0.5">
          Version:
        </span>

        <button
          onClick={() => {
            if (showEditedOnly) onToggleEditedOnly();
            onVersionSelect(null);
          }}
          className={`px-2 py-0.5 text-[10px] font-bold rounded shrink-0 ${
            selectedVersion === null && !showEditedOnly
              ? 'bg-emerald-500 text-black'
              : 'bg-[#070c09] text-[#718776] border border-[#132015] hover:text-[#c2d4c5]'
          }`}
        >
          All Versions
        </button>

        {availableVersions.map((ver) => (
          <button
            key={ver}
            onClick={() => {
              if (showEditedOnly) onToggleEditedOnly();
              onVersionSelect(selectedVersion === ver ? null : ver);
            }}
            className={`px-2 py-0.5 text-[10px] font-bold rounded shrink-0 flex items-center gap-0.5 ${
              selectedVersion === ver && !showEditedOnly
                ? 'bg-emerald-500 text-black'
                : 'bg-[#070c09] text-[#718776] border border-[#132015] hover:text-[#c2d4c5]'
            }`}
          >
            <span>v{ver}</span>
            {selectedVersion === ver && !showEditedOnly && <X className="w-2.5 h-2.5 text-black" />}
          </button>
        ))}

        {editedCount > 0 && (
          <button
            onClick={onToggleEditedOnly}
            className={`px-2 py-0.5 text-[10px] font-black rounded shrink-0 flex items-center gap-1 transition-all ${
              showEditedOnly
                ? 'bg-amber-400 text-black shadow-[0_0_10px_rgba(251,191,36,0.6)]'
                : 'bg-[#181105] text-amber-300 border border-amber-600/80 hover:border-amber-400'
            }`}
            title="Filter by newly updated files"
          >
            <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping" />
            <span>Updated ({editedCount})</span>
            {showEditedOnly && <X className="w-2.5 h-2.5 text-black" />}
          </button>
        )}
      </div>

      {/* Dynamic Shortening Controls + Mark All Read */}
      <div className="pt-1 border-t border-[#0e1710] flex items-center justify-between gap-1 text-[10px]">
        
        <div className="flex items-center gap-1">
          <span className="text-[#4f6354] font-semibold">Shorten:</span>
          <div className="flex items-center gap-0.5 bg-[#030604] p-0.5 rounded border border-[#101a12]">
            {(['compact', 'medium', 'full'] as ShortenPreset[]).map((preset) => (
              <button
                key={preset}
                onClick={() => {
                  onPresetSelect(preset);
                  onMaxLengthChange(SHORTEN_PRESETS[preset]);
                }}
                className={`px-1.5 py-0.2 rounded capitalize ${
                  activePreset === preset
                    ? 'bg-[#102015] text-emerald-400 font-bold'
                    : 'text-[#4f6354] hover:text-[#9bb3a2]'
                }`}
              >
                {preset === 'full' ? 'Full' : preset}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          <button
            onClick={onMarkAllRead}
            disabled={unreadCount === 0}
            className={`text-[10px] font-mono px-2 py-0.5 rounded border font-semibold transition-all ${
              unreadCount > 0
                ? 'text-emerald-300 bg-[#08150c] border-[#18331e] hover:bg-emerald-900/40 hover:text-emerald-200 cursor-pointer shadow-[0_0_6px_rgba(16,185,129,0.2)]'
                : 'text-[#36473b] bg-[#040805] border-[#0a120c] cursor-not-allowed opacity-60'
            }`}
            title={unreadCount > 0 ? `Mark ${unreadCount} unread files as read` : 'All files marked as read'}
          >
            Mark All Read {unreadCount > 0 ? `(${unreadCount})` : '✓'}
          </button>

          {(searchQuery || selectedVersion || selectedEntity || showEditedOnly) && (
            <button
              onClick={() => {
                onSearchChange('');
                onVersionSelect(null);
                onEntitySelect(null);
                if (showEditedOnly) onToggleEditedOnly();
              }}
              className="text-[#5b7161] hover:text-emerald-400 underline"
            >
              Clear
            </button>
          )}
        </div>

      </div>

      {/* Batch Selection & ZIP Download Toolbar */}
      <div className="pt-1.5 border-t border-[#0e1710] flex items-center justify-between gap-1.5">
        <div className="flex items-center gap-1">
          <button
            onClick={selectedCount === totalFiltered ? onDeselectAll : onSelectAllFiltered}
            className="px-2 py-0.5 bg-[#08120a] hover:bg-[#0f2414] border border-[#142918] text-[#9bb3a2] hover:text-white rounded text-[10px] font-bold cursor-pointer"
          >
            {selectedCount === totalFiltered && totalFiltered > 0 ? 'Deselect All' : `Select All (${totalFiltered})`}
          </button>
          {selectedCount > 0 && selectedCount < totalFiltered && (
            <button
              onClick={onDeselectAll}
              className="px-1.5 py-0.5 text-[#5b7161] hover:text-rose-400 text-[10px] cursor-pointer"
            >
              Clear ({selectedCount})
            </button>
          )}
        </div>

        <button
          onClick={onDownloadZip}
          disabled={isZipping || totalFiltered === 0}
          className={`px-3 py-1 text-xs font-black rounded flex items-center gap-1.5 transition-all shadow-md active:scale-95 cursor-pointer ${
            isZipping
              ? 'bg-[#0f2617] text-emerald-400 border border-emerald-800 cursor-wait'
              : 'bg-emerald-500 hover:bg-emerald-400 text-black shadow-[0_0_12px_rgba(16,185,129,0.3)]'
          }`}
          title="Export selected or filtered files as a ZIP package"
        >
          <Archive className="w-3.5 h-3.5" />
          <span>{isZipping ? 'Zipping...' : `ZIP (${selectedCount > 0 ? selectedCount : totalFiltered})`}</span>
        </button>
      </div>

    </div>
  );
};
