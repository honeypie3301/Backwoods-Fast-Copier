import React from 'react';
import { Search, Sliders, Type, Tag, X } from 'lucide-react';
import { ShortenPreset } from '../types';
import { shortenFileName, SHORTEN_PRESETS } from '../utils/shortenFileName';

interface ShortenControlProps {
  searchQuery: string;
  onSearchChange: (query: string) => void;
  maxLength: number;
  onMaxLengthChange: (length: number) => void;
  activePreset: ShortenPreset;
  onPresetSelect: (preset: ShortenPreset) => void;
  availableVersions: string[];
  selectedVersion: string | null;
  onVersionSelect: (version: string | null) => void;
  totalFiltered: number;
  totalCount: number;
}

export const ShortenControl: React.FC<ShortenControlProps> = ({
  searchQuery,
  onSearchChange,
  maxLength,
  onMaxLengthChange,
  activePreset,
  onPresetSelect,
  availableVersions,
  selectedVersion,
  onVersionSelect,
  totalFiltered,
  totalCount,
}) => {
  const sampleLongName = 'BackwoodsCore_v1.21.1_EntityRegistryProcedure.java';
  const shortenedSample = shortenFileName(sampleLongName, maxLength);

  return (
    <div className="bg-neutral-950 border border-neutral-900 rounded-xl p-4 sm:p-5 space-y-4 shadow-2xl">
      
      {/* Top Row: Search & Version Filter Badges */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
        
        {/* Search Bar */}
        <div className="relative flex-1 max-w-md">
          <Search className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-neutral-500" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            placeholder="Search files by title, extension, or content..."
            className="w-full pl-10 pr-9 py-2.5 bg-black border border-neutral-800 rounded-lg text-sm text-neutral-200 placeholder-neutral-500 focus:outline-none focus:border-emerald-500/70 focus:ring-1 focus:ring-emerald-500/30 transition-all font-mono"
          />
          {searchQuery && (
            <button
              onClick={() => onSearchChange('')}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-neutral-500 hover:text-neutral-300 p-0.5 rounded"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>

        {/* Dynamic Version Badges Filter */}
        <div className="flex flex-wrap items-center gap-1.5 text-xs font-mono">
          <span className="text-neutral-400 flex items-center gap-1 mr-1">
            <Tag className="w-3.5 h-3.5 text-neutral-500" />
            <span>Filter Version:</span>
          </span>

          <button
            onClick={() => onVersionSelect(null)}
            className={`px-2.5 py-1 rounded-md transition-all ${
              selectedVersion === null
                ? 'bg-neutral-800 text-white font-semibold border border-neutral-700'
                : 'bg-black text-neutral-400 border border-neutral-900 hover:text-neutral-200 hover:border-neutral-800'
            }`}
          >
            All
          </button>

          {availableVersions.map((ver) => (
            <button
              key={ver}
              onClick={() => onVersionSelect(selectedVersion === ver ? null : ver)}
              className={`px-2.5 py-1 rounded-md transition-all flex items-center gap-1 ${
                selectedVersion === ver
                  ? 'bg-emerald-950 text-emerald-300 font-semibold border border-emerald-700 shadow-[0_0_10px_rgba(16,185,129,0.2)]'
                  : 'bg-black text-neutral-400 border border-neutral-900 hover:text-neutral-200 hover:border-neutral-800'
              }`}
            >
              <span>v{ver}</span>
              {selectedVersion === ver && <X className="w-3 h-3 ml-0.5 text-emerald-400" />}
            </button>
          ))}
        </div>

      </div>

      {/* Bottom Row: Dynamic Filename Shortening Controls */}
      <div className="pt-3 border-t border-neutral-900/80 flex flex-col md:flex-row md:items-center justify-between gap-4 text-xs font-mono">
        
        {/* Dynamic Shortening Controls */}
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1.5 text-neutral-300 font-semibold">
            <Sliders className="w-4 h-4 text-emerald-400" />
            <span>Dynamic Name Shortener:</span>
          </div>

          {/* Preset Buttons */}
          <div className="flex items-center gap-1 bg-black p-1 rounded-lg border border-neutral-850">
            {(['compact', 'medium', 'generous', 'full'] as ShortenPreset[]).map((preset) => (
              <button
                key={preset}
                onClick={() => {
                  onPresetSelect(preset);
                  onMaxLengthChange(SHORTEN_PRESETS[preset]);
                }}
                className={`px-2.5 py-1 rounded-md capitalize transition-all ${
                  activePreset === preset
                    ? 'bg-neutral-800 text-emerald-400 font-bold border border-neutral-700 shadow-sm'
                    : 'text-neutral-400 hover:text-neutral-200'
                }`}
              >
                {preset === 'full' ? 'Full Name' : `${preset} (${SHORTEN_PRESETS[preset]}ch)`}
              </button>
            ))}
          </div>

          {/* Slider for custom character count */}
          {activePreset !== 'full' && (
            <div className="flex items-center gap-2 bg-black px-3 py-1.5 rounded-lg border border-neutral-850">
              <Type className="w-3.5 h-3.5 text-neutral-500" />
              <input
                type="range"
                min="14"
                max="65"
                value={maxLength}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  onMaxLengthChange(val);
                }}
                className="w-24 accent-emerald-400 cursor-pointer h-1.5 bg-neutral-800 rounded-lg"
              />
              <span className="text-emerald-400 font-bold min-w-[2.5rem] text-right">
                {maxLength}ch
              </span>
            </div>
          )}
        </div>

        {/* Dynamic Shortening Live Preview */}
        <div className="text-neutral-400 flex items-center gap-2 bg-black/60 px-3 py-1.5 rounded-lg border border-neutral-900 overflow-hidden">
          <span className="text-neutral-500 shrink-0">UI Preview:</span>
          <span className="text-neutral-200 font-semibold truncate max-w-[260px] sm:max-w-[340px]" title={sampleLongName}>
            {shortenedSample}
          </span>
        </div>

      </div>

      {/* Filter Stats summary */}
      {(searchQuery || selectedVersion) && (
        <div className="pt-2 flex items-center justify-between text-xs font-mono text-neutral-400">
          <span>
            Showing <strong className="text-emerald-400">{totalFiltered}</strong> of {totalCount} files
          </span>
          <button
            onClick={() => {
              onSearchChange('');
              onVersionSelect(null);
            }}
            className="text-neutral-400 hover:text-emerald-400 underline transition-colors"
          >
            Clear all filters
          </button>
        </div>
      )}

    </div>
  );
};
