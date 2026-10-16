import React, { useState, useEffect } from 'react';
import { FileItem } from '../types';
import { copyToClipboard } from '../utils/copyToClipboard';

interface RightPanelViewerProps {
  file: FileItem | null;
  cachedContent?: string;
  onClose?: () => void;
  onSaveContent?: (filename: string, content: string) => Promise<boolean>;
  onDeleteFile?: (filename: string) => void;
  onCopyContent?: (filename: string) => Promise<boolean>;
}

export const RightPanelViewer: React.FC<RightPanelViewerProps> = ({
  file,
  cachedContent,
}) => {
  const [content, setContent] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);

  const fileName = file?.name;

  useEffect(() => {
    if (!fileName) {
      setContent('');
      setLoading(false);
      return;
    }

    if (cachedContent !== undefined) {
      setContent(cachedContent);
      setLoading(false);
      return;
    }

    setLoading(true);
    let isCancelled = false;

    fetch(`/api/files/${encodeURIComponent(fileName)}/content`)
      .then((res) => res.json())
      .then((data) => {
        if (!isCancelled) {
          if (data.success) {
            setContent(data.content);
          } else {
            setContent('// Failed to load file content.');
          }
        }
      })
      .catch((err) => {
        if (!isCancelled) {
          setContent(`// Error loading file: ${err.message}`);
        }
      })
      .finally(() => {
        if (!isCancelled) {
          setLoading(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [fileName, cachedContent]);

  if (!file) {
    return (
      <div className="flex-1 h-full bg-[#030504] flex flex-col items-center justify-center p-8 text-center select-none font-mono">
        <p className="text-xs text-[#4e6153] max-w-sm leading-relaxed">
          Select a file from the sidebar to view its contents, or click the enlarged <span className="text-emerald-400 font-semibold">COPY</span> button beside any file to copy directly.
        </p>
      </div>
    );
  }

  const lines = content.split('\n');

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
          <span className="text-[#4e6153] text-[10px] shrink-0">
            ({lines.length} lines · {file.sizeFormatted})
          </span>
        </div>

      </div>

      {/* Code Editor / Content Display Area */}
      <div className="flex-1 overflow-hidden relative bg-[#030504]">
        {loading ? (
          <div className="h-full flex items-center justify-center text-[#4e6153] gap-2 font-mono text-xs">
            <div className="w-3.5 h-3.5 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
            <span>Loading content...</span>
          </div>
        ) : (
          <div className="h-full overflow-auto flex select-text">
            {/* Line Numbers */}
            <div className="py-4 pl-3 pr-3 bg-[#030504] text-[#334237] text-right select-none border-r border-[#0d1610] font-mono text-xs leading-relaxed shrink-0">
              {lines.map((_, i) => (
                <div key={i}>{i + 1}</div>
              ))}
            </div>
            {/* Code Body */}
            <pre className="py-4 px-4 text-[#c2d4c5] font-mono text-xs leading-relaxed whitespace-pre font-normal flex-1">
              {content || '// File is empty.'}
            </pre>
          </div>
        )}
      </div>

    </div>
  );
};
