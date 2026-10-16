import React, { useState, useEffect } from 'react';
import { X, Copy, Check, Save, ShieldCheck, Download, Code, Edit3, Trash2 } from 'lucide-react';
import { FileItem } from '../types';
import { copyToClipboard } from '../utils/copyToClipboard';

interface FileViewerModalProps {
  file: FileItem | null;
  onClose: () => void;
  onSaveContent: (filename: string, content: string) => Promise<boolean>;
  onDeleteFile: (filename: string) => void;
  cachedContent?: string;
}

export const FileViewerModal: React.FC<FileViewerModalProps> = ({
  file,
  onClose,
  onSaveContent,
  onDeleteFile,
  cachedContent,
}) => {
  const [content, setContent] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [isEditing, setIsEditing] = useState<boolean>(false);
  const [editedContent, setEditedContent] = useState<string>('');
  const [saving, setSaving] = useState<boolean>(false);
  const [copied, setCopied] = useState<boolean>(false);

  useEffect(() => {
    if (!file) return;

    if (cachedContent !== undefined) {
      setContent(cachedContent);
      setEditedContent(cachedContent);
      setLoading(false);
    } else {
      setLoading(true);
      fetch(`/api/files/${encodeURIComponent(file.name)}/content`)
        .then((res) => res.json())
        .then((data) => {
          if (data.success) {
            setContent(data.content);
            setEditedContent(data.content);
          } else {
            setContent(`// Error loading file content: ${data.error}`);
          }
        })
        .catch((err) => {
          setContent(`// Network error loading file: ${err.message}`);
        })
        .finally(() => {
          setLoading(false);
        });
    }
  }, [file, cachedContent]);

  if (!file) return null;

  const handleCopy = async () => {
    const textToCopy = isEditing ? editedContent : content;
    const success = await copyToClipboard(textToCopy);
    if (success) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleSave = async () => {
    if (!file) return;
    setSaving(true);
    const success = await onSaveContent(file.name, editedContent);
    setSaving(false);
    if (success) {
      setContent(editedContent);
      setIsEditing(false);
    }
  };

  const handleDownload = () => {
    const element = document.createElement('a');
    const blob = new Blob([isEditing ? editedContent : content], { type: 'text/plain' });
    element.href = URL.createObjectURL(blob);
    element.download = file.name;
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
  };

  const lines = (isEditing ? editedContent : content).split('\n');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className="bg-black border border-neutral-800 rounded-2xl w-full max-w-5xl h-[88vh] flex flex-col shadow-2xl overflow-hidden">
        
        {/* Modal Header */}
        <div className="p-4 sm:p-5 border-b border-neutral-850 flex items-center justify-between gap-4 bg-neutral-950">
          
          <div className="flex items-center gap-3 min-w-0">
            <div className="p-2 rounded-lg bg-neutral-900 border border-neutral-800 shrink-0 text-emerald-400">
              <Code className="w-5 h-5" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="font-mono text-base sm:text-lg font-bold text-white truncate max-w-md" title={file.name}>
                  {file.name}
                </h2>
                <div className="px-2 py-0.5 text-xs font-mono font-semibold text-emerald-300 bg-emerald-950/80 border border-emerald-800 rounded-md">
                  v{file.version}
                </div>
              </div>
              <p className="text-xs text-neutral-400 font-mono mt-0.5">
                Path: <span className="text-neutral-300">/uploaded_files/{file.name}</span> · {file.sizeFormatted} · {lines.length} lines
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {/* Edit / View Toggle */}
            <button
              onClick={() => setIsEditing(!isEditing)}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono rounded-lg border transition-all ${
                isEditing
                  ? 'bg-amber-950/80 text-amber-300 border-amber-700'
                  : 'bg-neutral-900 text-neutral-300 border-neutral-800 hover:text-white hover:bg-neutral-800'
              }`}
            >
              <Edit3 className="w-3.5 h-3.5" />
              <span>{isEditing ? 'Viewing Mode' : 'Edit File'}</span>
            </button>

            {/* Save Button */}
            {isEditing && (
              <button
                onClick={handleSave}
                disabled={saving}
                className="flex items-center gap-1.5 px-3.5 py-1.5 text-xs font-mono font-semibold text-black bg-emerald-400 hover:bg-emerald-300 rounded-lg transition-all"
              >
                <Save className="w-3.5 h-3.5" />
                <span>{saving ? 'Saving...' : 'Save Changes'}</span>
              </button>
            )}

            {/* Copy Content Button */}
            <button
              onClick={handleCopy}
              className={`flex items-center gap-1.5 px-3.5 py-1.5 text-xs font-mono font-semibold rounded-lg border transition-all ${
                copied
                  ? 'bg-emerald-500 text-black border-emerald-400'
                  : 'bg-neutral-900 text-emerald-400 border-emerald-900 hover:bg-emerald-950'
              }`}
            >
              {copied ? <Check className="w-3.5 h-3.5 stroke-[3]" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copied ? 'Copied!' : 'Copy Content'}</span>
            </button>

            {/* Download */}
            <button
              onClick={handleDownload}
              title="Download file"
              className="p-2 text-neutral-400 hover:text-white bg-neutral-900 border border-neutral-800 rounded-lg transition-all"
            >
              <Download className="w-4 h-4" />
            </button>

            {/* Close Modal */}
            <button
              onClick={onClose}
              className="p-2 text-neutral-400 hover:text-white bg-neutral-900 border border-neutral-800 rounded-lg transition-all"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

        </div>

        {/* Editor / Content Area */}
        <div className="flex-1 bg-black overflow-hidden relative flex flex-col font-mono text-xs sm:text-sm">
          {loading ? (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 text-neutral-400">
              <div className="w-6 h-6 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
              <span>Fetching file content...</span>
            </div>
          ) : isEditing ? (
            <textarea
              value={editedContent}
              onChange={(e) => setEditedContent(e.target.value)}
              className="flex-1 w-full p-4 bg-black text-neutral-200 focus:outline-none font-mono resize-none leading-relaxed"
              placeholder="Enter file content..."
            />
          ) : (
            <div className="flex-1 overflow-auto p-4 leading-relaxed flex">
              {/* Line Numbers */}
              <div className="select-none text-neutral-600 text-right pr-4 border-r border-neutral-900 shrink-0 space-y-1">
                {lines.map((_, i) => (
                  <div key={i}>{i + 1}</div>
                ))}
              </div>
              {/* Code text */}
              <div className="pl-4 text-neutral-300 space-y-1 overflow-x-auto whitespace-pre font-mono">
                {lines.map((line, i) => (
                  <div key={i}>{line || '\u00A0'}</div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Modal Footer */}
        <div className="p-3 border-t border-neutral-900 bg-neutral-950 flex items-center justify-between text-xs font-mono text-neutral-500">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
            <span>Version v{file.version} (Detected from {file.versionSource})</span>
          </div>
          <button
            onClick={() => {
              onDeleteFile(file.name);
              onClose();
            }}
            className="text-rose-500 hover:text-rose-400 flex items-center gap-1 transition-colors"
          >
            <Trash2 className="w-3.5 h-3.5" />
            <span>Delete File</span>
          </button>
        </div>

      </div>
    </div>
  );
};
